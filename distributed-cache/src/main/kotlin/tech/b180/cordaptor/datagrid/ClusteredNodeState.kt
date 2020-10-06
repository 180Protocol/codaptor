package tech.b180.cordaptor.datagrid

import net.corda.core.contracts.*
import net.corda.core.crypto.SecureHash
import net.corda.core.flows.StateMachineRunId
import net.corda.core.node.NodeInfo
import net.corda.core.transactions.SignedTransaction
import tech.b180.cordaptor.corda.CordaFlowHandle
import tech.b180.cordaptor.corda.CordaFlowInstruction
import tech.b180.cordaptor.corda.CordaNodeState
import tech.b180.cordaptor.corda.CordaStateQuery
import tech.b180.cordaptor.kernel.CordaptorComponent
import tech.b180.cordaptor.kernel.LifecycleAware

class ClusteredNodeState : CordaNodeState, CordaptorComponent, LifecycleAware {
  override val nodeInfo: NodeInfo
    get() = TODO("Not yet implemented")

  override fun <T : ContractState> findStateByRef(stateRef: StateRef, clazz: Class<T>): StateAndRef<T>? {
    TODO("Not yet implemented")
  }

  override fun findTransactionByHash(hash: SecureHash): SignedTransaction? {
    TODO("Not yet implemented")
  }

  override fun <T : ContractState> countStates(query: CordaStateQuery<T>): Int {
    TODO("Not yet implemented")
  }

  override fun <T : Any> aggregateFungibleState(query: CordaStateQuery<FungibleState<T>>, clazz: Class<T>): Amount<T> {
    TODO("Not yet implemented")
  }

  override fun <T : ContractState> trackStates(query: CordaStateQuery<T>): io.reactivex.rxjava3.core.Observable<T> {
    TODO("Not yet implemented")
  }

  override fun initiateFlow(instruction: CordaFlowInstruction): CordaFlowHandle {
    TODO("Not yet implemented")
  }

  override fun trackRunningFlow(runId: StateMachineRunId): CordaFlowHandle {
    TODO("Not yet implemented")
  }

  override fun initialize() {
    TODO("Not yet implemented")
  }

  override fun shutdown() {
    TODO("Not yet implemented")
  }

}