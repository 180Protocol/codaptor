package tech.b180.cordaptor.cache

import tech.b180.cordaptor.corda.ContractStateInfo
import tech.b180.cordaptor.corda.CordaNodeCatalog
import tech.b180.cordaptor.corda.CordappFlowInfo
import tech.b180.cordaptor.corda.CordappInfo

class CachedNodeCatalog : CordaNodeCatalog {
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

}