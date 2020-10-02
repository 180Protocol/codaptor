package tech.b180.cordaptor.cordapp

import org.koin.core.qualifier.named
import org.koin.dsl.bind
import org.koin.dsl.module
import tech.b180.cordaptor.corda.CordaNodeCatalog
import tech.b180.cordaptor.kernel.LifecycleAware
import tech.b180.cordaptor.kernel.ModuleProvider
import tech.b180.cordaptor.kernel.Tier

/**
 * Implementation of the microkernel module provider that makes the components
 * of this module available for injection into other modules' components.
 *
 * This class is instantiated by the microkernel at runtime using [java.util.ServiceLoader].
 */
@Suppress("UNUSED")
class CordaServiceModuleProvider : ModuleProvider {
  override fun buildModule() = module {
    single<CordaNodeCatalog>(named(Tier.INNER)) { CordaNodeCatalogImpl() } bind LifecycleAware::class
  }
}