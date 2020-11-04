package tech.b180.cordaptor.corda

import hu.akarnokd.rxjava3.interop.RxJavaInterop
import io.reactivex.rxjava3.subjects.ReplaySubject
import io.reactivex.rxjava3.subjects.SingleSubject
import net.corda.core.concurrent.CordaFuture
import net.corda.core.flows.FlowLogic
import org.slf4j.Logger
import java.time.Instant
import java.util.*

/**
 * Generic logic for translating an instance of [CordaFlowInstruction] into a Corda API
 * interaction that initiates a flow of a given type, and constructing an instance
 * of [CordaFlowHandle] that is used to track the progress of the flow.
 *
 * This is a thread-safe service class.
 */
abstract class FlowInitiator<ReturnType: Any> {

  data class Handle<ReturnType: Any>(
      val runId: UUID,
      val returnValue: CordaFuture<ReturnType>,
      val progressUpdates: rx.Observable<String>? = null
  )

  protected abstract val instanceLogger: Logger
  protected abstract fun doInitiateFlow(instruction: CordaFlowInstruction<FlowLogic<ReturnType>>): Handle<ReturnType>

  /**
   * Initiates the flow using parameters and initiation options provided
   * by the [CordaFlowInstruction].
   */
  fun initiateFlow(instruction: CordaFlowInstruction<FlowLogic<ReturnType>>): CordaFlowHandle<ReturnType> {
    instanceLogger.debug("Preparing to initiate flow {}", instruction.flowClass.qualifiedName)

    val handle = doInitiateFlow(instruction)

    instanceLogger.debug("Flow {} started with run id {}", instruction.flowClass.qualifiedName, handle.runId)

    // asynchronously emit the result when flow result future completes
    // this will happen on the node's state machine thread
    val resultSubject = SingleSubject.create<CordaFlowResult<ReturnType>>()
    handle.returnValue.then {
      try {
        val flowResult = it.get()
        instanceLogger.debug("Flow {} returned {}", handle.runId, flowResult)
        resultSubject.onSuccess(CordaFlowResult.forValue(flowResult))
      } catch (e: Throwable) {
        instanceLogger.debug("Flow {} threw an error:", handle.runId, e)
        resultSubject.onSuccess(CordaFlowResult.forError(e))
      }
    }

    val progressUpdates = if (handle.progressUpdates != null) {
      val timestampedProgressUpdates = RxJavaInterop.toV3Observable(handle.progressUpdates).map {
        instanceLogger.debug("Progress update for flow {}: {}", handle.runId, it)
        CordaFlowProgress(it) // this will record a timestamp at this point
      }

      // passing snapshots through a replay subject to ensure timestamps are assigned once
      // regardless of how many subscribers listen to the feed
      val progressSnapshots = ReplaySubject.create<CordaFlowProgress>()
      timestampedProgressUpdates.subscribe(progressSnapshots)

      progressSnapshots.doOnDispose {
        instanceLogger.debug("Progress observable for flow {} was disposed", handle.runId)
      }.doOnComplete {
        instanceLogger.debug("Progress observable for flow {} completed", handle.runId)
      }
    } else {
      null
    }

    return CordaFlowHandle(
        startedAt = Instant.now(),
        flowClass = instruction.flowClass,
        flowRunId = handle.runId,
        flowResultPromise = resultSubject,
        flowProgressUpdates = progressUpdates
    )
  }
}