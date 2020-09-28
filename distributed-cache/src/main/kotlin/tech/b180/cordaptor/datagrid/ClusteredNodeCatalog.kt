package tech.b180.cordaptor.datagrid

import org.koin.core.KoinComponent
import org.koin.core.inject
import org.koin.core.qualifier.named
import tech.b180.cordaptor.corda.*
import tech.b180.cordaptor.kernel.LifecycleAware
import tech.b180.cordaptor.kernel.Tier

class ClusteredNodeCatalog : CordaNodeCatalog, KoinComponent, LifecycleAware {

  private val nodeCatalog by inject<CordaNodeCatalog>(named(Tier.INNER))
  private val nodeState by inject<CordaNodeState>(named(Tier.INNER))

  override val cordapps: Collection<CordappInfo>
    get() = TODO("Not yet implemented")

  override fun findCordapp(shortName: String): CordappInfo? {
    TODO("Not yet implemented")
  }

  override fun findCordappFlow(cordappShortName: String): CordappFlowInfo? {
    TODO("Not yet implemented")
  }

  override fun findContractState(stateClassName: String): ContractStateInfo? {
    TODO("Not yet implemented")
  }

  override fun initialize() {
    TODO("Not yet implemented")
  }

  override fun shutdown() {
    TODO("Not yet implemented")
  }

}