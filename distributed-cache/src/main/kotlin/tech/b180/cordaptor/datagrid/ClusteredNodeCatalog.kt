package tech.b180.cordaptor.datagrid

import org.koin.core.KoinComponent
import org.koin.core.inject
import org.koin.core.qualifier.named
import tech.b180.cordaptor.corda.*
import tech.b180.cordaptor.kernel.LifecycleAware

class ClusteredNodeCatalog : CordaNodeCatalog, KoinComponent, LifecycleAware {

  private val nodeCatalog by inject<CordaNodeCatalog>()
  private val nodeState by inject<CordaNodeState>()

  override val cordapps: Collection<CordappInfo>
    get() = TODO("Not yet implemented")

  override fun initialize() {
    TODO("Not yet implemented")
  }

  override fun shutdown() {
    TODO("Not yet implemented")
  }

}