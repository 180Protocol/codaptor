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
  override fun buildModule() = module {
    single<CordaNodeCatalog>(named(Tier.OUTER)) { ClusteredNodeCatalog() } bind LifecycleAware::class
    single<CordaNodeState>(named(Tier.OUTER)) { ClusteredNodeState() } bind LifecycleAware::class
  }
}
