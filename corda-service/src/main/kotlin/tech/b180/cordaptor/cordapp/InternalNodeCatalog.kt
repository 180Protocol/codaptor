package tech.b180.cordaptor.cordapp

import net.corda.core.contracts.BelongsToContract
import net.corda.core.contracts.ContractState
import net.corda.core.flows.FlowLogic
import net.corda.core.flows.StartableByRPC
import net.corda.core.flows.StartableByService
import net.corda.node.services.api.ServiceHubInternal
import tech.b180.cordaptor.corda.*
import tech.b180.cordaptor.kernel.CordaptorComponent
import tech.b180.cordaptor.kernel.loggerFor
import kotlin.reflect.KClass

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
    cordapps = CordappInfoBuilder(cordapps = serviceHubInternal.cordappProvider.cordapps
        // filtering out built-in Corda flows because they do not yield an app-specific API
        // filtering out Cordaptor bundle, as it does not contain any flow or contract state classes
        .filterNot { it.info.shortName == "corda-core" || it.info.shortName == bundleCordappName }
    ).build()
  }
}
