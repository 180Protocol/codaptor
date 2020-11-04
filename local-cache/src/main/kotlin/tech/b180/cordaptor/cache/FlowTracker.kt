package tech.b180.cordaptor.cache

import com.google.common.cache.Cache
import com.google.common.cache.CacheBuilder
import io.reactivex.rxjava3.observers.DisposableSingleObserver
import net.corda.core.flows.FlowLogic
import tech.b180.cordaptor.corda.CordaFlowHandle
import tech.b180.cordaptor.corda.CordaFlowResult
import tech.b180.cordaptor.corda.CordaFlowSnapshot
import tech.b180.cordaptor.kernel.loggerFor
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference
import kotlin.NoSuchElementException
import kotlin.reflect.KClass

/**
 * In-memory repository for information about active and completed flows.
 * It knows how to subscribe to relevant flow progress updates and maintain up-to-date information,
 * in accordance with the settings for a particular flow class.
 */
@Suppress("UnstableApiUsage")
class FlowTracker<ReturnType: Any>(
    flowClass: KClass<out FlowLogic<ReturnType>>,
    settings: Settings
) {

  companion object {
    private val logger = loggerFor<FlowTracker<*>>()
  }

  /** Registry of actively running flows */
  private val runningFlows = ConcurrentHashMap<UUID, FlowCacheEntry<ReturnType>>()

  /** Cache for results of completed flows, or null if disabled */
  private val completedFlows: Cache<UUID, FlowCacheEntry<ReturnType>>?

  init {
    val cacheSettings = settings.getFlowResultsCacheSettings(flowClass)

    completedFlows = if (cacheSettings.enabled) {
      logger.debug("Constructing local cache for flows {} using {}", flowClass.qualifiedName, cacheSettings)

      CacheBuilder.newBuilder()
          .expireAfterWrite(cacheSettings.expireAfterCompletion)
          .build<UUID, FlowCacheEntry<ReturnType>>()
    } else {
      logger.info("Snapshots cache is disabled for flows ${flowClass.qualifiedName}, no " +
          "information will be available after such flows complete")

      null
    }
  }

  fun addFlow(handle: CordaFlowHandle<ReturnType>) {
    // preventing handle to be captured by the callback closure and potentially holding up resources
    val runId = handle.flowRunId

    runningFlows[handle.flowRunId] = FlowCacheEntry(handle) {
      // make available in snapshots cache if it is enabled (not null)
      completedFlows?.put(runId, this@FlowCacheEntry)

      // make no longer available for lookup among the active flows
      runningFlows.remove(runId)
    }
  }

  /**
   * Returns most recent snapshot of a flow instance with a given run id,
   * or throws [NoSuchElementException].
   *
   * FIXME implement logic for retaining information about evicted flows
   */
  fun getFlowSnapshot(runId: UUID): CordaFlowSnapshot<ReturnType> {
    return tryGetRunningFlow(runId)?.snapshot
        ?: tryGetCompletedFlow(runId)?.snapshot
        ?: throw NoSuchElementException(runId.toString())
  }

  private fun tryGetRunningFlow(runId: UUID): FlowCacheEntry<ReturnType>? = runningFlows[runId]
  private fun tryGetCompletedFlow(runId: UUID): FlowCacheEntry<ReturnType>? = completedFlows?.getIfPresent(runId)

  /** Cache entry that updates its own latest snapshot atomically */
  class FlowCacheEntry<ReturnType: Any>(
      flowHandle: CordaFlowHandle<ReturnType>,
      onFinalSnapshot: FlowCacheEntry<ReturnType>.() -> Unit
  ) {

    private val snapshotHolder = AtomicReference<CordaFlowSnapshot<ReturnType>>(flowHandle.asInitialSnapshot())

    init {
      val progressSubscription = flowHandle.flowProgressUpdates?.subscribe { next ->
        snapshotHolder.set(flowHandle.asInitialSnapshot().withProgress(next))
      }

      flowHandle.flowResultPromise.subscribe(object : DisposableSingleObserver<CordaFlowResult<ReturnType>>() {
        override fun onSuccess(result: CordaFlowResult<ReturnType>) {
          val finalSnapshot = flowHandle.asInitialSnapshot().withResult(result)

          snapshotHolder.set(flowHandle.asInitialSnapshot().withResult(result))
          progressSubscription?.dispose()
          dispose()

          onFinalSnapshot(this@FlowCacheEntry)
        }

        override fun onError(e: Throwable) {
          // flow errors per se would have already been wrapped into a snapshot,
          // so this would indicate potential bug in the reactive code
          logger.error("Unexpected error in flow execution tracking", e)
          dispose()
        }
      })
    }

    val snapshot: CordaFlowSnapshot<ReturnType>
      get() = snapshotHolder.get()
  }
}