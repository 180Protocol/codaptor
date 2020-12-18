package tech.b180.cordaptor.corda

import io.reactivex.rxjava3.core.Observable
import io.reactivex.rxjava3.core.Single
import net.corda.core.contracts.ContractState
import net.corda.core.contracts.StateAndRef
import net.corda.core.contracts.StateRef
import net.corda.core.crypto.SecureHash
import net.corda.core.flows.FlowLogic
import net.corda.core.identity.CordaX500Name
import net.corda.core.identity.Party
import net.corda.core.node.NodeInfo
import net.corda.core.node.services.Vault
import net.corda.core.node.services.diagnostics.NodeVersionInfo
import net.corda.core.node.services.vault.PageSpecification
import net.corda.core.node.services.vault.QueryCriteria
import net.corda.core.node.services.vault.Sort
import net.corda.core.transactions.SignedTransaction
import tech.b180.cordaptor.kernel.ModuleAPI
import java.security.PublicKey
import java.time.Instant
import java.util.*
import kotlin.reflect.KClass

/**
 * Common functions used to establish identity of a party.
 */
@ModuleAPI(since = "0.1")
interface PartyLocator {

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
}

/**
 * This interface describes node vault operations that use Corda API that is consistently
 * supported across all deployments. These methods are intended to
 * be used by application-specific Cordaptor extensions.
 */
@ModuleAPI(since = "0.2")
interface CordaNodeVault {

  /**
   * @see net.corda.core.messaging.CordaRPCOps.vaultQueryBy
   * @see net.corda.core.node.services.VaultService._queryBy
   */
  fun <T : ContractState> vaultQueryBy(criteria: QueryCriteria,
                                       paging: PageSpecification,
                                       sorting: Sort,
                                       contractStateType: Class<out T>): Vault.Page<T>
}

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
@ModuleAPI(since = "0.1")
interface CordaNodeState : PartyLocator {

  val nodeInfo: NodeInfo

  val nodeVersionInfo: NodeVersionInfo


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

  fun <T : ContractState> queryStates(query: CordaVaultQuery<T>): CordaVaultPage<T>

  fun <T : ContractState> countStates(query: CordaVaultQuery<T>): Int

  fun <T : ContractState> trackStates(query: CordaVaultQuery<T>): CordaDataFeed<T>

  fun <ReturnType: Any> initiateFlow(instruction: CordaFlowInstruction<FlowLogic<ReturnType>>): CordaFlowHandle<ReturnType>
}

/**
 * Marker interface allowing decorating implementation of [CordaNodeState] to locate
 * the underlying implementation.
 */
@ModuleAPI(since = "0.1")
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
@ModuleAPI(since = "0.1")
interface CordaFlowSnapshotsCache {

  /**
   * Allows to retrieve most recent snapshot of flow that was initiated earlier, or indication that such snapshot
   * is no longer available in the cache because of the eviction.
   *
   * Note that there is a low probability of a false positive owing to the fact that a memory-efficient
   * probabilistic data structure like a bloom filter is used.
   *
   * @return most recent snapshot, or null if flow information is no longer available because it was evicted
   * from the cache, or cache is not configured to retain snapshots of completed flows.
   *
   * @throws NoSuchElementException when there was no record of such run id. Note that there is a possibility
   * of a false negative if underlying implementation uses in-memory data structures and the JVM was restarted.
   * Only clustered implementation is guaranteed to never return a false negative.
   */
  fun <ReturnType: Any> getFlowSnapshot(
      flowClass: KClass<out FlowLogic<ReturnType>>, flowRunId: UUID): CordaFlowSnapshot<ReturnType>?
}

/**
 * Encapsulates the instruction to Corda node to initiate a flow.
 * This class is expected to be used with the type parameter in order to
 * generate typesafe serialization bindings.
 */
@ModuleAPI(since = "0.1")
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
@ModuleAPI(since = "0.1")
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
@ModuleAPI(since = "0.1")
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
     * A feed of flow progress updates if progress tracking is available for the flow
     * and it was requested via [CordaFlowInstruction.Options.trackProgress].
     *
     * Returned observable will complete when [flowResultPromise] is complete.
     *
     * Client code that is no longer interested in the result of the flow
     * should dispose the single to avoid wasting server resources.
     */
    val flowProgressUpdates: Observable<CordaFlowProgress>?) {

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
@ModuleAPI(since = "0.1")
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

@ModuleAPI(since = "0.1")
data class CordaFlowProgress(
    val currentStepName: String,
    val timestamp: Instant = Instant.now())

/**
 * Modelled after Corda RPC DataFeed construct to allow both a result of a query
 * and updates going forward to be returned from an API call.
 *
 * Actual DataFeed class is not used because of the old version of rxjava Observable.
 */
@ModuleAPI(since = "0.1")
data class CordaDataFeed<T: ContractState>(
    val snapshot: CordaVaultPage<T>,
    val feed: Observable<Vault.Update<T>>
)
