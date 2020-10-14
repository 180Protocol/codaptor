package tech.b180.cordaptor.rest

import io.reactivex.rxjava3.core.Single
import net.corda.core.contracts.ContractState
import net.corda.core.contracts.StateRef
import net.corda.core.crypto.SecureHash
import net.corda.core.flows.FlowLogic
import net.corda.core.transactions.SignedTransaction
import org.eclipse.jetty.server.handler.AbstractHandler
import org.koin.core.get
import org.koin.core.inject
import org.koin.core.parameter.parametersOf
import sun.reflect.generics.tree.ReturnType
import tech.b180.cordaptor.corda.CordaFlowProgress
import tech.b180.cordaptor.corda.CordaFlowSnapshot
import tech.b180.cordaptor.corda.CordaNodeCatalog
import tech.b180.cordaptor.corda.CordaNodeState
import tech.b180.cordaptor.kernel.CordaptorComponent
import tech.b180.cordaptor.kernel.loggerFor
import java.lang.reflect.Type
import javax.servlet.http.HttpServletRequest
import javax.servlet.http.HttpServletResponse
import kotlin.reflect.KClass
import org.eclipse.jetty.server.Request as JettyRequest

/**
 * Factory class for specific Jetty handlers created for flows and contract states of CorDapps found on the node.
 */
class NodeStateAPIProvider(private val contextPath: String) : ContextMappedHandlerFactory, CordaptorComponent {

  override val handlers: List<ContextMappedHandler>

  private val nodeCatalog by inject<CordaNodeCatalog>()

  init {
    @Suppress("UNCHECKED_CAST")
    handlers = nodeCatalog.cordapps.flatMap { cordapp ->
      val flowHandlers : List<ContextMappedHandler> = cordapp.flows.map { flowInfo ->
        val handlerPath = "$contextPath/${cordapp.shortName}/${flowInfo.flowClass.simpleName}"
        val endpoint = FlowInitiationEndpoint(handlerPath,
            flowInfo.flowClass, flowInfo.flowResultClass as KClass<Any>)

        get<OperationEndpointHandler<*, *>> { parametersOf(endpoint) }
      }

      val stateHandlers = cordapp.contractStates.map { stateInfo ->
        val handlerPath = "$contextPath/${cordapp.shortName}/${stateInfo.stateClass.simpleName}"
        val endpoint = ContractStateQueryEndpoint(handlerPath,
            stateInfo.stateClass)

        get<QueryEndpointHandler<*>> { parametersOf(endpoint) }
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
class FlowInitiationEndpoint<FlowReturnType: Any>(
    contextPath: String,
    private val flowClass: KClass<out FlowLogic<FlowReturnType>>,
    flowResultClass: KClass<FlowReturnType>
) : OperationEndpoint<FlowLogic<FlowReturnType>, CordaFlowSnapshot<FlowReturnType>>, CordaptorComponent {

  companion object {
    private val logger = loggerFor<FlowInitiationEndpoint<*>>()
  }

  data class SnapshotAndStatusCode(
      val snapshot: CordaFlowSnapshot<ReturnType>,
      val statusCode: Int
  )

  private val cordaNodeState: CordaNodeState by inject()

  override val responseType = SerializerKey(CordaFlowSnapshot::class, flowResultClass).asType()
  override val contextMappingParameters = ContextMappingParameters(contextPath, true)
  override val requestType = flowClass.java
  override val supportedMethods = OperationEndpoint.POST_ONLY

  override fun executeOperation(
      request: RequestWithPayload<FlowLogic<FlowReturnType>>): Single<Response<CordaFlowSnapshot<FlowReturnType>>> {

    val waitTimeout = request.getParameter("wait")?.let {
      it.toIntOrNull() ?: throw BadOperationRequestException(
          "Expected integer value for wait parameter, got [$it]")
    }

    val handle = cordaNodeState.initiateFlow(request.payload)
    if (waitTimeout == null) {
      // no wait parameter, return initial snapshot straight away
      val snapshot = CordaFlowSnapshot(flowClass = flowClass,
          flowRunId = handle.flowRunId, currentProgress = CordaFlowProgress.noProgressInfo,
          startedAt = handle.startedAt)

      return Single.just(Response(snapshot, statusCode = HttpServletResponse.SC_ACCEPTED))
    } else {
      return handle.flowResultPromise
          .map {

            // wrap a CordaFlowResult into a serializable snapshot
            logger.debug("Async completion for flowRunId={}: result={}", handle.flowRunId, it)

            CordaFlowSnapshot(flowClass = flowClass, flowRunId = handle.flowRunId,
                currentProgress = CordaFlowProgress.noProgressInfo, startedAt = handle.startedAt, result = it)
          }.map {
            // wrap flow snapshot into a protocol response
            val statusCode = when (it.result!!.isError) {
              true -> HttpServletResponse.SC_INTERNAL_SERVER_ERROR
              false -> HttpServletResponse.SC_OK
            }

            logger.debug("Async operation return for flowRunId={}: status={}, snapshot={}", handle.flowRunId, statusCode, it)

            Response(it, statusCode)
          }
    }
  }
}

/**
 * Resolves REST API queries for specific contract state using a stateRef.
 */
class ContractStateQueryEndpoint<StateType: ContractState>(
    contextPath: String,
    private val contractStateClass: KClass<StateType>
) : QueryEndpoint<ContractState>, CordaptorComponent {

  private val nodeState: CordaNodeState by inject()

  override val responseType: Type = contractStateClass.java
  override val contextMappingParameters = ContextMappingParameters(contextPath, false)

  companion object {
    private val pathInfoPattern = Regex("""^/([A-Z0-9]+)\(([0-9]+)\)$""")

    private val logger = loggerFor<ContractStateQueryEndpoint<*>>()
  }

  override fun executeQuery(request: Request): Response<ContractState> {
    logger.debug("Parsing pathInfo {}", request.pathInfo)

    val match = pathInfoPattern.matchEntire(request.pathInfo!!)
        ?: throw BadOperationRequestException("Malformed pathInfo ${request.pathInfo}")

    val (hash, index) = match.destructured
    val stateRef = StateRef(SecureHash.parse(hash), index.toInt())

    logger.debug("Parsed stateRef parameter is {}", stateRef)

    val stateAndRef = nodeState.findStateByRef(stateRef, contractStateClass.java)
        ?: throw EndpointOperationException(
            message = "No such state with ref $stateRef",
            errorType = OperationErrorType.NOT_FOUND)

    return Response(stateAndRef.state.data)
  }
}

/**
 * Resolves REST API queries for specific transactions using a secure hash.
 */
class TransactionQueryEndpoint(contextPath: String)
  : QueryEndpoint<SignedTransaction>, CordaptorComponent {

  private val nodeState: CordaNodeState by inject()

  override val responseType = SignedTransaction::class.java
  override val contextMappingParameters = ContextMappingParameters(contextPath, false)

  override fun executeQuery(request: Request): Response<SignedTransaction> {
    val hash = SecureHash.parse(request.pathInfo!!.substring(1))

    val stx = nodeState.findTransactionByHash(hash)
        ?: throw EndpointOperationException(
            message = "No transaction with hash ${hash} in the vault",
            errorType = OperationErrorType.NOT_FOUND)

    return Response(stx)
  }
}

class VaultQueryHandler(contextPath: String) : ContextMappedHandler, AbstractHandler() {

  override val mappingParameters = ContextMappingParameters(contextPath, true)

  override fun handle(target: String?, baseRequest: JettyRequest?, request: HttpServletRequest?, response: HttpServletResponse?) {
    TODO("Not yet implemented")
  }

}

class CountingVaultQueryHandler(contextPath: String) : ContextMappedHandler, AbstractHandler() {

  override val mappingParameters = ContextMappingParameters(contextPath, true)

  override fun handle(target: String?, baseRequest: JettyRequest?, request: HttpServletRequest?, response: HttpServletResponse?) {
    TODO("Not yet implemented")
  }

}

class AggregatingVaultQueryHandler(contextPath: String) : ContextMappedHandler, AbstractHandler() {

  override val mappingParameters = ContextMappingParameters(contextPath, true)

  override fun handle(target: String?, baseRequest: JettyRequest?, request: HttpServletRequest?, response: HttpServletResponse?) {
    TODO("Not yet implemented")
  }

}