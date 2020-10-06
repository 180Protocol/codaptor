package tech.b180.cordaptor.cordapp

import hu.akarnokd.rxjava3.interop.RxJavaInterop
import io.reactivex.rxjava3.core.Single
import net.corda.core.contracts.*
import net.corda.core.crypto.SecureHash
import net.corda.core.flows.FlowLogic
import net.corda.core.flows.StateMachineRunId
import net.corda.core.node.AppServiceHub
import net.corda.core.node.NodeInfo
import net.corda.core.node.services.TransactionStorage
import net.corda.core.transactions.SignedTransaction
import net.corda.serialization.internal.model.LocalTypeInformation
import net.corda.serialization.internal.model.LocalTypeModel
import org.koin.core.KoinComponent
import org.koin.core.inject
import tech.b180.cordaptor.corda.*
import java.lang.reflect.Constructor
import java.util.concurrent.ConcurrentHashMap

/**
 * Implementation of [CordaNodeState] interface providing access to a state
 * maintained within a particular Corda node using APIs available internally within the node.
 */
class CordaNodeStateImpl : CordaNodeStateInner, KoinComponent {

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

  override fun initiateFlow(instruction: CordaFlowInstruction): CordaFlowHandle {
    return flowDispatcher.initiateFlow(instruction)
  }

  override fun trackRunningFlow(runId: StateMachineRunId): CordaFlowHandle {
    return flowDispatcher.findFlowHandle(runId)
        ?: throw NoSuchElementException("Unknown run id $runId -- flow may have already completed")
  }
}

/**
 * Wrapper for Corda flow initiation API that keeps track of actively running
 * flows and allowing to look them up by run id.
 */
class CordaFlowDispatcher : KoinComponent {
  private val appServiceHub: AppServiceHub by inject()
  private val localTypeModel: LocalTypeModel by inject()

  private val activeHandles = ConcurrentHashMap<StateMachineRunId, CordaFlowHandle>()

  fun initiateFlow(instruction: CordaFlowInstruction): CordaFlowHandle {
    val cordaHandle = appServiceHub.startTrackedFlow(instantiateFlow(instruction))

    val ourHandle = CordaFlowHandle(
        flowClassName = instruction.flowClassName,
        flowRunId = cordaHandle.id,
        flowResultPromise = Single.fromFuture(cordaHandle.returnValue),
        flowProgressUpdates = RxJavaInterop.toV3Observable(cordaHandle.stepsTreeFeed!!.updates).map {
          CordaFlowProgress(it)
        }
    )

    ourHandle.flowResultPromise.onErrorReturnItem(null).subscribe { _, _ -> activeHandles.remove(cordaHandle.id) }

    activeHandles[cordaHandle.id] = ourHandle

    return ourHandle
  }

  fun findFlowHandle(runId: StateMachineRunId): CordaFlowHandle? {
    return activeHandles[runId]
  }

  /** Instantiates the flow class using given parameters */
  private fun instantiateFlow(instruction: CordaFlowInstruction): FlowLogic<Any> {
    val flowType = localTypeModel.inspect(Class.forName(instruction.flowClassName))
    if (flowType !is LocalTypeInformation.NonComposable) {
      throw AssertionError("Unexpected type for the flow class ${instruction.flowClassName}: $flowType")
    }
    val flowConstructor = (flowType as LocalTypeInformation.NonComposable).constructor
        ?: throw AssertionError("No constructor found in $flowType")

    val javaConstructor = flowConstructor.observedMethod as Constructor<FlowLogic<Any>>

    // we are not validating compatibility of the parameters passed in the instruction
    // as they should have been reconstructed using flow type information at the endpoint
    // but any type mismatches occurring at this point would yield a reflection error
    return javaConstructor.newInstance(*instruction.flowConstructorParameters.toTypedArray())
  }
}
