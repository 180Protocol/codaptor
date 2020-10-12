package tech.b180.cordaptor.corda

import io.reactivex.rxjava3.core.Observable
import io.reactivex.rxjava3.core.Single
import net.corda.core.contracts.*
import net.corda.core.crypto.SecureHash
import net.corda.core.flows.FlowLogic
import net.corda.core.flows.StateMachineRunId
import net.corda.core.node.NodeInfo
import net.corda.core.transactions.SignedTransaction
import java.time.Instant
import java.util.*
import kotlin.reflect.KClass

/**
 * Single access point for all state of a particular Corda node that is exposed
 * via an API endpoint by Cordaptor.
 *
 * Different modules implement this interface in a different way depending
 * on the nature of their interaction with the underlying node. Different caching
 * strategies also change the way the implementation behaves.
 *
 * We use rxjava3 for forward compatibility. At the moment Corda still uses rxjava1,
 * so the implementation needs to convert between different versions of the API.
 * Assumption is that Corda will be upgraded to more recent version soon.
 */
interface CordaNodeState {

  val nodeInfo: NodeInfo

  fun <T : ContractState> findStateByRef(stateRef: StateRef, clazz: Class<T>): StateAndRef<T>?

  fun findTransactionByHash(hash: SecureHash): SignedTransaction?

  fun <T : ContractState> countStates(query: CordaStateQuery<T>): Int

  fun <T : Any> aggregateFungibleState(query: CordaStateQuery<FungibleState<T>>, clazz: Class<T>): Amount<T>

  fun <T : ContractState> trackStates(query: CordaStateQuery<T>): Observable<T>

  fun <ReturnType: Any> initiateFlow(flowInstance: FlowLogic<ReturnType>): CordaFlowHandle<ReturnType>

  /**
   * Allows to observe the execution of a flow that was started earlier.
   *
   * Throws [NoSuchElementException] if the flow does not exist or is no longer running.
   * There is no way of telling these conditions apart in Corda API.
   *
   * See [CordaFlowCache] for ways of accessing details about completed/failed flows.
   */
  fun <ReturnType: Any> trackRunningFlow(
      flowClass: KClass<out FlowLogic<ReturnType>>, runId: StateMachineRunId): CordaFlowHandle<ReturnType>
}

/**
 * Marker interface allowing decorating implementation of [CordaNodeState] to locate
 * the underlying implementation.
 */
interface CordaNodeStateInner : CordaNodeState

/**
 * Corda treats all flows as disposable in the sense that once completed there is no way
 * to know the outcome of the flow. This makes subscribing to a completion future the only way to
 * know about the result, which is inconvenient in many low-stake low-tech scenarios,
 * where polling would be more appropriate.
 *
 * Instead, the implementation is expected to cache the completion state of flows
 * and make them available for polling for a period of time through a configurable cache.
 */
interface CordaFlowCache {

  fun <ReturnType: Any> getFlowInstance(
      flowClass: KClass<ReturnType>, flowRunId: StateMachineRunId): CordaFlowSnapshot<ReturnType>
}

/**
 * Container for a result of executing Corda flow, which may be either
 * an object or an exception, alongside an [Instant] when the result was captured.
 */
class CordaFlowResult<T: Any>(
    val timestamp: Instant,
    val value: T?,
    val error: Throwable?
) {
  companion object {
    fun <T: Any> forValue(value: T) = CordaFlowResult(timestamp = Instant.now(), value = value, error = null)
    fun <T: Any> forError(error: Throwable) = CordaFlowResult<T>(timestamp = Instant.now(), value = null, error = error)
  }
}

/**
 * Information bundle describing Corda flow that has been initiated
 * through the node API.
 */
data class CordaFlowHandle<ReturnType: Any>(
    val flowClass: KClass<out FlowLogic<ReturnType>>,
    val flowRunId: UUID,
    val startedAt: Instant,
    val flowResultPromise: Single<CordaFlowResult<ReturnType>>,

    /** There will be at least one initial update with empty progress information */
    val flowProgressUpdates: Observable<CordaFlowProgress>) {

  /**
   * Constructs an observable creating a new snapshot every time
   * there is an update in either flow progress tracker, or when flow completes/fails.
   *
   * Note that returned [Observable] will always have its first element available immediately
   * representing a snapshot with no result and no progress information.
   */
  fun observeSnapshots(): Observable<CordaFlowSnapshot<ReturnType>> {
    // construct versions of observable that issues initial state immediately,
    // so that combineLatest() has something to work with
    val prefixedFlowResult = Observable
        .just<CordaFlowResult<ReturnType>?>(null)
        .concatWith(flowResultPromise)

    val prefixedFlowProgress = Observable
        .just(CordaFlowProgress.noProgressInfo)
        .concatWith(flowProgressUpdates)

    return Observable.combineLatest(listOf(prefixedFlowResult, prefixedFlowProgress)) {
      @Suppress("UNCHECKED_CAST")
      val lastResult = it[0] as CordaFlowResult<ReturnType>?
      val lastProgress = it[1] as CordaFlowProgress

      CordaFlowSnapshot(flowClass, flowRunId, lastProgress, startedAt, lastResult)
    }
  }
}

/**
 * A snapshot of the progress tracker for a particular flow.
 * Progress may potentially have nested elements, in which case
 * there will be a number of items.
 */
data class CordaFlowProgress(
  val progress: List<CordaFlowProgressStep>
) {

  companion object {
    val noProgressInfo = CordaFlowProgress(emptyList())
  }
}

data class CordaFlowProgressStep(
    val stepIndex: Int,
    val stepName: String
)

/**
 * Description of the current state of a particular flow.
 */
data class CordaFlowSnapshot<ReturnType: Any>(
    val flowClass: KClass<out FlowLogic<ReturnType>>,
    val flowRunId: UUID,
    val currentProgress: CordaFlowProgress,
    val startedAt: Instant,

    /** Result of the flow if available */
  val result: CordaFlowResult<ReturnType>? = null
)

/**
 * All information necessary to query vault states or
 * subscribe for updates in the vault.
 */
data class CordaStateQuery<T : ContractState>(
    val contractStateType: Class<T>
)
