package tech.b180.cordaptor.rpc

import net.corda.core.contracts.ContractState
import net.corda.core.contracts.StateAndRef
import net.corda.core.contracts.StateRef
import net.corda.core.crypto.SecureHash
import net.corda.core.flows.FlowLogic
import net.corda.core.flows.StateMachineRunId
import net.corda.core.identity.CordaX500Name
import net.corda.core.identity.Party
import net.corda.core.messaging.CordaRPCOps
import net.corda.core.node.NodeInfo
import net.corda.core.node.services.Vault
import net.corda.core.node.services.diagnostics.NodeVersionInfo
import net.corda.core.node.services.vault.QueryCriteria
import net.corda.core.transactions.SignedTransaction
import org.koin.core.inject
import tech.b180.cordaptor.corda.*
import tech.b180.cordaptor.kernel.CordaptorComponent
import java.security.PublicKey
import kotlin.reflect.KClass

/**
 * Implementation of [CordaNodeState] interface providing access to a state
 * maintained within a particular node using Corda RPC API.
 */
class ClientNodeStateImpl : CordaNodeStateInner, CordaptorComponent {

  private val flowInitiator: FlowInitiator by inject()
  private val rpc: CordaRPCOps by inject()

  override val nodeInfo: NodeInfo
    get() = rpc.nodeInfo()

  override val nodeVersionInfo: NodeVersionInfo
    get() = rpc.nodeDiagnosticInfo().let {
      NodeVersionInfo(vendor = it.vendor, revision = it.revision,
          platformVersion = it.platformVersion, releaseVersion = it.version)
    }

  override fun wellKnownPartyFromX500Name(name: CordaX500Name): Party? =
      rpc.wellKnownPartyFromX500Name(name)

  override fun partyFromKey(publicKey: PublicKey): Party? =
      rpc.partyFromKey(publicKey)

  @Suppress("DEPRECATION")
  override fun findTransactionByHash(hash: SecureHash): SignedTransaction? =
      rpc.internalFindVerifiedTransaction(hash)

  override fun <T : ContractState> findStateByRef(stateRef: StateRef, clazz: Class<T>,
                                                  vaultStateStatus: Vault.StateStatus): StateAndRef<T>? =
    rpc.vaultQueryByCriteria(
        contractStateType = clazz,
        criteria = QueryCriteria.VaultQueryCriteria(
            status = vaultStateStatus,
            stateRefs = listOf(stateRef)
        )
    ).states.singleOrNull()

  override fun <T : ContractState> queryStates(query: CordaVaultQuery<T>): CordaVaultPage<T> {
    val page = rpc.vaultQueryBy(
        query.toCordaQueryCriteria(this),
        query.toCordaPageSpecification(),
        query.toCordaSort(),
        query.contractStateClass.java)

    return page.toCordaptorPage()
  }

  override fun <T : ContractState> countStates(query: CordaVaultQuery<T>): Int {
    TODO("Not yet implemented")
  }

  override fun <T : ContractState> trackStates(query: CordaVaultQuery<T>): CordaDataFeed<T> {
    val updates = rpc.vaultTrackBy(
        query.toCordaQueryCriteria(this),
        query.toCordaPageSpecification(),
        query.toCordaSort(),
        query.contractStateClass.java)

    return updates.toCordaptorFeed()
  }

  override fun <ReturnType : Any> initiateFlow(
      instruction: CordaFlowInstruction<FlowLogic<ReturnType>>
  ): CordaFlowHandle<ReturnType> {

    return flowInitiator.initiateFlow(instruction)
  }

  override fun <ReturnType : Any> trackRunningFlow(flowClass: KClass<out FlowLogic<ReturnType>>, runId: StateMachineRunId): CordaFlowHandle<ReturnType> {
    TODO("Not yet implemented")
  }

}
