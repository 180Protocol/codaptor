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

     val constructorParametersPair =  when (typeInfo) {
          is LocalTypeInformation.Composable -> Pair((typeInfo as? LocalTypeInformation.Composable)?.constructor, (typeInfo as? LocalTypeInformation.Composable)?.constructor!!.parameters)
          is LocalTypeInformation.NonComposable -> Pair((typeInfo as? LocalTypeInformation.NonComposable)?.constructor,
                  (typeInfo as? LocalTypeInformation.NonComposable)?.constructor?.parameters?.filterNot { param ->
                      typeInfo.nonComposableTypes.map { type -> type.observedType }.contains(param.type.observedType)
                  })
          else -> throw IllegalArgumentException(
              "Flow $flowClass is introspected as either composable or non-composable:\n" +
                      typeInfo.prettyPrint(true)
          )
      }

    logger.debug("Instantiating flow class with constructor: {}", constructorParametersPair.first)

    val actualArguments = if (constructorParametersPair.second!!.isNotEmpty()) {
        constructorParametersPair.second?.map {
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
    return constructorParametersPair.first?.observedMethod?.newInstance(*actualArguments!!.toTypedArray()) as FlowLogic<ReturnType>
  }
}