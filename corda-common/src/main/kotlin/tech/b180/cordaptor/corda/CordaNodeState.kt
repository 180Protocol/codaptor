package tech.b180.cordaptor.corda

import io.reactivex.rxjava3.core.Observable
import io.reactivex.rxjava3.core.Single
import net.corda.core.contracts.ContractState
import net.corda.core.contracts.StateAndRef
import net.corda.core.contracts.StateRef
import net.corda.core.crypto.SecureHash
import net.corda.core.flows.FlowLogic
import net.corda.core.flows.StateMachineRunId
import net.corda.core.identity.CordaX500Name
import net.corda.core.identity.Party
import net.corda.core.node.NodeInfo
import net.corda.core.node.services.Vault
import net.corda.core.node.services.diagnostics.NodeVersionInfo
import net.corda.core.transactions.SignedTransaction
import java.security.PublicKey
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

  val nodeVersionInfo: NodeVersionInfo

  /**
   * @see net.corda.core.node.services.IdentityService.wellKnownPartyFromX500Name
   * @see net.corda.core.messaging.CordaRPCOps.wellKnownPartyFromX500Name
   */
  fun wellKnownPartyFromX500Name(name: CordaX500Name): Party?

  /**
   * @see net.corda.core.node.services.IdentityService.partyFromKey
   * @see net.corda.core.messaging.CordaRPCOps.partyFromKey
   */
  fun partyFromKey(publicKey: PublicKey): Party?

  /**
   * FIXME Corda RPC implementation uses a deprecated API method, may be removed in future
   *
   * @see net.corda.core.node.services.TransactionStorage.getTransaction
   * @see net.corda.core.messaging.CordaRPCOps.internalFindVerifiedTransaction
   */
  fun findTransactionByHash(hash: SecureHash): SignedTransaction?

  /**
   * Returns [StateAndRef] for a particular [ContractState] identified by a reference.
   */
  fun <T : ContractState> findStateByRef(stateRef: StateRef, clazz: Class<T>, vaultStateStatus: Vault.StateStatus): StateAndRef<T>?

  fun <T : ContractState> countStates(query: CordaStateQuery<T>): Int

  fun <T : ContractState> trackStates(query: CordaStateQuery<T>): Observable<T>

  fun <ReturnType: Any> initiateFlow(flowInstance: FlowLogic<ReturnType>): CordaFlowHandle<ReturnType>

  fun <ReturnType: Any> initiateFlow(instruction: CordaFlowInstruction<FlowLogic<ReturnType>>): CordaFlowHandle<ReturnType>

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
      flowClass: KClass<FlowLogic<ReturnType>>, flowRunId: StateMachineRunId): CordaFlowSnapshot<ReturnType>
}

/**
 * Encapsulates the instruction to Corda node to initiate a flow.
 * This class is expected to be used with the type parameter in order to
 * generate typesafe serialization bindings.
 */
data class CordaFlowInstruction<FlowClass: FlowLogic<Any>>(
    val flowClass: KClass<FlowClass>,
    val arguments: Map<String, Any>,
    val options: Options? = null
) {

  /** Options that change the behaviour of the flow initiation logic */
  data class Options(
      /** by default false */
      val trackProgress: Boolean?
  )
}

/**
 * Container for a result of executing Corda flow, which may be either
 * an object or an exception, alongside an [Instant] when the result was captured.
 */
data class CordaFlowResult<T: Any>(
    val timestamp: Instant,
    val value: T?,
    val error: Throwable?
) {
  init {
    require(value != null || error != null) { "Either value or error must be provided" }
    require(value == null || error == null) { "Cannot have both value and error" }
  }

  val isError: Boolean
    get() = error != null

  companion object {
    fun <T: Any> forValue(value: T) = CordaFlowResult<T>(timestamp = Instant.now(), value = value, error = null)
    fun <T: Any> forError(error: Throwable) = CordaFlowResult<T>(timestamp = Instant.now(), value = null, error = error)
  }
}

/**
 * Information bundle describing Corda flow that has been initiated
 * through the node API.
 */
data class CordaFlowHandle<ReturnType: Any>(
    val flowClass: KClass<out FlowLogic<ReturnType>>,

    /** Unique identifier of the flow instance within the Corda node's state machine */
    val flowRunId: UUID,

    /**
     * Timestamp of the moment when the flow was submitted for execution.
     * This time is local to JVM running Cordaptor, which may or may not
     * be the same JVM running Corda depending on the deployment.
     */
    val startedAt: Instant,

    /**
     * A single (promise) for a flow completion result or an error.
     * Client code that is no longer interested in the result of the flow
     * should dispose the single to avoid wasting server resources.
     *
     * When running as a service, returned single will resolve on the flow completion thread,
     * so any lengthy operation will hold up node's state machine.
     */
    val flowResultPromise: Single<CordaFlowResult<ReturnType>>,

    /**
     * A feed of flow progress updates.
     * This observable will complete when [flowResultPromise] is complete.
     * Client code that is no longer interested in the result of the flow
     * should dispose the single to avoid wasting server resources.
     */
    val flowProgressUpdates: Observable<CordaFlowProgress>) {

  /** Returns an 'initial' snapshot with no progress information and not result */
  fun asInitialSnapshot() = CordaFlowSnapshot(flowClass = flowClass,
      flowRunId = flowRunId, currentProgress = null,
      startedAt = startedAt)

  fun asSnapshotWithResult(result: CordaFlowResult<ReturnType>) =
      asInitialSnapshot().withResult(result)

  fun asSnapshotWithProgress(currentProgress: CordaFlowProgress) =
      asInitialSnapshot().withProgress(currentProgress)
}

/**
 * Description of the current state of a particular flow.
 */
data class CordaFlowSnapshot<ReturnType: Any>(
    val flowClass: KClass<out FlowLogic<ReturnType>>,
    val flowRunId: UUID,

    /**
     * A snapshot of the progress tracker for a particular flow.
     * Progress may potentially have nested elements, in which case
     * there will be a number of items.
     */
    val currentProgress: CordaFlowProgress?,
    val startedAt: Instant,

    /** Result of the flow if available */
  val result: CordaFlowResult<ReturnType>? = null
) {

  fun withResult(result: CordaFlowResult<ReturnType>) = copy(result = result)
  fun withProgress(currentProgress: CordaFlowProgress) = copy(currentProgress = currentProgress)
}

data class CordaFlowProgress(
    val currentStepName: String,
    val timestamp: Instant = Instant.now())

/**
 * All information necessary to query vault states or
 * subscribe for updates in the vault.
 */
data class CordaStateQuery<T : ContractState>(
    val contractStateType: Class<T>
)
