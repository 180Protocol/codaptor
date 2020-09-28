package tech.b180.cordaptor.cache

import net.corda.core.contracts.*
import net.corda.core.crypto.SecureHash
import net.corda.core.flows.StateMachineRunId
import net.corda.core.node.NodeInfo
import net.corda.core.transactions.SignedTransaction
import org.koin.core.KoinComponent
import tech.b180.cordaptor.corda.CordaFlowInstruction
import tech.b180.cordaptor.corda.CordaFlowStatus
import tech.b180.cordaptor.corda.CordaNodeState
import tech.b180.cordaptor.corda.CordaStateQuery
import tech.b180.cordaptor.kernel.LifecycleAware

class CachedNodeState : CordaNodeState, KoinComponent, LifecycleAware {
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

  override fun initiateFlow(instruction: CordaFlowInstruction): io.reactivex.rxjava3.core.Observable<CordaFlowStatus> {
    TODO("Not yet implemented")
  }

  override fun getFlowStatus(flowRunId: StateMachineRunId): CordaFlowStatus {
    TODO("Not yet implemented")
  }

  override fun initialize() {
    TODO("Not yet implemented")
  }

  override fun shutdown() {
    TODO("Not yet implemented")
  }

}