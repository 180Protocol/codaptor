package tech.b180.cordaptor.cache

import org.koin.core.qualifier.named
import org.koin.dsl.bind
import org.koin.dsl.module
import tech.b180.cordaptor.corda.CordaNodeCatalog
import tech.b180.cordaptor.kernel.*

/**
 * Implementation of the microkernel module provider that makes the components
 * of this module available for injection into other modules' components.
 *
 * This class is instantiated by the microkernel at runtime using [java.util.ServiceLoader].
 */
@Suppress("UNUSED")
class LocalCacheModuleProvider : ModuleProvider {
  override fun buildModule() = module {
    single<CordaNodeCatalog>(named(Tier.OUTER)) {
      // unless local cache is enabled, inner implementation will be reexported as it is
      if (getBooleanProperty("use.local.cache"))
        CachedNodeCatalog()
      else
        get(CordaNodeCatalog::class, named(Tier.INNER))
    } bind LifecycleAware::class
  }
}
