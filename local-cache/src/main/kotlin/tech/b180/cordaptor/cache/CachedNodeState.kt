package tech.b180.cordaptor.cache

import net.corda.core.contracts.*
import net.corda.core.crypto.SecureHash
import net.corda.core.flows.FlowLogic
import net.corda.core.flows.StateMachineRunId
import net.corda.core.node.NodeInfo
import net.corda.core.node.services.diagnostics.NodeVersionInfo
import net.corda.core.transactions.SignedTransaction
import tech.b180.cordaptor.corda.*
import tech.b180.cordaptor.kernel.CordaptorComponent
import tech.b180.cordaptor.kernel.LifecycleAware
import kotlin.reflect.KClass

class CachedNodeState(private val delegate: CordaNodeStateInner) : CordaNodeState, CordaptorComponent, LifecycleAware {

  override val nodeInfo: NodeInfo
    get() = delegate.nodeInfo

  override val nodeVersionInfo: NodeVersionInfo
    get() = delegate.nodeVersionInfo

  override fun <T : ContractState> findStateByRef(stateRef: StateRef, clazz: Class<T>): StateAndRef<T>? {
    TODO("Not yet implemented")
  }

  override fun findTransactionByHash(hash: SecureHash): SignedTransaction? {
    TODO("Not yet implemented")
  }

  override fun <T : ContractState> countStates(query: CordaStateQuery<T>): Int {
    TODO("Not yet implemented")
  }

  override fun <T : ContractState> trackStates(query: CordaStateQuery<T>): io.reactivex.rxjava3.core.Observable<T> {
    TODO("Not yet implemented")
  }

  override fun <ReturnType : Any> initiateFlow(flowInstance: FlowLogic<ReturnType>): CordaFlowHandle<ReturnType> {
    TODO("Not yet implemented")
  }

  override fun <ReturnType : Any> trackRunningFlow(flowClass: KClass<out FlowLogic<ReturnType>>, runId: StateMachineRunId): CordaFlowHandle<ReturnType> {
    TODO("Not yet implemented")
  }

  override fun initialize() {
    TODO("Not yet implemented")
  }

  override fun shutdown() {
    TODO("Not yet implemented")
  }

}