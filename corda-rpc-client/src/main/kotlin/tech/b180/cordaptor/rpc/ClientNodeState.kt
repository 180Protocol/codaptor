package tech.b180.cordaptor.rpc

import net.corda.core.contracts.ContractState
import net.corda.core.contracts.StateAndRef
import net.corda.core.contracts.StateRef
import net.corda.core.crypto.SecureHash
import net.corda.core.flows.FlowLogic
import net.corda.core.identity.CordaX500Name
import net.corda.core.identity.Party
import net.corda.core.messaging.CordaRPCOps
import net.corda.core.node.NodeInfo
import net.corda.core.node.services.Vault
import net.corda.core.node.services.diagnostics.NodeVersionInfo
import net.corda.core.node.services.vault.PageSpecification
import net.corda.core.node.services.vault.QueryCriteria
import net.corda.core.node.services.vault.Sort
import net.corda.core.transactions.SignedTransaction
import net.corda.serialization.internal.AllWhitelist
import net.corda.serialization.internal.amqp.CachingCustomSerializerRegistry
import net.corda.serialization.internal.amqp.DefaultDescriptorBasedSerializerRegistry
import net.corda.serialization.internal.amqp.WhitelistBasedTypeModelConfiguration
import net.corda.serialization.internal.model.ConfigurableLocalTypeModel
import net.corda.serialization.internal.model.LocalTypeInformation
import org.koin.core.get
import org.koin.core.inject
import org.slf4j.Logger
import tech.b180.cordaptor.corda.*
import tech.b180.cordaptor.kernel.CordaptorComponent
import tech.b180.cordaptor.kernel.loggerFor
import java.security.PublicKey

/**
 * Implementation of [CordaNodeState] interface providing access to a state
 * maintained within a particular node using Corda RPC API.
 */
class ClientNodeStateImpl : CordaNodeStateInner, CordaptorComponent, CordaNodeVault {

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

  override fun <T : ContractState> vaultQueryBy(
      criteria: QueryCriteria,
      paging: PageSpecification,
      sorting: Sort,
      contractStateType: Class<out T>
  ): Vault.Page<T> {
    return rpc.vaultQueryBy(criteria, paging, sorting, contractStateType)
  }

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

    return get<RPCFlowInitiator<ReturnType>>().initiateFlow(instruction)
  }
}

/**
 * Specific extension of the abstract flow initiation logic that uses Corda RPC
 * to initiate flow execution in the remote Corda node.
 */
class RPCFlowInitiator<ReturnType: Any> : FlowInitiator<ReturnType>(), CordaptorComponent {

  companion object {
    val logger = loggerFor<RPCFlowInitiator<*>>()
  }

  private val rpc: CordaRPCOps by inject()

  override val instanceLogger: Logger get() = logger

  private val localTypeModel by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
    val customSerializerRegistry = CachingCustomSerializerRegistry(DefaultDescriptorBasedSerializerRegistry())
    val typeModelConfiguration = WhitelistBasedTypeModelConfiguration(AllWhitelist, customSerializerRegistry)
    ConfigurableLocalTypeModel(typeModelConfiguration)
  }

  override fun doInitiateFlow(instruction: CordaFlowInstruction<FlowLogic<ReturnType>>): Handle<ReturnType> {
    val flowClass = instruction.flowClass.java
    logger.debug("Preparing to initiate flow {} over Corda RPC connection", flowClass)

    val typeInfo: LocalTypeInformation = localTypeModel.inspect(flowClass);

      val constructorParameters = when (typeInfo){
          is LocalTypeInformation.Composable ->  (typeInfo as? LocalTypeInformation.Composable)?.constructor?.parameters
          is LocalTypeInformation.NonComposable ->  (typeInfo as? LocalTypeInformation.NonComposable)?.constructor?.parameters
          else -> throw IllegalArgumentException("Flow $flowClass is introspected as either composable or non-composable:\n" +
                  typeInfo.prettyPrint(true))
      }

    val actualArgs = arrayOfNulls<Any?>(constructorParameters!!.size)
      constructorParameters.forEachIndexed { index, param ->
      val givenValue = instruction.arguments[param.name]
      if (givenValue == null && param.isMandatory) {
        throw IllegalArgumentException("No value provided for mandatory parameter ${param.name}")
      }
      actualArgs[index] = givenValue
      logger.debug("Actual value for argument {}: {}", index, givenValue)
    }

    return if (instruction.options?.trackProgress == true) {
      logger.debug("Initiating flow {} with progress updates", flowClass)
      val handle = rpc.startTrackedFlowDynamic(flowClass, *actualArgs)
      Handle(handle.id.uuid, handle.returnValue, handle.progress)
    } else {
      logger.debug("Initiating flow {} without progress updates", flowClass)
      val handle = rpc.startFlowDynamic(flowClass, *actualArgs)
      Handle(handle.id.uuid, handle.returnValue)
    }
  }
}