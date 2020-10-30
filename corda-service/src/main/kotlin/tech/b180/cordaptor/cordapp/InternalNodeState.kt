package tech.b180.cordaptor.cordapp

import hu.akarnokd.rxjava3.interop.RxJavaInterop
import io.reactivex.rxjava3.subjects.SingleSubject
import net.corda.core.contracts.ContractState
import net.corda.core.contracts.StateAndRef
import net.corda.core.contracts.StateRef
import net.corda.core.contracts.TransactionResolutionException
import net.corda.core.crypto.SecureHash
import net.corda.core.flows.FlowLogic
import net.corda.core.flows.StateMachineRunId
import net.corda.core.identity.CordaX500Name
import net.corda.core.identity.Party
import net.corda.core.node.AppServiceHub
import net.corda.core.node.NodeInfo
import net.corda.core.node.services.TransactionStorage
import net.corda.core.node.services.Vault
import net.corda.core.node.services.VaultService
import net.corda.core.node.services.diagnostics.NodeVersionInfo
import net.corda.core.transactions.SignedTransaction
import net.corda.core.utilities.getOrThrow
import net.corda.serialization.internal.model.LocalTypeModel
import org.koin.core.inject
import tech.b180.cordaptor.corda.*
import tech.b180.cordaptor.kernel.CordaptorComponent
import tech.b180.cordaptor.kernel.loggerFor
import java.security.PublicKey
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/**
 * Implementation of [CordaNodeState] interface providing access to a state
 * maintained within a particular Corda node using APIs available internally within the node.
 */
class CordaNodeStateImpl : CordaNodeStateInner, CordaptorComponent {

  private val appServiceHub: AppServiceHub by inject()
  private val vaultService: VaultService by inject()
  private val transactionStorage: TransactionStorage by inject()
  private val flowDispatcher: CordaFlowDispatcher by inject()

  override val nodeInfo: NodeInfo
    get() = appServiceHub.myInfo

  override val nodeVersionInfo: NodeVersionInfo
    get() = appServiceHub.diagnosticsService.nodeVersionInfo()

  override fun wellKnownPartyFromX500Name(name: CordaX500Name): Party? {
    return appServiceHub.identityService.wellKnownPartyFromX500Name(name)
  }

  override fun partyFromKey(publicKey: PublicKey): Party? {
    return appServiceHub.identityService.partyFromKey(publicKey)
  }

  override fun <T : ContractState> findStateByRef(stateRef: StateRef, clazz: Class<T>, vaultStateStatus: Vault.StateStatus): StateAndRef<T>? {
    return try {
      appServiceHub.toStateAndRef(stateRef)
    } catch (e: TransactionResolutionException) {
      null
    }
  }

  override fun findTransactionByHash(hash: SecureHash): SignedTransaction? {
    return transactionStorage.getTransaction(hash)
  }

  override fun <T : ContractState> queryStates(query: CordaVaultQuery<T>): CordaVaultPage<T> {
    val page = vaultService.queryBy(query.contractStateClass.java,
        query.toCordaQueryCriteria(this),
        query.toCordaPageSpecification(), query.toCordaSort())

    return page.toCordaptorPage()
  }

  override fun <T : ContractState> countStates(query: CordaVaultQuery<T>): Int {
    TODO("Not yet implemented")
  }

  override fun <T : ContractState> trackStates(query: CordaVaultQuery<T>): CordaDataFeed<T> {
    val feed = vaultService.trackBy(query.contractStateClass.java,
        query.toCordaQueryCriteria(this),
        query.toCordaPageSpecification(), query.toCordaSort())

    return feed.toCordaptorFeed()
  }

  @Suppress("UNCHECKED_CAST")
  override fun <ReturnType: Any> initiateFlow(
      instruction: CordaFlowInstruction<FlowLogic<ReturnType>>
  ): CordaFlowHandle<ReturnType> {

    return flowDispatcher.initiateFlow(instruction)
  }
}

/**
 * Wrapper for Corda flow initiation API that keeps track of actively running
 * flows and allowing to look them up by run id.
 */
class CordaFlowDispatcher : CordaptorComponent {
  companion object {
    val logger = loggerFor<CordaFlowDispatcher>()
  }

  private val appServiceHub: AppServiceHub by inject()
  private val localTypeModel: LocalTypeModel by inject()

  private val activeHandles = ConcurrentHashMap<StateMachineRunId, CordaFlowHandle<*>>()

  fun <ReturnType: Any> initiateFlow(instruction: CordaFlowInstruction<FlowLogic<ReturnType>>): CordaFlowHandle<ReturnType> {
    val flowInstance = FlowInstanceBuilder(
        instruction.flowClass, instruction.arguments, localTypeModel).instantiate()

    val cordaHandle = appServiceHub.startTrackedFlow(flowInstance)
    logger.debug("Flow {} started with run id {}", flowInstance.javaClass.canonicalName, cordaHandle.id)

    // asynchronously emit the result when flow result future completes
    // this will happen on the node's state machine thread
    val resultSubject = SingleSubject.create<CordaFlowResult<ReturnType>>()
    cordaHandle.returnValue.then {
      try {
        val flowResult = it.getOrThrow()
        logger.debug("Flow {} returned {}", cordaHandle.id, flowResult)
        resultSubject.onSuccess(CordaFlowResult.forValue(flowResult))
      } catch (e: Throwable) {
        logger.debug("Flow {} threw an error:", cordaHandle.id, e)
        resultSubject.onSuccess(CordaFlowResult.forError(e))
      }
    }

    val progressUpdates = if (
        instruction.options?.trackProgress == true
        && flowInstance.progressTracker != null
    ) {
      val flowProgressFeed = flowInstance.track()
          ?: throw IllegalStateException("Flow has a progress tracked, but calling track() returned null progress feed")

      RxJavaInterop.toV3Observable(flowProgressFeed.updates).map {
        logger.debug("Progress update for flow {}: {}", cordaHandle.id, it)
        CordaFlowProgress(it)
      }.doOnDispose {
        logger.debug("Progress observable for flow {} was disposed", cordaHandle.id)
      }.doOnComplete {
        logger.debug("Progress observable for flow {} completed", cordaHandle.id)
      }
    } else {
      if (flowInstance.progressTracker == null) {
        logger.info("Flow {} does not use progress tracker, no progress feed will be available",
            flowInstance.javaClass.canonicalName)
      } else {
        logger.debug("Progress tracking was not requested for flow {}", cordaHandle.id)
      }
      null
    }

    val ourHandle = CordaFlowHandle(
        startedAt = Instant.now(),
        flowClass = flowInstance::class,
        flowRunId = cordaHandle.id.uuid,
        flowResultPromise = resultSubject,
        flowProgressUpdates = progressUpdates
    )

    ourHandle.flowResultPromise
        .subscribe { _, _ -> activeHandles.remove(cordaHandle.id) }

    activeHandles[cordaHandle.id] = ourHandle

    return ourHandle
  }

  fun findFlowHandle(runId: StateMachineRunId): CordaFlowHandle<Any>? {
    @Suppress("UNCHECKED_CAST")
    return activeHandles[runId] as CordaFlowHandle<Any>?
  }
}
