package tech.b180.cordaptor.cordapp

import net.corda.core.contracts.ContractState
import net.corda.core.contracts.StateAndRef
import net.corda.core.contracts.StateRef
import net.corda.core.contracts.TransactionResolutionException
import net.corda.core.crypto.SecureHash
import net.corda.core.flows.FlowLogic
import net.corda.core.identity.CordaX500Name
import net.corda.core.identity.Party
import net.corda.core.node.AppServiceHub
import net.corda.core.node.NodeInfo
import net.corda.core.node.services.TransactionStorage
import net.corda.core.node.services.Vault
import net.corda.core.node.services.VaultService
import net.corda.core.node.services.diagnostics.NodeVersionInfo
import net.corda.core.node.services.vault.PageSpecification
import net.corda.core.node.services.vault.QueryCriteria
import net.corda.core.node.services.vault.Sort
import net.corda.core.transactions.SignedTransaction
import net.corda.serialization.internal.model.LocalTypeModel
import org.koin.core.get
import org.koin.core.inject
import org.slf4j.Logger
import tech.b180.cordaptor.corda.*
import tech.b180.cordaptor.kernel.CordaptorComponent
import tech.b180.cordaptor.kernel.loggerFor
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.Paths
import java.security.PublicKey
import java.util.*
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Implementation of [CordaNodeState] interface providing access to a state
 * maintained within a particular Corda node using APIs available internally within the node.
 */
class CordaNodeStateImpl : CordaNodeStateInner, CordaptorComponent, CordaNodeVault {

  private val appServiceHub: AppServiceHub by inject()
  private val vaultService: VaultService by inject()
  private val transactionStorage: TransactionStorage by inject()

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

  override fun <T : ContractState> vaultQueryBy(
      criteria: QueryCriteria,
      paging: PageSpecification,
      sorting: Sort,
      contractStateType: Class<out T>
  ): Vault.Page<T> {
    return vaultService._queryBy(criteria, paging, sorting, contractStateType)
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

    return get<LocalFlowInitiator<ReturnType>>().initiateFlow(instruction)
  }

  override fun createAttachment(attachment: CordaNodeAttachment): SecureHash {
    val zipName = "${attachment.filename}-${UUID.randomUUID()}.zip"
    FileOutputStream(zipName).use { fileOutputStream ->
      ZipOutputStream(fileOutputStream).use { zipOutputStream ->
        val zipEntry = ZipEntry(attachment.filename)
        zipOutputStream.putNextEntry(zipEntry)
        attachment.inputStream.copyTo(zipOutputStream, 1024)
      }
    }
    val inputStream = FileInputStream(zipName)
    val hash = appServiceHub.attachments.importAttachment(
      jar = inputStream,
      uploader = attachment.dataType,
      filename = attachment.filename
    )
    inputStream.close()
    Files.deleteIfExists(Paths.get(zipName))
    return hash
  }
}

/**
 * Specific extension of the abstract flow initiation logic that uses Corda API
 * to initiate flow execution in the Corda node where the service is running.
 */
class LocalFlowInitiator<ReturnType: Any> : FlowInitiator<ReturnType>(), CordaptorComponent {

  companion object {
    val logger = loggerFor<LocalFlowInitiator<*>>()
  }

  private val appServiceHub: AppServiceHub by inject()
  private val localTypeModel: LocalTypeModel by inject()

  override val instanceLogger: Logger get() = logger

  override fun doInitiateFlow(instruction: CordaFlowInstruction<FlowLogic<ReturnType>>): Handle<ReturnType> {
    val flowInstance = FlowInstanceBuilder(
        instruction.flowClass, instruction.arguments, localTypeModel).instantiate()

    val cordaHandle = appServiceHub.startTrackedFlow(flowInstance)

    return if (instruction.options?.trackProgress == true) {
      val flowProgressFeed = flowInstance.track()
          ?: throw IllegalStateException("Flow has a progress tracked, but calling track() returned null progress feed")

      Handle(cordaHandle.id.uuid, cordaHandle.returnValue, flowProgressFeed.updates)
    } else {
      Handle(cordaHandle.id.uuid, cordaHandle.returnValue)
    }
  }
}
