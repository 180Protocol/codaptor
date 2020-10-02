package tech.b180.cordaptor.cordapp

import net.corda.core.cordapp.Cordapp
import org.koin.core.KoinComponent
import tech.b180.cordaptor.corda.ContractStateInfo
import tech.b180.cordaptor.corda.CordaNodeCatalog
import tech.b180.cordaptor.corda.CordappFlowInfo
import tech.b180.cordaptor.corda.CordappInfo
import tech.b180.cordaptor.kernel.LifecycleAware

class CordaNodeCatalogImpl() : CordaNodeCatalog, LifecycleAware, KoinComponent {

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