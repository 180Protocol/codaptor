package tech.b180.cordaptor.rest

import io.reactivex.rxjava3.core.Single
import io.reactivex.rxjava3.subjects.ReplaySubject
import net.corda.core.contracts.ContractState
import net.corda.core.contracts.StateRef
import net.corda.core.crypto.SecureHash
import net.corda.core.flows.FlowLogic
import net.corda.core.node.services.Vault
import net.corda.core.transactions.SignedTransaction
import org.koin.core.inject
import tech.b180.cordaptor.corda.CordaFlowProgress
import tech.b180.cordaptor.corda.CordaFlowSnapshot
import tech.b180.cordaptor.corda.CordaNodeCatalog
import tech.b180.cordaptor.corda.CordaNodeState
import tech.b180.cordaptor.kernel.CordaptorComponent
import tech.b180.cordaptor.kernel.loggerFor
import java.util.concurrent.TimeUnit
import javax.servlet.http.HttpServletResponse
import kotlin.math.min
import kotlin.reflect.KClass
import kotlin.text.Regex
import kotlin.text.toInt

/**
 * Factory class for specific Jetty handlers created for flows and contract states of CorDapps found on the node.
 */
class NodeStateAPIProvider(private val contextPath: String) : EndpointProvider, CordaptorComponent {

  private val nodeCatalog by inject<CordaNodeCatalog>()

  override val operationEndpoints: List<OperationEndpoint<*, *>>
  override val queryEndpoints: List<QueryEndpoint<*>>

  init {
    operationEndpoints = nodeCatalog.cordapps
        .flatMap { cordapp -> cordapp.flows.map { it to cordapp } }
        .map { (flowInfo, cordapp) ->
          val handlerPath = "$contextPath/${cordapp.shortName}/${flowInfo.flowClass.simpleName}"
          @Suppress("UNCHECKED_CAST")
          FlowInitiationEndpoint(handlerPath, flowInfo.flowClass, flowInfo.flowResultClass as KClass<Any>)
        }

    queryEndpoints = nodeCatalog.cordapps
        .flatMap { cordapp -> cordapp.contractStates.map { it to cordapp } }
        .map { (stateInfo, cordapp) ->
          val handlerPath = "$contextPath/${cordapp.shortName}/${stateInfo.stateClass.simpleName}"
          ContractStateQueryEndpoint(handlerPath, stateInfo.stateClass)
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
    private val flowClass: KClass<out FlowLogic<FlowReturnType>>,
    flowResultClass: KClass<FlowReturnType>
) : OperationEndpoint<FlowLogic<FlowReturnType>, CordaFlowSnapshot<FlowReturnType>>, CordaptorComponent,
    ContextMappedResourceEndpoint(contextPath, allowNullPathInfo = true) {

  companion object {
    private val logger = loggerFor<FlowInitiationEndpoint<*>>()

    /** Absolute maximum timeout for the request to avoid wasting server resources */
    const val MAX_SECONDS_TIMEOUT = 15 /* minutes */ * 60
  }

  private val cordaNodeState: CordaNodeState by inject()

  override val responseType = CordaFlowSnapshot::class.asParameterizedType(flowResultClass)
  override val requestType = flowClass.java
  override val supportedMethods = OperationEndpoint.POST_ONLY

  override fun executeOperation(
      request: RequestWithPayload<FlowLogic<FlowReturnType>>): Single<Response<CordaFlowSnapshot<FlowReturnType>>> {

    val waitTimeout = request.getPositiveIntParameterValue("wait", 0)

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

  override fun generatePathInfoSpecification(schemaGenerator: JsonSchemaGenerator): OpenAPI.PathItem =
      OpenAPI.PathItem(
          post = OpenAPI.Operation(
              summary = "Initiates and tracks execution of Corda flow ${flowClass.simpleName} with given parameters",
              operationId = "initiate${flowClass.simpleName}"
          ).withRequestBody(
              OpenAPI.RequestBody.createJsonRequest(
                  schemaGenerator.generateSchema(SerializerKey.forType(requestType)),
                  required = true)
          ).withParameter(OpenAPI.Parameter(name = "wait", `in` = OpenAPI.ParameterLocation.QUERY,
              description = "Timeout for synchronous flow execution, 0 for immediate return", required = false,
              schema = OpenAPI.PrimitiveTypes.POSITIVE_INTEGER)
          ).withResponse(
              OpenAPI.HttpStatusCode.OK,
              OpenAPI.Response.createJsonResponse(
                  description = "Flow execution completed successfully and its result is available",
                  schema = schemaGenerator.generateSchema(SerializerKey.forType(responseType)))
          ).withResponse(
              OpenAPI.HttpStatusCode.ACCEPTED,
              OpenAPI.Response.createJsonResponse(
                  description = "Flow execution stared and its outcome is not yet available",
                  schema = schemaGenerator.generateSchema(SerializerKey.forType(responseType)))
          ).withResponse(
              OpenAPI.HttpStatusCode.INTERNAL_SERVER_ERROR,
              OpenAPI.Response.createJsonResponse(
                  description = "Flow execution failed and error information is available",
                  schema = schemaGenerator.generateSchema(SerializerKey.forType(responseType)))
          )
      )
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
) : QueryEndpoint<StateType>, ContextMappedResourceEndpoint(contextPath, allowNullPathInfo = false),
    CordaptorComponent {

  private val nodeState: CordaNodeState by inject()

  companion object {
    private val pathInfoPattern = Regex("""^/([A-Z0-9]+)\(([0-9]+)\)$""")

    private val logger = loggerFor<ContractStateQueryEndpoint<*>>()
  }

  override val responseType = contractStateClass.java

  override val resourcePath = "$contextPath/{hash}({index})"

  override fun executeQuery(request: Request): Response<StateType> {
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

  override fun generatePathInfoSpecification(schemaGenerator: JsonSchemaGenerator): OpenAPI.PathItem =
      OpenAPI.PathItem(
          get = OpenAPI.Operation(
              summary = "Finds contract state recorded to the ledger with a given hash and index ",
              operationId = "findContractState"
          ).withParameter(OpenAPI.Parameter(name = "hash", `in` = OpenAPI.ParameterLocation.PATH,
              description = "Transaction hash value", required = true,
              schema = schemaGenerator.generateSchema(SerializerKey(SecureHash::class)))
          ).withParameter(OpenAPI.Parameter(name = "index", `in` = OpenAPI.ParameterLocation.PATH,
              description = "Transaction output index", required = true,
              schema = schemaGenerator.generateSchema(SerializerKey(Int::class)))
          ).withResponse(OpenAPI.HttpStatusCode.OK, OpenAPI.Response.createJsonResponse(
              description = "Successfully retrieved contract state",
              schema = schemaGenerator.generateSchema(SerializerKey.forType(responseType)))
          ).withResponse(OpenAPI.HttpStatusCode.NOT_FOUND, OpenAPI.Response(
              description = "Contract state with given hash and index was not found")
          )
      )
}

/**
 * Resolves REST API queries for specific transactions using a secure hash.
 */
class TransactionQueryEndpoint(contextPath: String)
  : ContextMappedQueryEndpoint<SignedTransaction>(contextPath, allowNullPathInfo = false), CordaptorComponent {

  companion object {
    private val pathInfoPattern = Regex("""^/([A-Z0-9]+)$""")

    private val logger = loggerFor<TransactionQueryEndpoint>()
  }

  private val nodeState: CordaNodeState by inject()

  override val resourcePath = "$contextPath/{hash}"

  override fun executeQuery(request: Request): Response<SignedTransaction> {
    logger.debug("Parsing pathInfo {}", request.pathInfo)

    val match = pathInfoPattern.matchEntire(request.pathInfo!!)
        ?: throw BadOperationRequestException("Malformed pathInfo ${request.pathInfo}")

    val (hash) = match.destructured

    val stx = nodeState.findTransactionByHash(SecureHash.parse(hash))
        ?: throw EndpointOperationException(
            message = "No transaction with hash ${hash} in the vault",
            errorType = OperationErrorType.NOT_FOUND)

    return Response(stx)
  }

  override fun generatePathInfoSpecification(schemaGenerator: JsonSchemaGenerator): OpenAPI.PathItem =
      OpenAPI.PathItem(
          get = OpenAPI.Operation(
              summary = "Finds a transaction recorded to the ledger with a given hash value",
              operationId = "findTransactionByHash"
          ).withParameter(OpenAPI.Parameter(name = "hash", `in` = OpenAPI.ParameterLocation.PATH,
              description = "Transaction hash value", required = true,
              schema = schemaGenerator.generateSchema(SerializerKey(SecureHash::class)))
          ).withResponse(OpenAPI.HttpStatusCode.OK, OpenAPI.Response.createJsonResponse(
              description = "Successfully retrieved transaction",
              schema = schemaGenerator.generateSchema(SerializerKey.forType(responseType)))
          ).withResponse(OpenAPI.HttpStatusCode.NOT_FOUND, OpenAPI.Response(
              description = "Transaction with given hash value was not found")
          )
      )
}

/**
 * Allows flexible querying of the node vault for states.
 */
class VaultQueryEndpoint(contextPath: String)
  : ContextMappedQueryEndpoint<List<ContractState>>(contextPath, allowNullPathInfo = true), CordaptorComponent {

  override fun executeQuery(request: Request): Response<List<ContractState>> {
    TODO("Not yet implemented")
  }

  override fun generatePathInfoSpecification(schemaGenerator: JsonSchemaGenerator): OpenAPI.PathItem =
      OpenAPI.PathItem(
          get = OpenAPI.Operation(
              summary = "Performs a query of the vault of the underlying Corda node",
              operationId = "queryVault"
          )
      )
}
