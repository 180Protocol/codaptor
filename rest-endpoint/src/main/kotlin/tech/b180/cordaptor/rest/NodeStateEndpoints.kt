package tech.b180.cordaptor.rest

import io.reactivex.rxjava3.core.BackpressureStrategy
import io.reactivex.rxjava3.core.Single
import io.reactivex.rxjava3.subjects.ReplaySubject
import net.corda.core.contracts.ContractState
import net.corda.core.contracts.StateRef
import net.corda.core.crypto.SecureHash
import net.corda.core.flows.FlowLogic
import net.corda.core.node.services.Vault
import net.corda.core.transactions.SignedTransaction
import org.koin.core.get
import org.koin.core.inject
import org.koin.core.parameter.parametersOf
import tech.b180.cordaptor.corda.CordaFlowProgress
import tech.b180.cordaptor.corda.CordaFlowSnapshot
import tech.b180.cordaptor.corda.CordaNodeCatalog
import tech.b180.cordaptor.corda.CordaNodeState
import tech.b180.cordaptor.kernel.CordaptorComponent
import tech.b180.cordaptor.kernel.loggerFor
import java.lang.reflect.Type
import java.util.concurrent.TimeUnit
import javax.servlet.http.HttpServletResponse
import kotlin.math.min
import kotlin.reflect.KClass

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
 * This class uses type parameters to reduce the chance of introducing any type-related bugs
 * in the implementation code. However, this class is not instantiated with type parameters.
 */
class FlowInitiationEndpoint<FlowReturnType: Any>(
    contextPath: String,
    flowClass: KClass<out FlowLogic<FlowReturnType>>,
    flowResultClass: KClass<FlowReturnType>
) : OperationEndpoint<FlowLogic<FlowReturnType>, CordaFlowSnapshot<FlowReturnType>>, CordaptorComponent {

  companion object {
    private val logger = loggerFor<FlowInitiationEndpoint<*>>()

    /** Absolute maximum timeout for the request to avoid wasting server resources */
    const val MAX_SECONDS_TIMEOUT = 15 /* minutes */ * 60
  }

  private val cordaNodeState: CordaNodeState by inject()

  override val responseType = CordaFlowSnapshot::class.asParameterizedType(flowResultClass)
  override val contextMappingParameters = ContextMappingParameters(contextPath, true)
  override val requestType = flowClass.java
  override val supportedMethods = OperationEndpoint.POST_ONLY

  override fun executeOperation(
      request: RequestWithPayload<FlowLogic<FlowReturnType>>): Single<Response<CordaFlowSnapshot<FlowReturnType>>> {

    val waitTimeout = request.getPositiveIntParameter("wait", 0)

    val flowInstance = request.payload
    logger.debug("Initiating Corda flow {}", flowInstance)
    val handle = cordaNodeState.initiateFlow(flowInstance)
    logger.debug("Started flow {} with run id {} at {}", flowInstance, handle.flowRunId, handle.startedAt)

    if (waitTimeout == 0) {
      logger.debug("Zero timeout specified, returning result straight away")

      val snapshot = handle.asInitialSnapshot()

      return Single.just(Response(snapshot, statusCode = HttpServletResponse.SC_ACCEPTED))
    } else {
      val effectiveTimeout = min(waitTimeout, MAX_SECONDS_TIMEOUT)
      logger.debug("Effective timeout for the flow {}", effectiveTimeout)

      val resultPromise = handle.flowResultPromise.map {
        logger.debug("Received result from flow {} while waiting: {}", handle.flowRunId, it)
        handle.asSnapshotWithResult(it)
      }

      // this replay subject will only keep one last progress update
      val progressUpdates = ReplaySubject.createWithSize<CordaFlowProgress>(1)
      handle.flowProgressUpdates.subscribe(progressUpdates)

      // when timeout ends, last progress update will be replayed
      val lastProgressUpdateBeforeTimeout = Single.merge(
          Single.timer(waitTimeout.toLong(), TimeUnit.SECONDS).doOnSuccess {
            logger.debug("The wait for the flow {} to complete has timed out", handle.flowRunId)
          }.map { progressUpdates.take(1).singleOrError() })
          .map { handle.asSnapshotWithProgress(it) }

      // race between the result and the timeout
      return resultPromise.ambWith(lastProgressUpdateBeforeTimeout).map { snapshot ->
        logger.debug("The wait for the flow {} to complete ended with snapshot {}", handle.flowRunId, snapshot)

        val statusCode = when {
          snapshot.result == null -> {
            // flow is still active
            HttpServletResponse.SC_ACCEPTED
          }
          snapshot.result!!.isError -> {
            // flow terminated with an error
            HttpServletResponse.SC_INTERNAL_SERVER_ERROR
          }
          else -> {
            // flow completed and produced a result
            HttpServletResponse.SC_OK
          }
        }
        Response(snapshot, statusCode)
      }
    }
  }
}

/**
 * Resolves REST API queries for specific contract state using a stateRef.
 *
 * This class uses type parameters to reduce the chance of introducing any type-related bugs
 * in the implementation code. However, this class is not instantiated with type parameters.
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

    // we are interested in consumed states too, as this is a permanent URL
    val stateAndRef = nodeState.findStateByRef(stateRef, contractStateClass.java, Vault.StateStatus.ALL)
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

/**
 * Allows flexible querying of the node vault for states.
 */
class VaultQueryEndpoint(contextPath: String)
  : QueryEndpoint<List<ContractState>>, CordaptorComponent {

  override val responseType = List::class.asParameterizedType(ContractState::class)
  override val contextMappingParameters = ContextMappingParameters(contextPath, true)

  override fun executeQuery(request: Request): Response<List<ContractState>> {
    TODO("Not yet implemented")
  }
}
