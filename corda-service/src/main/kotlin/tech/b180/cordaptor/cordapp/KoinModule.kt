package tech.b180.cordaptor.cordapp

import org.koin.dsl.module
import tech.b180.cordaptor.kernel.ModuleProvider
import tech.b180.cordaptor.kernel.getTokenizedProperty

/**
 * Implementation of the microkernel module provider that makes the components
 * of this module available for injection into other modules' components.
 *
 * This class is instantiated by the microkernel at runtime using [java.util.ServiceLoader].
 */
@Suppress("UNUSED")
class CordaServiceModuleProvider : ModuleProvider {
  override fun buildModule() = module {
    single { CordappCatalog(getTokenizedProperty("cordapp.package.names")) }
  }
}
