package tech.b180.cordaptor.cache

import org.koin.dsl.bind
import org.koin.dsl.module
import tech.b180.cordaptor.corda.CordaNodeCatalog
import tech.b180.cordaptor.corda.CordaNodeState
import tech.b180.cordaptor.kernel.BootstrapSettings
import tech.b180.cordaptor.kernel.LifecycleAware
import tech.b180.cordaptor.kernel.ModuleProvider

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
    const val USE_LOCAL_CACHE_PROPERTY = "useLocalCache"
  }

  override fun provideModule(settings: BootstrapSettings) = module(override = true) {
    if (settings.getBoolean(USE_LOCAL_CACHE_PROPERTY)) {
      single<CordaNodeCatalog> { CachedNodeCatalog(get()) } bind LifecycleAware::class
      single<CordaNodeState> { CachedNodeState(get()) } bind LifecycleAware::class
    }
  }
}
