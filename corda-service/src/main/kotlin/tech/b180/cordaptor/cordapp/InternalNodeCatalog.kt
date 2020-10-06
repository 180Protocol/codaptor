package tech.b180.cordaptor.cordapp

import net.corda.node.services.api.ServiceHubInternal
import net.corda.serialization.internal.amqp.LocalSerializerFactory
import org.koin.core.KoinComponent
import tech.b180.cordaptor.corda.*

class CordaNodeCatalogImpl(
    serviceHubInternal: ServiceHubInternal,
    serializerFactory: LocalSerializerFactory
) : CordaNodeCatalogInner, KoinComponent {

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