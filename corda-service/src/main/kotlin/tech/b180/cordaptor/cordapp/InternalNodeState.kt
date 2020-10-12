package tech.b180.cordaptor.cordapp

import hu.akarnokd.rxjava3.interop.RxJavaInterop
import io.reactivex.rxjava3.core.Observable
import io.reactivex.rxjava3.core.Single
import net.corda.core.contracts.*
import net.corda.core.crypto.SecureHash
import net.corda.core.flows.FlowLogic
import net.corda.core.flows.StateMachineRunId
import net.corda.core.node.AppServiceHub
import net.corda.core.node.NodeInfo
import net.corda.core.node.services.TransactionStorage
import net.corda.core.transactions.SignedTransaction
import org.koin.core.inject
import tech.b180.cordaptor.corda.*
import tech.b180.cordaptor.kernel.CordaptorComponent
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import kotlin.reflect.KClass

/**
 * Implementation of [CordaNodeState] interface providing access to a state
 * maintained within a particular Corda node using APIs available internally within the node.
 */
class CordaNodeStateImpl : CordaNodeStateInner, CordaptorComponent {

  private val appServiceHub: AppServiceHub by inject()
  private val transactionStorage: TransactionStorage by inject()
  private val flowDispatcher: CordaFlowDispatcher by inject()

  override val nodeInfo: NodeInfo
    get() = appServiceHub.myInfo

  override fun <T : ContractState> findStateByRef(stateRef: StateRef, clazz: Class<T>): StateAndRef<T>? {
    return try {
      appServiceHub.toStateAndRef(stateRef)
    } catch (e: TransactionResolutionException) {
      null
    }
  }

  override fun findTransactionByHash(hash: SecureHash): SignedTransaction? {
    return transactionStorage.getTransaction(hash)
  }

  override fun <T : ContractState> countStates(query: CordaStateQuery<T>): Int {
    TODO("Not yet implemented")
  }

  override fun <T : Any> aggregateFungibleState(query: CordaStateQuery<FungibleState<T>>, clazz: Class<T>): Amount<T> {
    TODO("Not yet implemented")
  }

  override fun <T : ContractState> trackStates(query: CordaStateQuery<T>): io.reactivex.rxjava3.core.Observable<T> {
//    appServiceHub.vaultService.trackBy()
    TODO("Not yet implemented")
  }

  @Suppress("UNCHECKED_CAST")
  override fun <ReturnType: Any> initiateFlow(flowInstance: FlowLogic<ReturnType>): CordaFlowHandle<ReturnType> {
    return flowDispatcher.initiateFlow(flowInstance) as CordaFlowHandle<ReturnType>
  }

  @Suppress("UNCHECKED_CAST")
  override fun <ReturnType: Any> trackRunningFlow(
      flowClass: KClass<out FlowLogic<ReturnType>>, runId: StateMachineRunId): CordaFlowHandle<ReturnType> {

    val handle = flowDispatcher.findFlowHandle(runId)
        ?: throw NoSuchElementException("Unknown run id $runId -- flow may have already completed")

    return handle as CordaFlowHandle<ReturnType>
  }
}

/**
 * Wrapper for Corda flow initiation API that keeps track of actively running
 * flows and allowing to look them up by run id.
 */
class CordaFlowDispatcher : CordaptorComponent {
  private val appServiceHub: AppServiceHub by inject()

  private val activeHandles = ConcurrentHashMap<StateMachineRunId, CordaFlowHandle<Any>>()

  fun initiateFlow(flowInstance: FlowLogic<Any>): CordaFlowHandle<Any> {
    val startedAt = Instant.now()

    val cordaHandle = appServiceHub.startTrackedFlow(flowInstance)

    val ourHandle = CordaFlowHandle(
        startedAt = startedAt,
        flowClass = flowInstance::class,
        flowRunId = cordaHandle.id.uuid,
        flowResultPromise = Single.fromFuture(cordaHandle.returnValue)
            .onErrorReturn { CordaFlowResult.forError<Any>(it) }
            .map { CordaFlowResult.forValue(it) },

        // if the feed is not available, return observable that returns empty progress info once
        flowProgressUpdates = cordaHandle.stepsTreeFeed?.let { feed ->
          RxJavaInterop.toV3Observable(feed.updates).map { steps ->
            CordaFlowProgress(steps.map { step -> CordaFlowProgressStep(step.first, step.second) })
          }
        } ?: Observable.just(CordaFlowProgress.noProgressInfo)
    )

    ourHandle.flowResultPromise
        .subscribe { _, _ -> activeHandles.remove(cordaHandle.id) }

    activeHandles[cordaHandle.id] = ourHandle

    return ourHandle
  }

  fun findFlowHandle(runId: StateMachineRunId): CordaFlowHandle<Any>? {
    return activeHandles[runId]
  }
}
