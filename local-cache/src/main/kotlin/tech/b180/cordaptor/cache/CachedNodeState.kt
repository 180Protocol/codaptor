package tech.b180.cordaptor.cache

import net.corda.core.contracts.*
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

class CachedNodeState(private val delegate: CordaNodeStateInner) : CordaNodeState, CordaptorComponent, LifecycleAware {

  override val nodeInfo: NodeInfo
    get() = delegate.nodeInfo

  override val nodeVersionInfo: NodeVersionInfo
    get() = delegate.nodeVersionInfo

  override fun wellKnownPartyFromX500Name(name: CordaX500Name): Party? {
    TODO("Not yet implemented")
  }

  override fun partyFromKey(publicKey: PublicKey): Party? {
    TODO("Not yet implemented")
  }

  override fun <T : ContractState> findStateByRef(stateRef: StateRef, clazz: Class<T>, vaultStateStatus: Vault.StateStatus): StateAndRef<T>? {
    TODO("Not yet implemented")
  }

  override fun findTransactionByHash(hash: SecureHash): SignedTransaction? {
    TODO("Not yet implemented")
  }

  override fun <T : ContractState> queryStates(query: CordaVaultQuery<T>): CordaVaultPage<T> {
    TODO("Not yet implemented")
  }

  override fun <T : ContractState> countStates(query: CordaVaultQuery<T>): Int {
    TODO("Not yet implemented")
  }

  override fun <T : ContractState> trackStates(query: CordaVaultQuery<T>): CordaDataFeed<T> {
    TODO("Not yet implemented")
  }

  override fun <ReturnType : Any> initiateFlow(instruction: CordaFlowInstruction<FlowLogic<ReturnType>>): CordaFlowHandle<ReturnType> {
    TODO("Not yet implemented")
  }

  override fun onInitialize() {
    TODO("Not yet implemented")
  }

  override fun onShutdown() {
    TODO("Not yet implemented")
  }

}