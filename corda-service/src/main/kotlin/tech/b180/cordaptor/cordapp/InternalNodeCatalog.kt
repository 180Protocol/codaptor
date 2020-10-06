package tech.b180.cordaptor.cordapp

import net.corda.node.services.api.ServiceHubInternal
import net.corda.serialization.internal.amqp.LocalSerializerFactory
import net.corda.serialization.internal.model.LocalTypeModel
import tech.b180.cordaptor.corda.CordaNodeCatalogInner
import tech.b180.cordaptor.corda.CordappContractStateInfo
import tech.b180.cordaptor.corda.CordappFlowInfo
import tech.b180.cordaptor.corda.CordappInfo
import tech.b180.cordaptor.kernel.CordaptorComponent

class CordaNodeCatalogImpl(
    serviceHubInternal: ServiceHubInternal,
    localTypeModel: LocalTypeModel
) : CordaNodeCatalogInner, CordaptorComponent {

  override val cordapps: Collection<CordappInfo>

  init {
    cordapps = serviceHubInternal.cordappProvider.cordapps.map { cordapp ->
      CordappInfo(
          shortName = cordapp.info.shortName,
          flows = cordapp.rpcFlows.map { flowClass ->
            CordappFlowInfo(
                flowClassName = flowClass.canonicalName,
                flowTypeInfo = localTypeModel.inspect(flowClass)
            )
          },
          contractStates = cordapp.contractClassNames.map { contractClassName ->
            CordappContractStateInfo(
                stateClassName = contractClassName,
                stateTypeInfo = localTypeModel.inspect(Class.forName(contractClassName))
            )
          }
      )
    }
  }
}