package tech.b180.cordaptor.cordapp

import net.corda.core.contracts.ContractState
import net.corda.core.flows.FlowLogic
import net.corda.node.services.api.ServiceHubInternal
import tech.b180.cordaptor.corda.*
import tech.b180.cordaptor.kernel.CordaptorComponent
import kotlin.reflect.KClass

class CordaNodeCatalogImpl(
    serviceHubInternal: ServiceHubInternal
) : CordaNodeCatalogInner, CordaptorComponent {

  override val cordapps: Collection<CordappInfo>

  init {
    cordapps = serviceHubInternal.cordappProvider.cordapps
        // filtering out built-in Corda flows because they do not yield an app-specific API
        .filterNot { it.info.shortName == "corda-core" }
        .map { cordapp ->
      CordappInfo(
          shortName = cordapp.info.shortName,
          flows = cordapp.rpcFlows.map { flowClass ->
            @Suppress("UNCHECKED_CAST")
            val flowKClass = flowClass.kotlin as KClass<out FlowLogic<Any>>

            CordappFlowInfo(
                flowClass = flowKClass,
                flowResultClass = determineFlowResultClass(flowKClass)
            )
          },
          contractStates = cordapp.contractClassNames.map { contractClassName ->
            @Suppress("UNCHECKED_CAST")
            CordappContractStateInfo(
                stateClass = Class.forName(contractClassName).kotlin as KClass<out ContractState>
            )
          }
      )
    }
  }
}
