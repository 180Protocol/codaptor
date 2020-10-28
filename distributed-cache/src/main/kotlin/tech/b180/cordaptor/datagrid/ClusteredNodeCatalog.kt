package tech.b180.cordaptor.datagrid

import org.koin.core.inject
import tech.b180.cordaptor.corda.CordaNodeCatalog
import tech.b180.cordaptor.corda.CordaNodeCatalogInner
import tech.b180.cordaptor.corda.CordaNodeState
import tech.b180.cordaptor.corda.CordappInfo
import tech.b180.cordaptor.kernel.CordaptorComponent
import tech.b180.cordaptor.kernel.LifecycleAware

class ClusteredNodeCatalog(private val delegate: CordaNodeCatalogInner)
  : CordaNodeCatalog, CordaptorComponent, LifecycleAware {

  private val nodeCatalog by inject<CordaNodeCatalog>()
  private val nodeState by inject<CordaNodeState>()

  override val cordapps: Collection<CordappInfo>
    get() = TODO("Not yet implemented")

  override fun onInitialize() {
    TODO("Not yet implemented")
  }

  override fun onShutdown() {
    TODO("Not yet implemented")
  }

}