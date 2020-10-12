package tech.b180.cordaptor.cordapp

import net.corda.core.node.AppServiceHub
import net.corda.node.services.api.ServiceHubInternal
import org.koin.dsl.bind
import org.koin.dsl.module
import tech.b180.cordaptor.corda.CordaNodeCatalog
import tech.b180.cordaptor.corda.CordaNodeCatalogInner
import tech.b180.cordaptor.corda.CordaNodeState
import tech.b180.cordaptor.corda.CordaNodeStateInner
import tech.b180.cordaptor.kernel.BootstrapSettings
import tech.b180.cordaptor.kernel.ModuleProvider

/**
 * Single point of access for various APIs available within the Corda node.
 * Implementation is injected during the bootstrap process, and may be version-specific
 * where internal node APIs are being exposed.
 */
interface NodeServicesLocator {
  val appServiceHub: AppServiceHub
  val serviceHubInternal: ServiceHubInternal
}

/**
 * Implementation of the microkernel module provider that makes the components
 * of this module available for injection into other modules' components.
 *
 * This class is instantiated by the microkernel at runtime using [java.util.ServiceLoader].
 *
 * Note that there is no META-INF/services entry for [ModuleProvider].
 * Instead, an overarching bundle will contribute an all-encompassing entry file.
 */
@Suppress("UNUSED")
class CordaServiceModuleProvider : ModuleProvider {
  override val salience = ModuleProvider.INNER_MODULE_SALIENCE

  override fun provideModule(settings: BootstrapSettings) = module {
    single<CordaNodeCatalog> { CordaNodeCatalogImpl(get()) } bind CordaNodeCatalogInner::class
    single<CordaNodeState> { CordaNodeStateImpl() } bind CordaNodeStateInner::class
    single { CordaFlowDispatcher() }

    // expose Corda node APIs to other definitions without the need to traverse the properties
    single { get<NodeServicesLocator>().serviceHubInternal }
    single { get<NodeServicesLocator>().appServiceHub }
    single { get<NodeServicesLocator>().appServiceHub.validatedTransactions }
    single { get<NodeServicesLocator>().appServiceHub.vaultService }
    single { get<NodeServicesLocator>().appServiceHub.identityService }
  }
}
