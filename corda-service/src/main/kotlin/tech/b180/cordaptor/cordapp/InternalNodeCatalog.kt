package tech.b180.cordaptor.cordapp

import net.corda.node.services.api.ServiceHubInternal
import net.corda.serialization.internal.amqp.LocalSerializerFactory
import net.corda.serialization.internal.model.LocalTypeModel
import tech.b180.cordaptor.corda.*
import tech.b180.cordaptor.kernel.CordaptorComponent

class CordaNodeCatalogImpl(
    serviceHubInternal: ServiceHubInternal,
    serializerFactory: LocalSerializerFactory
) : CordaNodeCatalogInner, CordaptorComponent {

  override val cordapps: Collection<CordappInfo>

  init {
    cordapps = serviceHubInternal.cordappProvider.cordapps.map { cordapp ->
      CordappInfo(
          shortName = cordapp.info.shortName,
          flows = cordapp.rpcFlows.map { flowClass ->
            CordappFlowInfo(
                flowClassName = flowClass.canonicalName,
                flowTypeInfo = serializerFactory.getTypeInformation(flowClass)
            )
          },
          contractStates = cordapp.contractClassNames.map { contractClassName ->
            CordappContractStateInfo(
                stateClassName = contractClassName,
                stateTypeInfo = serializerFactory.getTypeInformation(contractClassName)
                    ?: throw AssertionError("Type information for $contractClassName not found")
            )
          }
      )
    }
  }
}