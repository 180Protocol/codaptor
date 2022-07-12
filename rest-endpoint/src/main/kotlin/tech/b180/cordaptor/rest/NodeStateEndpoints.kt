package tech.b180.cordaptor.rest

import com.google.common.net.HttpHeaders
import io.reactivex.rxjava3.core.Single
import io.reactivex.rxjava3.subjects.ReplaySubject
import io.undertow.util.StatusCodes
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
import kotlin.math.min
import kotlin.reflect.KClass

const val FLOW_INITIATION_TAG = "flowInitiation"
const val VAULT_QUERY_TAG = "vaultQuery"
const val NODE_ATTACHMENT_TAG = "nodeAttachment"

/**
 * Factory class for specific Jetty handlers created for flows and contract states of CorDapps found on the node.
 */
class NodeStateAPIProvider(contextPath: String) : EndpointProvider, CordaptorComponent {

  companion object {
    private val logger = loggerFor<NodeStateAPIProvider>()
  }

  private val nodeCatalog by inject<CordaNodeCatalog>()
  private val settings by inject<Settings>()

  override val operationEndpoints: List<OperationEndpoint<*, *>>
  override val queryEndpoints: List<QueryEndpoint<*>>

  init {
    val flowSnapshotsEnabled = settings.isFlowSnapshotsEndpointEnabled
    val nodeAttachmentEndpointEnabled = settings.isNodeAttachmentEndpointEnabled
    if (!flowSnapshotsEnabled) {
      logger.info("Flow snapshots endpoint is disabled. Flow initiation operations will never return a Location header")
    }

    if (!nodeAttachmentEndpointEnabled) {
      logger.info("Node Attachment endpoint is disabled.")
    }

    operationEndpoints = mutableListOf()
    queryEndpoints = mutableListOf()

    for (cordapp in nodeCatalog.cordapps) {

      if(nodeAttachmentEndpointEnabled){
        val handlerPath = "$contextPath/uploadNodeAttachment"
        operationEndpoints.add(NodeAttachmentEndpoint(handlerPath))
      }

      for (flowInfo in cordapp.flows) {
        val handlerPath = "$contextPath/${cordapp.shortName}/${flowInfo.flowClass.simpleName}"

        operationEndpoints.add(FlowInitiationEndpoint<Any>(handlerPath, flowInfo.flowClass, flowInfo.flowResultClass))

        if (settings.isFlowSnapshotsEndpointEnabled) {
          queryEndpoints.add(FlowSnapshotsEndpoint(
              "$handlerPath/snapshot", flowInfo.flowClass, flowInfo.flowResultClass))
        }
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
    private val flowClass: KClass<out FlowLogic<Any>>,
    flowResultClass: KClass<out Any>
) : OperationEndpoint<CordaFlowInstruction<FlowLogic<FlowReturnType>>, CordaFlowSnapshot<FlowReturnType>>, CordaptorComponent,
    ContextMappedResourceEndpoint(contextPath, true) {

  companion object {
    private val logger = loggerFor<FlowInitiationEndpoint<*>>()
  }

  private val cordaNodeState: CordaNodeState by inject()
  private val settings: Settings by inject()
  private val urlBuilder: URLBuilder by inject()

  override val responseType = SerializerKey(CordaFlowSnapshot::class, flowResultClass)
  override val requestType = SerializerKey(CordaFlowInstruction::class, flowClass)
  override val supportedMethods = OperationEndpoint.POST_ONLY

  override fun executeOperation(
      request: RequestWithPayload<CordaFlowInstruction<FlowLogic<FlowReturnType>>>
  ): Single<Response<CordaFlowSnapshot<FlowReturnType>>> {

    if (!request.subject.isPermitted(OPERATION_INITIATE_FLOW, flowClass.qualifiedName)) {
      throw UnauthorizedOperationException(OPERATION_INITIATE_FLOW)
    }

    val waitTimeout = request.getPositiveIntParameterValue("wait", 0)

    val flowInstruction = request.payload
    logger.debug("Initiating Corda flow using instruction {}", flowInstruction)

    val handle = cordaNodeState.initiateFlow(flowInstruction)
    logger.debug("Started flow {} with run id {} at {}",
        flowInstruction.flowClass.qualifiedName, handle.flowRunId, handle.startedAt)

    val headers = if (settings.isFlowSnapshotsEndpointEnabled) {
      listOf(Response.Header(HttpHeaders.LOCATION,
          urlBuilder.toAbsoluteUrl("$path/snapshot/${handle.flowRunId}")))
    } else {
      emptyList()
    }

    if (waitTimeout == 0) {
      logger.debug("Zero timeout specified, returning result straight away")

      val snapshot = handle.asInitialSnapshot()

      return Single.just(Response(snapshot, StatusCodes.ACCEPTED, headers))
    } else {
      val effectiveTimeout = min(waitTimeout, settings.maxFlowInitiationTimeout.seconds.toInt())
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
            StatusCodes.ACCEPTED
          }
          snapshot.result!!.isError -> {
            // flow terminated with an error
            StatusCodes.INTERNAL_SERVER_ERROR
          }
          else -> {
            // flow completed and produced a result
            StatusCodes.OK
          }
        }
        Response(snapshot, statusCode, headers)
      }
    }
  }

  override fun generatePathInfoSpecification(schemaGenerator: JsonSchemaGenerator): OpenAPI.PathItem =
      OpenAPI.PathItem(
          post = OpenAPI.Operation(
              summary = "Initiates and tracks execution of Corda flow ${flowClass.simpleName} with given parameters",
              operationId = "initiate${flowClass.simpleName}"
          ).withRequestBody(
            OpenAPI.RequestBody.createMultiMediaTypeRequest(
              mapOf(OpenAPI.JSON_CONTENT_TYPE to OpenAPI.MediaType(schemaGenerator.generateSchema(requestType)),
                OpenAPI.MULTI_PART_FORM_DATA_CONTENT_TYPE to OpenAPI.MediaType(schemaGenerator.generateSchema(requestType))),
              required = true)
          ).withParameter(OpenAPI.Parameter(name = "wait", `in` = OpenAPI.ParameterLocation.QUERY,
              description = "Timeout for synchronous flow execution, 0 for immediate return", required = false,
              schema = OpenAPI.PrimitiveTypes.POSITIVE_INTEGER)
          ).withResponse(
              OpenAPI.HttpStatusCode.OK,
              OpenAPI.Response.createJsonResponse(
                  description = "Flow execution completed successfully and its result is available",
                  schema = schemaGenerator.generateSchema(responseType)
              ).withHeader(
                  HttpHeaders.LOCATION to OpenAPI.Header(
                      description = "URL from which to obtain latest snapshot of the flow",
                      schema = OpenAPI.PrimitiveTypes.URL_STRING)
              )
          ).withResponse(
              OpenAPI.HttpStatusCode.ACCEPTED,
              OpenAPI.Response.createJsonResponse(
                  description = "Flow execution stared and its outcome is not yet available",
                  schema = schemaGenerator.generateSchema(responseType)
              ).withHeader(
                  HttpHeaders.LOCATION to OpenAPI.Header(
                      description = "URL from which to obtain latest snapshot of the flow",
                      schema = OpenAPI.PrimitiveTypes.URL_STRING)
              )
          ).withResponse(
              OpenAPI.HttpStatusCode.INTERNAL_SERVER_ERROR,
              OpenAPI.Response.createJsonResponse(
                  description = "Flow execution failed and error information is available",
                  schema = schemaGenerator.generateSchema(responseType)
              ).withHeader(
                  HttpHeaders.LOCATION to OpenAPI.Header(
                      description = "URL from which to obtain latest snapshot of the flow",
                      schema = OpenAPI.PrimitiveTypes.URL_STRING)
              )
          ).withForbiddenResponse().withTags(FLOW_INITIATION_TAG)
      )
}

/**
 * API endpoint handler allowing to upload attachment on corda node.
 */
class NodeAttachmentEndpoint(
    contextPath: String
) : OperationEndpoint<CordaNodeAttachment, SecureHash>, CordaptorComponent,
    ContextMappedResourceEndpoint(contextPath, true) {

    companion object {
        private val logger = loggerFor<NodeAttachmentEndpoint>()
    }

    private val cordaNodeState: CordaNodeState by inject()

    override val responseType = SerializerKey(SecureHash::class)
    override val requestType = SerializerKey(CordaNodeAttachment::class)
    override val supportedMethods = OperationEndpoint.POST_ONLY

    override fun executeOperation(
        request: RequestWithPayload<CordaNodeAttachment>
    ): Single<Response<SecureHash>> {
      if (!request.subject.isPermitted(OPERATION_UPLOAD_NODE_ATTACHMENT)) {
        throw UnauthorizedOperationException(OPERATION_UPLOAD_NODE_ATTACHMENT)
      }

      val attachmentInstruction = request.payload
      logger.debug("Attachment instruction {}", attachmentInstruction)

      val handle = cordaNodeState.createAttachment(attachment = attachmentInstruction)
      return Single.just(Response(handle, StatusCodes.OK, emptyList()))
    }

    override fun generatePathInfoSpecification(schemaGenerator: JsonSchemaGenerator): OpenAPI.PathItem {
      return OpenAPI.PathItem(
          post = OpenAPI.Operation(
              summary = "Uploads Corda attachment with given parameters",
              operationId = "uploadNodeAttachment"
          ).withRequestBody(
              OpenAPI.RequestBody.createMultiPartFormDataRequest(
                  schemaGenerator.generateSchema(requestType),
                  required = true
              )
          ).withResponse(
              OpenAPI.HttpStatusCode.OK,
              OpenAPI.Response.createJsonResponse(
                  description = "Attachment uploaded successfully and its result is available",
                  schema = schemaGenerator.generateSchema(responseType)
              )
          ).withForbiddenResponse().withTags(NODE_ATTACHMENT_TAG)
      )
    }
}

/**
 * API endpoint handler allowing to retrieve latest snapshot of a flow by its run id.
 * This call will fail to instantiate if no module implementing flow results cache is present.
 *
 * This class uses type parameters to reduce the chance of introducing any type-related bugs
 * in the implementation code. However, this class is not instantiated with type parameters.
 */
class FlowSnapshotsEndpoint<FlowReturnType: Any>(
    contextPath: String,
    private val flowClass: KClass<out FlowLogic<FlowReturnType>>,
    flowResultClass: KClass<out FlowReturnType>
) : QueryEndpoint<CordaFlowSnapshot<FlowReturnType>>, CordaptorComponent,
    ContextMappedResourceEndpoint(contextPath, false) {

  companion object {
    private val pathInfoPattern = Regex("""^/([0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})$""")

    private val logger = loggerFor<FlowSnapshotsEndpoint<*>>()
  }

  private val snapshotsCache: CordaFlowSnapshotsCache by inject()

  override val resourcePath = "$contextPath/{flowRunId}"
  override val responseType = SerializerKey(CordaFlowSnapshot::class, flowResultClass)

  override fun executeQuery(request: Request): Response<CordaFlowSnapshot<FlowReturnType>> {
    if (!request.subject.isPermitted(OPERATION_GET_FLOW_SNAPSHOT, flowClass.qualifiedName)) {
      throw UnauthorizedOperationException(OPERATION_GET_FLOW_SNAPSHOT)
    }

    logger.debug("Parsing relativePath {}", request.relativePath)

    val match = pathInfoPattern.matchEntire(request.relativePath.toLowerCase())
        ?: throw BadOperationRequestException("Malformed relativePath ${request.relativePath}")

    val runId = UUID.fromString(match.groupValues[1])

    logger.debug("Parsed flowRunId parameter is {}", runId)

    return try {
      val snapshot = snapshotsCache.getFlowSnapshot(flowClass, runId)
      if (snapshot == null) {
        // this will not happen in the current local cache implementation
        logger.debug("Flow snapshots for run id {} is no longer available", runId)
        Response(null, StatusCodes.GONE)
      } else {
        logger.debug("Flow snapshots for run id {} was found: {}", runId, snapshot)
        Response(snapshot, StatusCodes.OK)
      }
    } catch (e: NoSuchElementException) {
      logger.debug("Flow id {} was not found in the snapshots cache", runId)
      Response(null, StatusCodes.NOT_FOUND)
    }
  }

  override fun generatePathInfoSpecification(schemaGenerator: JsonSchemaGenerator): OpenAPI.PathItem =
    OpenAPI.PathItem(
        get = OpenAPI.Operation(
            summary = "Returns latest snapshot for a flow of type ${flowClass.simpleName} initiated with given run id",
            operationId = "getLatestSnapshotFor${flowClass.simpleName}"
        ).withParameter(OpenAPI.Parameter(name = "flowRunId", `in` = OpenAPI.ParameterLocation.PATH,
            description = "Flow run id returned by a flow initiation operation", required = true,
            schema = OpenAPI.PrimitiveTypes.UUID_STRING)
        ).withResponse(
            OpenAPI.HttpStatusCode.OK,
            OpenAPI.Response.createJsonResponse(
                description = "Latest snapshot of the flow with given run id",
                schema = schemaGenerator.generateSchema(responseType))
        ).withResponse(
            OpenAPI.HttpStatusCode.NOT_FOUND,
            OpenAPI.Response("Snapshot of a flow with given run id was not found, " +
                "which may mean it was already evicted")
        ).withForbiddenResponse().withTags(FLOW_INITIATION_TAG)
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
) : QueryEndpoint<StateType>, ContextMappedResourceEndpoint(contextPath, false),
    CordaptorComponent {

  private val nodeState: CordaNodeState by inject()

  companion object {
    private val pathInfoPattern = Regex("""^/([A-Z0-9]+)\(([0-9]+)\)$""")

    private val logger = loggerFor<ContractStateRefQueryEndpoint<*>>()
  }

  override val responseType = SerializerKey(contractStateClass)

  override val resourcePath = "$contextPath/{hash}({index})"

  override fun executeQuery(request: Request): Response<StateType> {
    if (!request.subject.isPermitted(OPERATION_GET_STATE_BY_REF, contractStateClass.qualifiedName)) {
      throw UnauthorizedOperationException(OPERATION_GET_STATE_BY_REF)
    }

    logger.debug("Parsing relativePath {}", request.relativePath)

    val match = pathInfoPattern.matchEntire(request.relativePath)
        ?: throw BadOperationRequestException("Malformed pathInfo ${request.relativePath}")

    val (hash, index) = match.destructured
    val stateRef = StateRef(SecureHash.parse(hash), index.toInt())

    logger.debug("Parsed stateRef parameter is {}", stateRef)

    // we are interested in consumed states too, as this is a permanent URL
    val stateAndRef = nodeState.findStateByRef(stateRef, contractStateClass.java, Vault.StateStatus.ALL)
        ?: throw EndpointOperationException(
            message = "No such state with ref $stateRef",
            errorType = OperationErrorType.NOT_FOUND)

    // FIXME validate actual contract state type, otherwise it will fail at serialization

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
              schema = schemaGenerator.generateSchema(responseType))
          ).withResponse(OpenAPI.HttpStatusCode.NOT_FOUND, OpenAPI.Response(
              description = "Contract state with given hash and index was not found")
          ).withForbiddenResponse().withTags(VAULT_QUERY_TAG)
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
    ContextMappedResourceEndpoint(contextPath, true),
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

  private val settings: Settings by inject()
  private val nodeState: CordaNodeState by inject()

  override val requestType = SerializerKey(CordaVaultQuery::class.java, contractStateClass.java)
  override val responseType = SerializerKey(CordaVaultPage::class.java, contractStateClass.java)
  override val supportedMethods = listOf("GET", "POST")

  // GET form of query supports a limited subset of criteria
  override fun executeQuery(request: Request): Response<CordaVaultPage<StateType>> {
    if (!request.subject.isPermitted(OPERATION_QUERY_STATES, contractStateClass.qualifiedName)) {
      throw UnauthorizedOperationException(OPERATION_QUERY_STATES)
    }

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

    if (!request.subject.isPermitted(OPERATION_QUERY_STATES, contractStateClass.qualifiedName)) {
      throw UnauthorizedOperationException(OPERATION_QUERY_STATES)
    }

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
    val pageSize = min(
        request.getPositiveIntParameterValue(PARAMETER_NAME_PAGE_SIZE, DEFAULT_PAGE_SIZE),
        settings.maxVaultQueryPageSize)

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
              schema = schemaGenerator.generateSchema(requestType),
              required = true)
          ).withResponse(OpenAPI.HttpStatusCode.OK, OpenAPI.Response.createJsonResponse(
              description = "Query ran successfully",
              schema = schemaGenerator.generateSchema(responseType))
          ).withTags(VAULT_QUERY_TAG),
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
              schema = schemaGenerator.generateSchema(responseType))
          ).withForbiddenResponse().withTags(VAULT_QUERY_TAG)
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
    if (!request.subject.isPermitted(OPERATION_GET_TX_BY_HASH)) {
      throw UnauthorizedOperationException(OPERATION_GET_TX_BY_HASH)
    }

    logger.debug("Parsing relativePath {}", request.relativePath)

    val match = pathInfoPattern.matchEntire(request.relativePath)
        ?: throw BadOperationRequestException("Malformed relativePath ${request.relativePath}")

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
              schema = schemaGenerator.generateSchema(responseType))
          ).withResponse(OpenAPI.HttpStatusCode.NOT_FOUND, OpenAPI.Response(
              description = "Transaction with given hash value was not found")
          ).withForbiddenResponse().withTags(VAULT_QUERY_TAG)
      )
}
