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

  companion object {
    private val logger = loggerFor<CordaNodeCatalogImpl>()
  }

  override val cordapps: Collection<CordappInfo>

  init {
    cordapps = serviceHubInternal.cordappProvider.cordapps
        // filtering out built-in Corda flows because they do not yield an app-specific API
        // filtering out Cordaptor bundle, as it does not contain any flow or contract state classes
        .filterNot { it.info.shortName == "corda-core" || it.info.shortName == bundleCordappName }
        .map { cordapp ->
          logger.info("Found CordApp ${cordapp.info} at ${cordapp.jarPath}")

          CordappInfo(
              shortName = cordapp.info.shortName,
              jarHash = cordapp.jarHash,
              jarURL = cordapp.jarPath,
              flows = cordapp.allFlows
                  .filter { flowClass ->
                    if (!FlowLogic::class.java.isAssignableFrom(flowClass)) {
                      throw AssertionError("Class ${flowClass.canonicalName} " +
                          "was identified as a flow by Corda, but cannot be cast to FlowLogic<*>")
                    }

                    val canStartByService = flowClass.getAnnotation(StartableByService::class.java) != null
                    canStartByService.also {
                      // we will ignore the flow because it is not available to Corda service,
                      // but we can provide the user with further information in the log to help with troubleshooting

                      val canStartByRPC = flowClass.getAnnotation(StartableByRPC::class.java) != null
                      if (canStartByRPC) {
                        logger.warn("Flow class ${flowClass.canonicalName} can be started by RPC, " +
                            "but is not available to Cordaptor running as a service within the node. " +
                            "Annotate the flow class with @StartableByService to make it available")
                      } else {
                        logger.debug("Ignoring flow class {} as it is not available to services nor RPC",
                            flowClass.canonicalName)
                      }
                    }
                  }
                  .map { flowClass ->
                    @Suppress("UNCHECKED_CAST")
                    val flowKClass = flowClass.kotlin as KClass<out FlowLogic<Any>>
                    val flowResultClass = determineFlowResultClass(flowKClass)

                    logger.debug("Registering flow class {} returning instances of {}",
                        flowClass.canonicalName, flowResultClass.qualifiedName)

                    CordappFlowInfo(flowClass = flowKClass, flowResultClass = flowResultClass)
                  },

              contractStates = cordapp.cordappClasses
                  // filtering out SDK classes that could be picked up by the introspecting scanner
                  .filterNot { it.startsWith("kotlin") || it.startsWith("java") || it.startsWith("[") }
                  .map { Class.forName(it) }
                  .filter { clazz ->
                    ContractState::class.java.isAssignableFrom(clazz).also {
                      // this class will be registered
                      if (!it) {
                        logger.debug("Cordapp class {} does not implement ContractState interface", clazz.canonicalName)
                      }
                    }
                  }
                  .map { clazz ->
                    val contractAnnotation = clazz.getAnnotation(BelongsToContract::class.java)
                    if (contractAnnotation == null) {
                      logger.warn("Contract state class ${clazz.canonicalName} is not annotated with @BelongsToContract")
                    }

                    @Suppress("UNCHECKED_CAST")
                    val stateClass = clazz.kotlin as KClass<out ContractState>

                    logger.debug("Registering contract state class {} managed by contract {}",
                        clazz.canonicalName, contractAnnotation?.value?.qualifiedName ?: "(no annotation)")

                    CordappContractStateInfo(stateClass = stateClass)
                  }
          )
    }
  }
}
