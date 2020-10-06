package tech.b180.cordaptor.corda

import io.reactivex.rxjava3.core.Observable
import io.reactivex.rxjava3.core.Single
import net.corda.core.contracts.*
import net.corda.core.crypto.SecureHash
import net.corda.core.flows.StateMachineRunId
import net.corda.core.node.NodeInfo
import net.corda.core.transactions.SignedTransaction
import java.time.Instant

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

  fun initiateFlow(instruction: CordaFlowInstruction): CordaFlowHandle

  /**
   * Allows to observe the execution of a flow that was started earlier.
   *
   * Throws [NoSuchElementException] if the flow does not exist or is no longer running.
   * There is no way of telling these conditions apart in Corda API.
   *
   * See [CordaFlowCache] for ways of accessing details about completed/failed flows.
   */
  fun trackRunningFlow(runId: StateMachineRunId): CordaFlowHandle
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

  fun getFlowInstance(flowRunId: StateMachineRunId): CordaFlowSnapshot?
}

/**
 * Information bundle describing Corda flow that has been initiated
 * through the node API.
 */
data class CordaFlowHandle(
  val flowClassName: String,
  val flowRunId: StateMachineRunId,
  val flowResultPromise: Single<Any>,
  val flowProgressUpdates: Observable<CordaFlowProgress>)

/**
 * A snapshot of the progress tracker for a particular flow.
 * Progress may potentially have nested elements, in which case
 * there will be a number of items.
 */
data class CordaFlowProgress(
  val progress: List<Pair<Int, String>>
)

/**
 * Description of the current state of a particular flow.
 */
data class CordaFlowSnapshot(
  val flowClassName: String,
  val currentProgress: CordaFlowProgress,
  val startedAt: Instant,
  val completed: Boolean,

  /** Present if [completed] is true */
  val completedAt: Instant? = null,

  /** Present if [completed] is true and no error occured */
  val result: Any? = null,

  /** Present if [completed] is true and an error occured */
  val error: Throwable? = null
)

/**
 * All information necessary to initiate a Corda flow.
 * regardless of the access method.
 */
data class CordaFlowInstruction(
    val flowClassName: String,
    val flowConstructorParameters: List<Any>
)

/**
 * All information necessary to query vault states or
 * subscribe for updates in the vault.
 */
data class CordaStateQuery<T : ContractState>(
    val contractStateType: Class<T>
)
