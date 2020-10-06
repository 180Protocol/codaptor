package tech.b180.cordaptor.datagrid

import org.koin.core.qualifier.named
import org.koin.dsl.bind
import org.koin.dsl.module
import tech.b180.cordaptor.corda.CordaNodeCatalog
import tech.b180.cordaptor.corda.CordaNodeState
import tech.b180.cordaptor.kernel.*

/**
 * Implementation of the microkernel module provider that makes the components
 * of this module available for injection into other modules' components.
 *
 * This class is instantiated by the microkernel at runtime using [java.util.ServiceLoader].
 */
@Suppress("UNUSED")
class DataGridModuleProvider : ModuleProvider {
  override val salience = ModuleProvider.INNER_MODULE_SALIENCE + 100

  override val module = module(override = true) {
    single<CordaNodeCatalog> { ClusteredNodeCatalog() } bind LifecycleAware::class
    single<CordaNodeState> { ClusteredNodeState() } bind LifecycleAware::class
  }
}
