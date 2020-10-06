package tech.b180.cordaptor.rpc

import org.koin.dsl.bind
import org.koin.dsl.module
import tech.b180.cordaptor.kernel.BootstrapSettings
import tech.b180.cordaptor.kernel.LifecycleAware
import tech.b180.cordaptor.kernel.ModuleProvider
import tech.b180.cordaptor.kernel.getHostAndPortProperty

/**
 * Implementation of the microkernel module provider that makes the components
 * of this module available for injection into other modules' components.
 *
 * This class is instantiated by the microkernel at runtime using [java.util.ServiceLoader].
 */
@Suppress("UNUSED")
class CordaRpcClientModuleProvider : ModuleProvider {
  override val salience = ModuleProvider.INNER_MODULE_SALIENCE

  override fun provideModule(settings: BootstrapSettings) = module {
    single {
      CordaRpcConnection(
          getHostAndPortProperty("node.address")
      ) as LifecycleAware
    } bind LifecycleAware::class
  }
}
