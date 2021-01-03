package tech.b180.cordaptor.corda

import net.corda.core.flows.FlowLogic
import kotlin.reflect.KClass
import kotlin.reflect.full.allSupertypes

/**
 * Unpacks reflection data about a flow class to determine its return type.
 * The logic relies on the assumption that concrete flow class will be a subtype
 * of [FlowLogic] with a specific type parameter.
 *
 * FIXME allow an annotation on a flow class to override the discovery logic
 * FIXME log details of failed type discovery
 */
fun determineFlowResultClass(flowClass: KClass<out FlowLogic<Any>>): KClass<out Any> {
  val flowLogicType = flowClass.allSupertypes.find { it.classifier == FlowLogic::class }
      ?: throw AssertionError("Flow class $flowClass does not seem to extend FlowLogic")

  val flowLogicReturnType = flowLogicType.arguments.firstOrNull()?.type
      ?: throw AssertionError("Flow class $flowClass does not seem to give FlowLogic any type parameters")

  return flowLogicReturnType.classifier as? KClass<out Any>
      ?: throw AssertionError("Flow class $flowClass seems to have FlowLogic a non-class type parameter")
}
