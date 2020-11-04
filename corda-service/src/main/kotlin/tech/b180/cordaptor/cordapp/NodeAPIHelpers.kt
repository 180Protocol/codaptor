package tech.b180.cordaptor.cordapp

import net.corda.core.flows.FlowLogic
import net.corda.serialization.internal.model.LocalTypeInformation
import net.corda.serialization.internal.model.LocalTypeModel
import tech.b180.cordaptor.kernel.loggerFor
import kotlin.reflect.KClass

/**
 * Testable wrapper for logic creating an instance of a flow class derived from [FlowLogic]
 * from a map of parameters using Corda introspection mechanism.
 */
data class FlowInstanceBuilder<ReturnType: Any>(
    val flowClass: KClass<out FlowLogic<ReturnType>>,
    val flowProperties: Map<String, Any>,
    val localTypeModel: LocalTypeModel
) {

  companion object {
    private val logger = loggerFor<FlowInstanceBuilder<*>>()
  }

  fun instantiate(): FlowLogic<ReturnType> {
    val typeInfo = localTypeModel.inspect(flowClass.java)

    val constructor = (typeInfo as? LocalTypeInformation.Composable)?.constructor
        ?: throw IllegalArgumentException("Unable to instantiate flow class ${flowClass.qualifiedName}.\n" +
            "Introspection details: ${typeInfo.prettyPrint()})")

    logger.debug("Instantiating flow class with constructor: {}", constructor)

    val actualArguments = if (constructor.hasParameters) {
      constructor.parameters.map {
        flowProperties[it.name]
            ?: if (it.isMandatory)
              throw IllegalArgumentException("Missing value for mandatory constructor parameter [${it.name}]")
            else
              null
      }
    } else {
      emptyList()
    }

    logger.debug("Instantiating flow class with arguments: {}", actualArguments)

    @Suppress("UNCHECKED_CAST")
    return constructor.observedMethod.newInstance(*actualArguments.toTypedArray()) as FlowLogic<ReturnType>
  }
}