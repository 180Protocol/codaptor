package tech.b180.cordaptor.cache

import org.koin.core.qualifier.named
import org.koin.dsl.bind
import org.koin.dsl.module
import org.koin.ext.scope
import tech.b180.cordaptor.corda.CordaNodeCatalog
import tech.b180.cordaptor.corda.CordaNodeCatalogInner
import tech.b180.cordaptor.corda.CordaNodeState
import tech.b180.cordaptor.corda.CordaNodeStateInner
import tech.b180.cordaptor.kernel.*

/**
 * Implementation of the microkernel module provider that makes the components
 * of this module available for injection into other modules' components.
 *
 * This class is instantiated by the microkernel at runtime using [java.util.ServiceLoader].
 */
@Suppress("UNUSED")
class LocalCacheModuleProvider : ModuleProvider {
  override val salience = ModuleProvider.INNER_MODULE_SALIENCE + 100

  companion object {
    const val USE_LOCAL_CACHE_PROPERTY = "use.local.cache"
  }

  override val module = module(override = true) {
    single<CordaNodeCatalog> {
      // unless local cache is enabled, inner implementation will be reexported as it is
      if (getBooleanProperty(USE_LOCAL_CACHE_PROPERTY))
        CachedNodeCatalog(get())
      else
        get<CordaNodeCatalogInner>()
    } bind LifecycleAware::class

    single<CordaNodeState> {
      // unless local cache is enabled, inner implementation will be reexported as it is
      if (getBooleanProperty(USE_LOCAL_CACHE_PROPERTY))
        CachedNodeState(get())
      else
        get<CordaNodeStateInner>()
    }
  }
}
