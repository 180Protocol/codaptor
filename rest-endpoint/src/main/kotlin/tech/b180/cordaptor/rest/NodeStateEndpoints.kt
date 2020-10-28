package tech.b180.cordaptor.rest

import io.reactivex.rxjava3.core.Single
import io.reactivex.rxjava3.subjects.ReplaySubject
import net.corda.core.contracts.ContractState
import net.corda.core.contracts.StateRef
import net.corda.core.crypto.SecureHash
import net.corda.core.flows.FlowLogic
import net.corda.core.identity.CordaX500Name
import net.corda.core.node.services.Vault
import net.corda.core.node.services.vault.DEFAULT_PAGE_SIZE
import net.corda.core.transactions.SignedTransaction
import org.koin.core.inject
import tech.b180.cordaptor.corda.*
import tech.b180.cordaptor.kernel.CordaptorComponent
import tech.b180.cordaptor.kernel.loggerFor
import tech.b180.cordaptor.shaded.javax.json.JsonObject
import java.util.*
import java.util.concurrent.TimeUnit
import javax.servlet.http.HttpServletResponse
import kotlin.math.min
import kotlin.reflect.KClass

/**
 * Factory class for specific Jetty handlers created for flows and contract states of CorDapps found on the node.
 */
class NodeStateAPIProvider(contextPath: String) : EndpointProvider, CordaptorComponent {

  private val nodeCatalog by inject<CordaNodeCatalog>()

  override val operationEndpoints: List<OperationEndpoint<*, *>>
  override val queryEndpoints: List<QueryEndpoint<*>>

  init {
    operationEndpoints = mutableListOf()
    queryEndpoints = mutableListOf()

    for (cordapp in nodeCatalog.cordapps) {
      for (flowInfo in cordapp.flows) {
        val handlerPath = "$contextPath/${cordapp.shortName}/${flowInfo.flowClass.simpleName}"
        @Suppress("UNCHECKED_CAST")
        val endpoint = FlowInitiationEndpoint(
            handlerPath, flowInfo.flowClass, flowInfo.flowResultClass as KClass<Any>)

        operationEndpoints.add(endpoint)
      }

      for (stateInfo in cordapp.contractStates) {
        val stateClass = stateInfo.stateClass
        val handlerPath = "$contextPath/${cordapp.shortName}/${stateClass.simpleName}"

        queryEndpoints.add(ContractStateRefQueryEndpoint(handlerPath, stateClass))
        operationEndpoints.add(ContractStateVaultQueryEndpoint("$handlerPath/query", stateClass))
      }
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
) : OperationEndpoint<CordaFlowInstruction<FlowLogic<FlowReturnType>>, CordaFlowSnapshot<FlowReturnType>>, CordaptorComponent,
    ContextMappedResourceEndpoint(contextPath, allowNullPathInfo = true) {

  companion object {
    private val logger = loggerFor<FlowInitiationEndpoint<*>>()

    /** Absolute maximum timeout for the request to avoid wasting server resources */
    const val MAX_SECONDS_TIMEOUT = 15 /* minutes */ * 60
  }

  private val cordaNodeState: CordaNodeState by inject()

  override val responseType = SerializerKey(CordaFlowSnapshot::class.java, flowResultClass.java).localType
  override val requestType = SerializerKey(CordaFlowInstruction::class.java, flowClass.java).localType
  override val supportedMethods = OperationEndpoint.POST_ONLY

  override fun executeOperation(
      request: RequestWithPayload<CordaFlowInstruction<FlowLogic<FlowReturnType>>>
  ): Single<Response<CordaFlowSnapshot<FlowReturnType>>> {

    val waitTimeout = request.getPositiveIntParameterValue("wait", 0)

    val flowInstruction = request.payload
    logger.debug("Initiating Corda flow using instruction {}", flowInstruction)

    val handle = cordaNodeState.initiateFlow(flowInstruction)
    logger.debug("Started flow {} with run id {} at {}",
        flowInstruction.flowClass.qualifiedName, handle.flowRunId, handle.startedAt)

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

      // timer for the wait timeout will complete after a delay
      val timer = Single.timer(waitTimeout.toLong(), TimeUnit.SECONDS).doOnSuccess {
        logger.debug("The wait for the flow {} to complete has timed out", handle.flowRunId)
      }

      val lastProgressUpdateBeforeTimeout = if (handle.flowProgressUpdates != null) {

        // this replay subject will only keep one last progress update
        val progressUpdates = ReplaySubject.createWithSize<CordaFlowProgress>(1)
        handle.flowProgressUpdates!!.subscribe(progressUpdates)

        // when timeout ends, progress update subject will be subscribed to and the last received item replayed
        Single.merge(timer.map {
          progressUpdates.take(1).singleOrError().map {
            handle.asSnapshotWithProgress(it)
          }
        })
      } else {
        // progress updates are not available, so return initial snapshot without progress or result after the timoout
        timer.map { handle.asInitialSnapshot() }
      }

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
class ContractStateRefQueryEndpoint<StateType: ContractState>(
    contextPath: String,
    private val contractStateClass: KClass<StateType>
) : QueryEndpoint<StateType>, ContextMappedResourceEndpoint(contextPath, allowNullPathInfo = false),
    CordaptorComponent {

  private val nodeState: CordaNodeState by inject()

  companion object {
    private val pathInfoPattern = Regex("""^/([A-Z0-9]+)\(([0-9]+)\)$""")

    private val logger = loggerFor<ContractStateRefQueryEndpoint<*>>()
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
              operationId = "get${contractStateClass.simpleName}ByRef"
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
 * Allows flexible querying of the node vault for states. Note that both GET and POST
 * methods are supported, so the endpoint is both a [QueryEndpoint] and an [OperationEndpoint].
 *
 * This is a strongly-typed API endpoint allowing full JSON schema to be generated.
 *
 * This class uses type parameters to reduce the chance of introducing any type-related bugs
 * in the implementation code. However, this class is not instantiated with type parameters.
 */
class ContractStateVaultQueryEndpoint<StateType: ContractState>(
    contextPath: String,
    private val contractStateClass: KClass<StateType>
) : QueryEndpoint<CordaVaultPage<StateType>>,
    OperationEndpoint<CordaVaultQuery<StateType>, CordaVaultPage<StateType>>,
    ContextMappedResourceEndpoint(contextPath, allowNullPathInfo = true),
    CordaptorComponent {

  companion object {
    private val logger = loggerFor<ContractStateVaultQueryEndpoint<*>>()

    const val PARAMETER_NAME_PAGE_NUMBER = "pageNumber"
    const val PARAMETER_NAME_PAGE_SIZE = "pageSize"
    const val PARAMETER_NAME_CONSUMED = "consumed"
    const val PARAMETER_NAME_EXTERNAL_ID = "externalId"
    const val PARAMETER_NAME_UUID = "uuid"
    const val PARAMETER_NAME_PARTICIPANT = "participant"
    const val PARAMETER_NAME_OWNER = "owner"
    const val PARAMETER_NAME_NOTARY = "notary"

    /** JSON Schema object for parameter [PARAMETER_NAME_CONSUMED] */
    private val CONSUMED_PARAMETER_SCHEMA: JsonObject = mapOf(
        "type" to "string",
        "enum" to listOf("ignore", "include", "only")
    ).asJsonObject()
  }

  private val nodeState: CordaNodeState by inject()

  override val requestType = SerializerKey(CordaVaultQuery::class.java, contractStateClass.java).localType
  override val responseType = SerializerKey(CordaVaultPage::class.java, contractStateClass.java).localType
  override val supportedMethods = listOf("GET", "POST")

  // GET form of query supports a limited subset of criteria
  override fun executeQuery(request: Request): Response<CordaVaultPage<StateType>> {
    val query = buildQueryFromRequestParameters(request)
    logger.debug("Performing vault query: {}", query)

    val page = nodeState.queryStates(query)
    logger.debug("Vault query returned page with {} states, {} available in total",
        page.states.size, page.totalStatesAvailable)

    return Response(page)
  }

  // POST form of query caters for more complicated query scenarios
  override fun executeOperation(
      request: RequestWithPayload<CordaVaultQuery<StateType>>
  ): Single<Response<CordaVaultPage<StateType>>> {

    val query = request.payload
    logger.debug("Performing vault query: {}", query)

    val page = nodeState.queryStates(query)
    logger.debug("Vault query returned page with {} states, {} available in total",
        page.states.size, page.totalStatesAvailable)

    // query is performed synchronously above, so resolve straight away
    return Single.just(Response(page))
  }

  private fun buildQueryFromRequestParameters(request: Request): CordaVaultQuery<StateType> {
    logger.debug("Building vault query from HTTP GET query parameters: {}", request.queryParameters)

    val pageNumber = request.getPositiveIntParameterValue(PARAMETER_NAME_PAGE_NUMBER, 0)
    val pageSize = request.getPositiveIntParameterValue(PARAMETER_NAME_PAGE_SIZE, DEFAULT_PAGE_SIZE)

    val stateStatus = when (request.getParameterValue(PARAMETER_NAME_CONSUMED)?.toLowerCase()) {
      null -> Vault.StateStatus.UNCONSUMED    // default value
      "ignore" -> Vault.StateStatus.UNCONSUMED
      "include" -> Vault.StateStatus.ALL
      "only" -> Vault.StateStatus.CONSUMED
      else -> throw BadOperationRequestException("Invalid value", parameterName = PARAMETER_NAME_CONSUMED)
    }

    val externalIds = request.getAllParameterValues(PARAMETER_NAME_EXTERNAL_ID)
    val uuids = request.getAllParameterValues(PARAMETER_NAME_UUID)?.map {
      try {
        UUID.fromString(it)
      } catch (e: IllegalArgumentException) {
        throw BadOperationRequestException("Malformed UUID", parameterName = PARAMETER_NAME_UUID, cause = e)
      }
    }

    val participantNames = getX500NamesFromRequestParameter(request, PARAMETER_NAME_PARTICIPANT)
    val ownerNames = getX500NamesFromRequestParameter(request, PARAMETER_NAME_OWNER)
    val notaryNames = getX500NamesFromRequestParameter(request, PARAMETER_NAME_NOTARY)

    return CordaVaultQuery(
        contractStateClass = contractStateClass,
        pageNumber = pageNumber,
        pageSize = pageSize,
        stateStatus = stateStatus,
        linearStateExternalIds = externalIds,
        linearStateUUIDs = uuids,
        participantNames = participantNames,
        ownerNames = ownerNames,
        notaryNames = notaryNames
    )
  }

  private fun getX500NamesFromRequestParameter(request: Request, paramName: String): List<CordaX500Name>? {
    try {
      return request.getAllParameterValues(paramName)?.map { CordaX500Name.parse(it) }
    } catch (e: IllegalArgumentException) {
      throw BadOperationRequestException("Malformed X500 name", parameterName = paramName, cause = e)
    }
  }

  override fun generatePathInfoSpecification(schemaGenerator: JsonSchemaGenerator): OpenAPI.PathItem =
      OpenAPI.PathItem(
          post = OpenAPI.Operation(
              summary = "Performs a query of the vault of the underlying Corda node with a complex criteria",
              operationId = "query${contractStateClass.simpleName}Instances"
          ).withRequestBody(OpenAPI.RequestBody.createJsonRequest(
              schema = schemaGenerator.generateSchema(SerializerKey.forType(requestType)),
              required = true)
          ).withResponse(OpenAPI.HttpStatusCode.OK, OpenAPI.Response.createJsonResponse(
              description = "Query ran successfully",
              schema = schemaGenerator.generateSchema(SerializerKey.forType(responseType)))
          ),
          get = OpenAPI.Operation(
              summary = "Performs a query of the vault of the underlying Corda node with a simplified criteria",
              operationId = "fetch${contractStateClass.simpleName}Instances"
          ).withParameter(OpenAPI.Parameter(name = PARAMETER_NAME_PAGE_NUMBER, `in` = OpenAPI.ParameterLocation.QUERY,
              description = "Zero-based index of the page to return (0 by default)", required = false,
              schema = OpenAPI.PrimitiveTypes.POSITIVE_INTEGER)
          ).withParameter(OpenAPI.Parameter(name = PARAMETER_NAME_PAGE_SIZE, `in` = OpenAPI.ParameterLocation.QUERY,
              description = "Size of the page to return ($DEFAULT_PAGE_SIZE by default)", required = false,
              schema = OpenAPI.PrimitiveTypes.POSITIVE_INTEGER)
          ).withParameter(OpenAPI.Parameter(name = PARAMETER_NAME_CONSUMED, `in` = OpenAPI.ParameterLocation.QUERY,
              description = "Approach to querying consumed states (ignore by default)", required = false,
              schema = CONSUMED_PARAMETER_SCHEMA)
          ).withParameter(OpenAPI.Parameter(name = PARAMETER_NAME_EXTERNAL_ID, `in` = OpenAPI.ParameterLocation.QUERY,
              description = "One or more external keys used in unique identifiers of linear states",
              required = false, explode = true,
              schema = OpenAPI.PrimitiveTypes.NON_EMPTY_STRING)
          ).withParameter(OpenAPI.Parameter(name = PARAMETER_NAME_UUID, `in` = OpenAPI.ParameterLocation.QUERY,
              description = "One or more UUIDs used in unique identifiers of linear states",
              required = false, explode = true,
              schema = OpenAPI.PrimitiveTypes.UUID_STRING)
          ).withParameter(OpenAPI.Parameter(name = PARAMETER_NAME_PARTICIPANT, `in` = OpenAPI.ParameterLocation.QUERY,
              description = "One or more of X500 names of participants in a state",
              required = false, explode = true,
              schema = OpenAPI.PrimitiveTypes.NON_EMPTY_STRING)
          ).withParameter(OpenAPI.Parameter(name = PARAMETER_NAME_OWNER, `in` = OpenAPI.ParameterLocation.QUERY,
              description = "One or more of X500 names of owning parties for ownable states",
              required = false, explode = true,
              schema = OpenAPI.PrimitiveTypes.NON_EMPTY_STRING)
          ).withParameter(OpenAPI.Parameter(name = PARAMETER_NAME_NOTARY, `in` = OpenAPI.ParameterLocation.QUERY,
              description = "One or more of X500 names of notarizing parties for a state",
              required = false, explode = true,
              schema = OpenAPI.PrimitiveTypes.NON_EMPTY_STRING)
          ).withResponse(OpenAPI.HttpStatusCode.OK, OpenAPI.Response.createJsonResponse(
              description = "Query ran successfully",
              schema = schemaGenerator.generateSchema(SerializerKey.forType(responseType)))
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
            message = "No transaction with hash $hash in the vault",
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
