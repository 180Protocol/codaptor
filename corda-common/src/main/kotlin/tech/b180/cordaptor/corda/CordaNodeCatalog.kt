package tech.b180.cordaptor.corda

/**
 * Single access point for all descriptive information about CorDapps installed
 * on a particular Corda node that may be used when implementing Cordaptor API.
 *
 * Different modules implement this interface in a different way depending
 * on the nature of their interaction with the underlying node.
 */
interface CordaNodeCatalog {

  val cordapps: Collection<CordappInfo>

  fun findCordapp(shortName: String): CordappInfo?

  fun findCordappFlow(cordappShortName: String): CordappFlowInfo?

  fun findContractState(stateClassName: String): ContractStateInfo?
}

data class CordappInfo(
    val shortName: String
)

data class CordappFlowInfo(
    val flowClassName: String
)

data class ContractStateInfo(
    val stateClassName: String
)