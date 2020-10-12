package tech.b180.cordaptor.rest

import net.corda.core.crypto.SecureHash
import net.corda.core.flows.FlowLogic
import org.eclipse.jetty.http.HttpMethod
import org.eclipse.jetty.server.Request
import org.eclipse.jetty.server.handler.AbstractHandler
import org.koin.core.inject
import org.koin.core.parameter.parametersOf
import tech.b180.cordaptor.corda.CordaFlowProgress
import tech.b180.cordaptor.corda.CordaFlowSnapshot
import tech.b180.cordaptor.corda.CordaNodeCatalog
import tech.b180.cordaptor.corda.CordaNodeState
import tech.b180.cordaptor.kernel.CordaptorComponent
import javax.servlet.ServletException
import javax.servlet.http.HttpServletRequest
import javax.servlet.http.HttpServletResponse
import kotlin.reflect.KClass

class NodeStateApiProvider(private val contextPath: String) : ContextMappedHandlerFactory, CordaptorComponent {

  override val handlers: List<ContextMappedHandler>

  private val nodeCatalog by inject<CordaNodeCatalog>()

  init {
    @Suppress("UNCHECKED_CAST")
    handlers = nodeCatalog.cordapps.flatMap {
      val flowHandlers : List<ContextMappedHandler> = it.flows.map { flowInfo ->
        val handlerPath = "$contextPath/${flowInfo.flowClass.qualifiedName}"
        FlowInitiationHandler(handlerPath, flowInfo.flowClass, flowInfo.flowResultClass as KClass<Any>)
      }

      val stateHandlers = it.contractStates.map { stateInfo ->
        val handlerPath = "$contextPath/${stateInfo.stateClass.qualifiedName}"
        StateQueryHandler(handlerPath)
      }

      flowHandlers + stateHandlers
    }
  }
}

/**
 * Jetty HTTP handler allowing to initiate a Corda flow asynchronously and optionally
 * wait for the flow to complete.
 *
 * This class uses type parameters to reduce the change of introducing any type-related bugs
 * in the implementation code. However, this class is not instantiated with type parameters.
 */
class FlowInitiationHandler<ReturnType: Any>(
    contextPath: String,
    private val flowClass: KClass<out FlowLogic<ReturnType>>,
    private val flowResultClass: KClass<ReturnType>
)
  : ContextMappedHandler, CordaptorComponent, AbstractHandler() {

  private val cordaNodeState: CordaNodeState by inject()

  private val flowSerializer by injectSerializer(flowClass)
  private val flowResultSerializer by injectSerializer(flowResultClass)

  // use explicit type parameter so that the resulting JSON schema is strongly typed
  private val flowSnapshotSerializer: JsonSerializer<CordaFlowSnapshot<ReturnType>>
      by inject { parametersOf(SerializerKey(CordaFlowSnapshot::class, flowResultClass)) }

  override val mappingParameters = ContextMappingParameters(contextPath, true)

  override fun handle(target: String?, baseRequest: Request?, request: HttpServletRequest?, response: HttpServletResponse?) {
    baseRequest!!.isHandled = true

    if (request!!.method != HttpMethod.POST.asString()) {
      response!!.status = HttpServletResponse.SC_METHOD_NOT_ALLOWED
      return
    }

    response!!.contentType = "application/json"

    val waitTimeout = request.getParameter("wait")?.let {
      it.toIntOrNull() ?: throw ServletException("Expected integer value for wait parameter, got [$it]")
    }

    val flowObject = JsonHome.createReader(request.reader).readObject()
    val flow = flowSerializer.fromJson(flowObject)

    val handle = cordaNodeState.initiateFlow(flow)

    if (waitTimeout == null) {
      // no wait parameter, return initial snapshot straight away
      val snapshot = CordaFlowSnapshot(flowClass = flowClass,
          flowRunId = handle.flowRunId, currentProgress = CordaFlowProgress.noProgressInfo,
          startedAt = handle.startedAt)

      response!!.status = HttpServletResponse.SC_ACCEPTED
      JsonHome.createGenerator(response!!.writer)
          .writeSerializedObject(flowSnapshotSerializer, snapshot)
          .flush()
    } else {

      TODO("Not implemented")
    }
  }
}

class StateQueryHandler(
    contextPath: String
) : ContextMappedHandler, AbstractHandler() {

  override val mappingParameters = ContextMappingParameters(contextPath, true)

  override fun handle(target: String?, baseRequest: Request?, request: HttpServletRequest?, response: HttpServletResponse?) {
    TODO("Not yet implemented")
  }

}

/**
 * Resolves REST API queries for specific transactions using a secure hash.
 */
class TransactionQueryHandler(contextPath: String)
  : ContextMappedHandler, AbstractHandler(), CordaptorComponent {

  private val nodeState: CordaNodeState by inject()
//  private val txSerializer: CordaSignedTransactionSerializer by inject()

  override val mappingParameters = ContextMappingParameters(contextPath, true)

  override fun handle(target: String?, baseRequest: Request?, request: HttpServletRequest?, response: HttpServletResponse?) {
    when (baseRequest!!.method) {
      "GET" -> {
        val hash = SecureHash.parse(request!!.pathInfo!!)
        val tx = nodeState.findTransactionByHash(hash)
            ?: throw NoSuchElementException()

//        val serializedTx = txSerializer.toJson(tx)
//        response!!.outputStream.print(serializedTx.toString())
      }
      else -> {
        response!!.status = HttpServletResponse.SC_METHOD_NOT_ALLOWED
      }
    }
    baseRequest.isHandled = true
  }

}

class VaultQueryHandler(contextPath: String) : ContextMappedHandler, AbstractHandler() {

  override val mappingParameters = ContextMappingParameters(contextPath, true)

  override fun handle(target: String?, baseRequest: Request?, request: HttpServletRequest?, response: HttpServletResponse?) {
    TODO("Not yet implemented")
  }

}

class CountingVaultQueryHandler(contextPath: String) : ContextMappedHandler, AbstractHandler() {

  override val mappingParameters = ContextMappingParameters(contextPath, true)

  override fun handle(target: String?, baseRequest: Request?, request: HttpServletRequest?, response: HttpServletResponse?) {
    TODO("Not yet implemented")
  }

}

class AggregatingVaultQueryHandler(contextPath: String) : ContextMappedHandler, AbstractHandler() {

  override val mappingParameters = ContextMappingParameters(contextPath, true)

  override fun handle(target: String?, baseRequest: Request?, request: HttpServletRequest?, response: HttpServletResponse?) {
    TODO("Not yet implemented")
  }

}