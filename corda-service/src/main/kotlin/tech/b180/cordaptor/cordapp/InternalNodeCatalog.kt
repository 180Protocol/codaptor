package tech.b180.cordaptor.cordapp

import net.corda.node.services.api.ServiceHubInternal
import tech.b180.cordaptor.corda.CordaNodeCatalog
import tech.b180.cordaptor.corda.CordaNodeCatalogInner
import tech.b180.cordaptor.corda.CordappInfo
import tech.b180.cordaptor.corda.CordappInfoBuilder
import tech.b180.cordaptor.kernel.CordaptorComponent

/**
 * Implementation of the [CordaNodeCatalog] interface that uses internal Corda API
 * to introspect the information about cordapps running on the node.
 */
class CordaNodeCatalogImpl(
    serviceHubInternal: ServiceHubInternal,
    bundleCordappName: String
) : CordaNodeCatalogInner, CordaptorComponent {

  override val cordapps: Collection<CordappInfo>

  init {
    cordapps = CordappInfoBuilder(
        isEmbedded = true,
        cordapps = serviceHubInternal.cordappProvider.cordapps
            // filtering out built-in Corda flows because they do not yield an app-specific API
            // filtering out Cordaptor bundle, as it does not contain any flow or contract state classes
            .filterNot { it.info.shortName == "corda-core" || it.info.shortName == bundleCordappName }
    ).build()
  }
}
