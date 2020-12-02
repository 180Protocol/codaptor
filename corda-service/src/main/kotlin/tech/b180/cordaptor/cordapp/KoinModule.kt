package tech.b180.cordaptor.cordapp

import net.corda.core.node.AppServiceHub
import net.corda.node.services.api.ServiceHubInternal
import net.corda.serialization.internal.model.LocalTypeModel
import org.koin.dsl.bind
import org.koin.dsl.module
import tech.b180.cordaptor.corda.*
import tech.b180.cordaptor.kernel.Config
import tech.b180.cordaptor.kernel.ConfigPath
import tech.b180.cordaptor.kernel.ModuleAPI
import tech.b180.cordaptor.kernel.ModuleProvider

/**
 * Single point of access for various APIs available within the Corda node.
 * Implementation is injected during the bootstrap process, and may be version-specific
 * where internal node APIs are being exposed.
 *
 * Note that even through this module is part of module API, other modules should
 * exercise caution because this definition will only be available when Cordaptor
 * is deployed as an embedded Corda service.
 */
@ModuleAPI(since = "0.1")
interface NodeServicesLocator {
  val appServiceHub: AppServiceHub
  val serviceHubInternal: ServiceHubInternal
  val localTypeModel: LocalTypeModel
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

  override val configPath: ConfigPath = "cordaService"

  override fun provideModule(moduleConfig: Config) = module {
    val settings = Settings(moduleConfig)
    single { settings }

    // actual components implementing Corda API access layer
    // these 'inner' definitions may be accessed by other modules to use as delegates
    // when overriding 'outer' definitions, e.g. by the caching layer
    single<CordaNodeCatalogInner> { CordaNodeCatalogImpl(get(), settings.bundleCordappName) }
    single<CordaNodeStateInner> { CordaNodeStateImpl() } bind CordaNodeVault::class

    // outward-facing definitions for the Corda API access layer components
    // which may be overridden by higher-tier modules augmenting the functionality
    single<CordaNodeCatalog> { get<CordaNodeCatalogInner>() }
    single<CordaNodeState> { get<CordaNodeStateInner>() }

    single { LocalFlowInitiator<Any>() }

    // expose Corda node APIs to other definitions without the need to traverse the properties
    single { get<NodeServicesLocator>().serviceHubInternal }
    single { get<NodeServicesLocator>().localTypeModel }
    single { get<NodeServicesLocator>().appServiceHub }
    single { get<NodeServicesLocator>().appServiceHub.validatedTransactions }
    single { get<NodeServicesLocator>().appServiceHub.vaultService }
    single { get<NodeServicesLocator>().appServiceHub.identityService }
  }
}

/**
 * Eagerly-initialized typesafe wrapper for module's configuration.
 */
class Settings private constructor(
    /** Name of the CorDapp for the Cordaptor bundle to explicitly ignore in CorDapp scanning */
    val bundleCordappName: String
) {
  constructor(ourConfig: Config) : this(
      bundleCordappName = ourConfig.getString("bundleCordappName")
  )
}
