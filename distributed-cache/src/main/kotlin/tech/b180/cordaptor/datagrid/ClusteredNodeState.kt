package tech.b180.cordaptor.datagrid

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
import tech.b180.cordaptor.corda.*
import tech.b180.cordaptor.kernel.CordaptorComponent
import tech.b180.cordaptor.kernel.LifecycleAware
import java.security.PublicKey
import kotlin.reflect.KClass

class ClusteredNodeState(private val delegate: CordaNodeStateInner)
  : CordaNodeState, CordaptorComponent, LifecycleAware {

  override val nodeInfo: NodeInfo
    get() = TODO("Not yet implemented")

  override val nodeVersionInfo: NodeVersionInfo
    get() = TODO("Not yet implemented")

  override fun wellKnownPartyFromX500Name(name: CordaX500Name): Party? {
    TODO("Not yet implemented")
  }

  override fun partyFromKey(publicKey: PublicKey): Party? {
    TODO("Not yet implemented")
  }

  override fun <T : ContractState> findStateByRef(stateRef: StateRef, clazz: Class<T>,
                                                  vaultStateStatus: Vault.StateStatus): StateAndRef<T>? {
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

  override fun <ReturnType : Any> initiateFlow(instruction: CordaFlowInstruction<FlowLogic<ReturnType>>): CordaFlowHandle<ReturnType> {
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