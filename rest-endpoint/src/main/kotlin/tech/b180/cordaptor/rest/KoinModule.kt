package tech.b180.cordaptor.rest

import org.koin.dsl.bind
import tech.b180.cordaptor.kernel.getHostAndPortProperty

import org.koin.dsl.module
import tech.b180.cordaptor.kernel.LifecycleAware
import tech.b180.cordaptor.kernel.ModuleProvider

/**
 * Implementation of the microkernel module provider that makes the components
 * of this module available for injection into other modules' components.
 *
 * This class is instantiated by the microkernel at runtime using [java.util.ServiceLoader].
 */
@Suppress("UNUSED")
class RestEndpointModuleProvider : ModuleProvider {
  override fun buildModule() = module {
    single {
      JettyServer(getHostAndPortProperty("listen.address"))
    } bind LifecycleAware::class
  }
}
