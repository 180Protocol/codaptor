package tech.b180.cordaptor.rpc

import hu.akarnokd.rxjava3.interop.RxJavaInterop
import io.reactivex.rxjava3.subjects.SingleSubject
import net.corda.core.flows.FlowLogic
import net.corda.core.messaging.CordaRPCOps
import net.corda.core.messaging.FlowProgressHandle
import net.corda.serialization.internal.AllWhitelist
import net.corda.serialization.internal.amqp.CachingCustomSerializerRegistry
import net.corda.serialization.internal.amqp.DefaultDescriptorBasedSerializerRegistry
import net.corda.serialization.internal.amqp.WhitelistBasedTypeModelConfiguration
import net.corda.serialization.internal.model.ConfigurableLocalTypeModel
import net.corda.serialization.internal.model.LocalTypeInformation
import org.koin.core.inject
import tech.b180.cordaptor.corda.CordaFlowHandle
import tech.b180.cordaptor.corda.CordaFlowInstruction
import tech.b180.cordaptor.corda.CordaFlowProgress
import tech.b180.cordaptor.corda.CordaFlowResult
import tech.b180.cordaptor.kernel.CordaptorComponent
import tech.b180.cordaptor.kernel.loggerFor
import java.time.Instant

/**
 * Logic for translating an instance of [CordaFlowInstruction] into an RPC call initiating the flow,
 * and returning an instance of [CordaFlowHandle] that is used to track the progress.
 *
 * This is a thread-safe service class.
 */
class FlowInitiator : CordaptorComponent {

  companion object {
    val logger = loggerFor<FlowInitiator>()
  }

  private val rpc: CordaRPCOps by inject()

  private val localTypeModel by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
    val customSerializerRegistry = CachingCustomSerializerRegistry(DefaultDescriptorBasedSerializerRegistry())
    val typeModelConfiguration = WhitelistBasedTypeModelConfiguration(AllWhitelist, customSerializerRegistry)
    ConfigurableLocalTypeModel(typeModelConfiguration)
  }

  fun <ReturnType: Any> initiateFlow(instruction: CordaFlowInstruction<FlowLogic<ReturnType>>): CordaFlowHandle<ReturnType> {
    val flowClass = instruction.flowClass.java
    logger.debug("Preparing to initiate flow {} over Corda RPC connection", flowClass)

    val typeInfo = localTypeModel.inspect(flowClass).let {
      it as? LocalTypeInformation.Composable
          ?: throw IllegalArgumentException("Flow ${flowClass} is introspected as non-composable:\n" +
              it.prettyPrint(true))
    }

    val actualArgs = arrayOfNulls<Any?>(typeInfo.constructor.parameters.size)
    typeInfo.constructor.parameters.forEachIndexed { index, param ->
      val givenValue = instruction.arguments[param.name]
      if (givenValue == null && param.isMandatory) {
        throw IllegalArgumentException("No value provided for mandatory parameter ${param.name}")
      }
      actualArgs[index] = givenValue
      logger.debug("Actual value for argument {}: {}", index, givenValue)
    }

    val flowHandle = if (instruction.options?.trackProgress == true) {
      logger.debug("Initiating flow {} with progress updates", flowClass)
      rpc.startTrackedFlowDynamic(flowClass, *actualArgs)
    } else {
      logger.debug("Initiating flow {} without progress updates", flowClass)
      rpc.startFlowDynamic(flowClass, *actualArgs)
    }

    logger.debug("Flow {} started with run id {}", flowClass.canonicalName, flowHandle.id)

    // asynchronously emit the result when flow result future completes
    // this will happen on the node's state machine thread
    val resultSubject = SingleSubject.create<CordaFlowResult<ReturnType>>()
    flowHandle.returnValue.then {
      try {
        val flowResult = it.get()
        logger.debug("Flow {} returned {}", flowHandle.id, flowResult)
        resultSubject.onSuccess(CordaFlowResult.forValue(flowResult))
      } catch (e: Throwable) {
        logger.debug("Flow {} threw an error:", flowHandle.id, e)
        resultSubject.onSuccess(CordaFlowResult.forError(e))
      }
    }

    val progressUpdates = if (flowHandle is FlowProgressHandle) {
      RxJavaInterop.toV3Observable(flowHandle.progress).map {
        logger.debug("Progress update for flow {}: {}", flowHandle.id, it)
        CordaFlowProgress(it)
      }.doOnDispose {
        logger.debug("Progress observable for flow {} was disposed", flowHandle.id)
      }.doOnComplete {
        logger.debug("Progress observable for flow {} completed", flowHandle.id)
      }
    } else {
      null
    }

    return CordaFlowHandle(
        startedAt = Instant.now(),
        flowClass = instruction.flowClass,
        flowRunId = flowHandle.id.uuid,
        flowResultPromise = resultSubject,
        flowProgressUpdates = progressUpdates
    )
  }
}