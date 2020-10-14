package tech.b180.cordaptor.rpc

import net.corda.node.services.api.ServiceHubInternal
import tech.b180.cordaptor.corda.CordaNodeCatalog
import tech.b180.cordaptor.corda.CordaNodeCatalogInner
import tech.b180.cordaptor.corda.CordappInfo
import tech.b180.cordaptor.kernel.CordaptorComponent
import tech.b180.cordaptor.kernel.loggerFor

/**
 * Implementation of the [CordaNodeCatalog] interface that uses Corda RPC API
 * and local JAR file introspection to discover available CorDapps.
 */
class ClientNodeCatalogImpl(
    serviceHubInternal: ServiceHubInternal,
    bundleCordappName: String
) : CordaNodeCatalogInner, CordaptorComponent {

  companion object {
    private val logger = loggerFor<ClientNodeCatalogImpl>()
  }

  override val cordapps: Collection<CordappInfo>
    get() = TODO("Not yet implemented")
}