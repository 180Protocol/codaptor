package tech.b180.cordaptor.rest

import io.reactivex.rxjava3.core.Observable
import io.reactivex.rxjava3.core.Single
import io.reactivex.rxjava3.internal.operators.single.SingleJust
import io.undertow.server.HttpServerExchange
import io.undertow.util.*
import tech.b180.cordaptor.kernel.CordaptorComponent
import tech.b180.cordaptor.kernel.ModuleAPI
import tech.b180.cordaptor.kernel.loggerFor
import tech.b180.cordaptor.shaded.javax.json.Json
import tech.b180.cordaptor.shaded.javax.json.stream.JsonParsingException
import java.beans.Transient
import java.io.OutputStreamWriter
import java.io.StringReader

@ModuleAPI(since = "0.1")
enum class OperationErrorType(val protocolStatusCode: Int) {
  GENERIC_ERROR(StatusCodes.INTERNAL_SERVER_ERROR),
  BAD_REQUEST(StatusCodes.BAD_REQUEST),
  NOT_FOUND(StatusCodes.NOT_FOUND),
  UNAUTHORIZED(StatusCodes.UNAUTHORIZED)
}

/**
 * Payload structure that is sent to the client when the operation ends with an error.
 */
@ModuleAPI(since = "0.1")
data class EndpointErrorMessage(
    val message: String,
    val cause: Throwable? = null,
    val errorType: OperationErrorType = OperationErrorType.GENERIC_ERROR,
    /** Not serialized in the payload, but set on the response */
    @get:Transient val statusCode: Int = errorType.protocolStatusCode
)

/**
 * Base class for any exceptions that may arise when executing a Cordaptor API operation.
 * Instances of this exception will be represented in a respective payload.
 * Subclasses may add further fields to be serialized.
 */
@ModuleAPI(since = "0.1")
open class EndpointOperationException(
    message: String,
    cause: Throwable? = null,
    @Suppress("UNUSED_PARAMETER") val errorType: OperationErrorType = OperationErrorType.GENERIC_ERROR,
    @get:Transient val statusCode: Int = errorType.protocolStatusCode
) : Exception(message, cause) {

  fun toErrorMessage() = EndpointErrorMessage(
      message = message ?: "Internal error",
      cause = cause,
      errorType = errorType,
      statusCode = statusCode
  )
}

/**
 * Indicates that passed parameters are invalid in the context of a particular operation.
 * Optionally may indicate which parameter had invalid value.
 */
@ModuleAPI(since = "0.1")
class BadOperationRequestException(
    message: String,
    cause: Throwable? = null,
    @Suppress("UNUSED_PARAMETER") parameterName: String? = null
) : EndpointOperationException(message, cause, errorType = OperationErrorType.BAD_REQUEST)

/**
 * Indicates that the caller is not authorized to perform the operation.
 * This may indicate that provided caller's credentials are not recognized,
 * or that the caller's subject does not have sufficient privileges.
 */
@ModuleAPI(since = "0.1")
class UnauthorizedOperationException(
    message: String = "Not authorized"
) : EndpointOperationException(message, null, errorType = OperationErrorType.UNAUTHORIZED)

/**
 * Generic protocol request for an API operation,
 * allowing the logic to obtain information about the protocol query.
 */
@ModuleAPI(since = "0.1")
interface Request {
  /** Equivalent of pathInfo in Servlet spec */
  val relativePath: String
  /** Equivalent to contextPath in Servlet spec */
  val resolvedPath: String
  /** Combination of [resolvedPath] and [relativePath] */
  val completePath: String get() = resolvedPath + relativePath

  val method: String

  val queryParameters: Map<String, List<String>>

  /** Caller's security subject associated with the request */
  val subject: Subject

  /**
   * Returns a query string parameter value or null.
   * If there is more than one parameter for a given name, only one of them is returned
   */
  fun getParameterValue(name: String): String?

  /**
   * Returns all values for a given query string parameter, or null if there are no values.
   */
  fun getAllParameterValues(name: String): List<String>?
}

@ModuleAPI(since = "0.1")
fun Request.getIntParameterValue(name: String): Int? =
    getParameterValue(name)?.let {
      it.toIntOrNull() ?: throw BadOperationRequestException(
          "Expected integer value for parameter $name, got [$it]", parameterName = name)
    }

@ModuleAPI(since = "0.1")
fun Request.getIntParameterValue(name: String, defaultValue: Int): Int =
    getIntParameterValue(name) ?: defaultValue

@ModuleAPI(since = "0.1")
fun Request.getPositiveIntParameterValue(name: String): Int? =
    getIntParameterValue(name)?.let {
      if (it >= 0) it else throw BadOperationRequestException(
          "Expected positive value for parameter $name, got $it", parameterName = name)
    }

@ModuleAPI(since = "0.1")
fun Request.getPositiveIntParameterValue(name: String, defaultValue: Int): Int =
    getPositiveIntParameterValue(name) ?: defaultValue

/**
 * Extension of the protocol request type for an API operation
 * supporting a payload.
 */
@ModuleAPI(since = "0.1")
interface RequestWithPayload<PayloadType: Any> : Request {
  val payload: PayloadType
}

/**
 * Wrapper type for an API response payload able to configure the details of the protocol response.
 * Payload may be optional if a particular endpoint does not require it.
 */
@ModuleAPI(since = "0.1")
data class Response<PayloadType: Any>(
    val payload: PayloadType?,
    val statusCode: Int = DEFAULT_STATUS_CODE,
    val headers: List<Header> = emptyList()
) {
  companion object {
    /** Assumed by default unless overridden in the constructor */
    const val DEFAULT_STATUS_CODE = StatusCodes.OK
  }

  data class Header(
      val header: HttpHeader,
      val value: String
  )
}

/**
 * Provides basic information about an API endpoint mapped to a URL and generating a JSON response.
 */
@ModuleAPI(since = "0.1")
interface GenericEndpoint {

  /**
   * Returned type may be parameterized in order to correctly configure the serializer
   * used by the endpoint handler. Note that returned type could be parameterized,
   * which comes handy if a specific serializer need to be used.
   */
  val responseType: SerializerKey

  /**
   * Parameters used to construct an HTTP query handler.
   */
  val contextMappingParameters: ContextMappedHandler.Parameters

  /**
   * Specification for the resource within OpenAPI specification corresponding to this endpoint.
   */
  val resourceSpecification: OpenAPIResource
}

/**
 * Entry point for creating a section of OpenAPI specification document corresponding
 * to a particular endpoint.
 */
@ModuleAPI(since = "0.1")
interface OpenAPIResource {

  /** Key for the entry representing an OpenAPI resource endpoint.
   * May contain path templates using curly brackets, e.g. `/users/{id}` */
  val resourcePath: String

  /**
   * Creates a JSON object representing a resource endpoint entry in 'paths' section of OpenAPI specification.
   * See [https://swagger.io/docs/specification/paths-and-operations/]
   *
   * @param schemaGenerator allows JSON schema definitions for operation payloads to be generated
   */
  fun generatePathInfoSpecification(schemaGenerator: JsonSchemaGenerator): OpenAPI.PathItem
}

/**
 * Base class applicable for most endpoints that are exposed at a fixed context path
 * optionally accepting non-path parameters.
 */
@ModuleAPI(since = "0.1")
abstract class ContextMappedResourceEndpoint(
    val path: String,
    exactPathOnly: Boolean
) : GenericEndpoint, OpenAPIResource {

  /** Default implementation uses the same resource path as context path,
   * but it must be overridden if the endpoint requires path parameters */
  override val resourcePath: String
    get() = path
  override val contextMappingParameters = ContextMappedHandler.Parameters(path, exactPathOnly)

  override val resourceSpecification: OpenAPIResource
    get() = this
}

/**
 * Extension of a basic [ContextMappedResourceEndpoint] for a [QueryEndpoint] removing some boilerplate code.
 * Note that extending this class only makes sense if subclass is giving a meaningful value for [ResponseType],
 * otherwise this base class adds no value and may lead to obscure errors during initialization.
 */
@ModuleAPI(since = "0.1")
abstract class ContextMappedQueryEndpoint<ResponseType: Any>(
    contextPath: String,
    allowNullPathInfo: Boolean
) : ContextMappedResourceEndpoint(contextPath, allowNullPathInfo), QueryEndpoint<ResponseType> {

  override val responseType = SerializerKey.fromSuperclassTypeArgument(QueryEndpoint::class, this::class)
}

/**
 * Extension of a basic [ContextMappedResourceEndpoint] for an [OperationEndpoint] removing some boilerplate code.
 * Note that extending this class only makes sense if subclass is giving meaningful values for
 * [RequestType] and [ResponseType], otherwise this base class adds no value  and may lead to obscure errors
 * during initialization.
 */
@ModuleAPI(since = "0.1")
abstract class ContextMappedOperationEndpoint<RequestType: Any, ResponseType: Any>(
    contextPath: String,
    allowNullPathInfo: Boolean
) : ContextMappedResourceEndpoint(contextPath, allowNullPathInfo), OperationEndpoint<RequestType, ResponseType> {

  override val requestType =
      SerializerKey.fromSuperclassTypeArgument(OperationEndpoint::class, this::class, 0)

  override val responseType =
      SerializerKey.fromSuperclassTypeArgument(OperationEndpoint::class, this::class, 1)
}

/**
 * Contract of a Cordaptor API endpoint that accepts
 * an HTTP get request and returns a result synchronously.
 */
@ModuleAPI(since = "0.1")
interface QueryEndpoint<ResponseType: Any> : GenericEndpoint {

  /**
   * @throws EndpointOperationException
   */
  fun executeQuery(request: Request): Response<ResponseType>
}

/**
 * Contract of a Cordaptor API endpoint that takes a HTTP request representing an
 * action verb (POST, PUT, etc), and performs an operation.
 *
 * Note that [OperationEndpointHandler] supports scenario where the endpoint also implements [QueryEndpoint],
 * but for this to work [supportedMethods] property must return GET among its other methods.
 */
@ModuleAPI(since = "0.1")
interface OperationEndpoint<RequestType: Any, ResponseType: Any> : GenericEndpoint {

  /**
   * Returned type may be parameterized in order to correctly configure the serializer
   * used by the endpoint handler. Note that returned type could be parameterized,
   * which comes handy if a specific serializer need to be used.
   */
  val requestType: SerializerKey

  /**
   * HTTP methods supported by the operation. Must only contain uppercase strings.
   */
  val supportedMethods: Collection<String>

  /**
   * If the error is an instance of [EndpointOperationException],
   * it will be send back as a protocol payload.
   *
   * The implementation is normally expected to perform work asynchronously,
   * but it may indicate a synchronously produced response by calling [Single.just]
   * and thus returning an instance of [SingleJust]
   *
   * @return a promise for the operation result or an error
   */
  fun executeOperation(request: RequestWithPayload<RequestType>): Single<Response<ResponseType>>

  companion object {
    val POST_ONLY = listOf("POST")
  }
}

/**
 * Contract of a Cordaptor API endpoint that represents a WebSocket session
 * with a client.
 */
interface InteractionEndpoint<RequestType: Any, ResponseType: Any> : GenericEndpoint {

  /**
   * Returned type may be parameterized in order to correctly configure the serializer
   * used by the endpoint handler
   */
  val requestType: SerializerKey

  fun beginInteraction(futureRequests: Observable<RequestType>): Observable<ResponseType>
}

/**
 * Denotes a component that initializes a number of endpoints of various types.
 * The expectation for the implementation is to initialize the endpoints upon construction or first use,
 * and then return the same set of endpoints, because it might be called repeatedly
 */
@ModuleAPI(since = "0.1")
interface EndpointProvider {

  val queryEndpoints: List<QueryEndpoint<*>>
    get() = emptyList()

  val operationEndpoints: List<OperationEndpoint<*, *>>
    get() = emptyList()

  val interactionEndpoints: List<InteractionEndpoint<*, *>>
    get() = emptyList()
}

/**
 * Base implementation of Jetty request handler that wraps a particular Cordaptor API endpoint.
 */
abstract class AbstractEndpointHandler<ResponseType: Any>(
    private val responseType: SerializerKey,
    override val mappingParameters: ContextMappedHandler.Parameters
) : ContextMappedHandler, CordaptorComponent {

  companion object {
    const val JSON_CONTENT_TYPE = "application/json"

    private val logger = loggerFor<AbstractEndpointHandler<*>>()
  }

  private val responseSerializer by injectSerializer<ResponseType>(responseType)
  private val errorMessageSerializer by injectSerializer(EndpointErrorMessage::class)

  override fun handleRequest(exchange: HttpServerExchange) {
    if (exchange.isInIoThread) {
      exchange.dispatch(this)
      return
    }

    try {
      if (!canHandle(exchange)) {
        exchange.statusCode = StatusCodes.METHOD_NOT_ALLOWED
        exchange.endExchange()
        return
      }

      doHandle(exchange, obtainSubject(exchange))

    } catch (e: Throwable) {
      if (!exchange.isResponseStarted) {
        logger.debug("Endpoint operation threw an exception", e)

        exchange.statusCode = StatusCodes.INTERNAL_SERVER_ERROR

        val error = (e as? EndpointOperationException)?.toErrorMessage()
            ?: EndpointErrorMessage("Unknown server error", e)

        sendError(exchange, error)
      } else {
        logger.warn("Endpoint operation threw unexpected exception after starting to send back a response", e)
      }
    }
  }

  private fun obtainSubject(exchange: HttpServerExchange): Subject {
    return PermissiveSubject
  }

  protected abstract fun canHandle(exchange: HttpServerExchange): Boolean

  /**
   * Handles request in a way specific to the endpoint type.
   * The hand off will be made in a worker thread, so implementations do not need to deal
   * with dispatching unless they want to read request payload data in a non-blocking way.
   *
   * The implementation may throw an exception, which will be passed into only use [sendResponse] or [sendError] methods
   * to complete response. Any exceptions thrown by the implementation will be sent to the client via [sendError].
   * If the implementation invokes asynchronous IO operations, then it is responsible for eventually
   * calling [sendResponse] or [sendError].
   *
   * @throws EndpointOperationException
   */
  protected abstract fun doHandle(exchange: HttpServerExchange, subject: Subject)

  /**
   * One of two ways to end the exchange normally, the other one is [sendResponse].
   * This method will serialize given exception using the [errorMessageSerializer],
   * and then will end the exchange using [HttpServerExchange.endExchange]
   */
  protected fun sendError(exchange: HttpServerExchange, error: EndpointErrorMessage) {
    if (exchange.isResponseStarted) {
      logger.warn("Response has already been started, cannot send error: {}", error)
      return  // nothing can be done, so simply log the error
    }
    logger.debug("Sending a protocol error to the client: {}", error)

    try {
      exchange.statusCode = error.statusCode
      exchange.responseHeaders.put(Headers.CONTENT_TYPE, JSON_CONTENT_TYPE)

      // we switch to blocking mode to simplify stream handling, because
      // generation of JSON is presumed to be fast and will not hold the worker thread for long
      exchange.startBlocking()
      Json.createGenerator(OutputStreamWriter(exchange.outputStream))
          .writeSerializedObject(errorMessageSerializer, error)
          .flush()
    } catch (e: Exception) {
      logger.error("Exception occurred whilst serializing an error object masking it: {}", error, error.cause)

    } finally {
      exchange.endExchange()
    }
  }

  /**
   * One of two ways to end the exchange normally, the other one is [sendError].
   * This method will serialize given response payload using the [responseSerializer],
   * and then will end the exchange using [HttpServerExchange.endExchange]
   */
  protected fun sendResponse(exchange: HttpServerExchange, endpointResponse: Response<ResponseType>) {
    if (exchange.isResponseStarted) {
      logger.warn("Response has already been started, cannot send the response: {}", endpointResponse)
      return  // nothing can be done, so simply log the error
    }
    logger.debug("Sending response payload: {}", endpointResponse)

    val payload = endpointResponse.payload

    // validation for correct return type instead of failing to serialize with a cryptic message
    // this may occur if the endpoint's responseType is incorrectly set
    if (payload != null && !responseType.rawType.isAssignableFrom(payload.javaClass)) {

      // at this point we have not touched the response, so an error could be sent instead
      sendError(exchange, EndpointErrorMessage("Endpoint returned an instance of ${payload.javaClass}, " +
          "where an instance of ${responseType.rawType} was expected"))

      return // sendError will close the exchange
    }

    // in this block we actually send back the response
    try {
      exchange.statusCode = endpointResponse.statusCode
      for (header in endpointResponse.headers) {
        exchange.responseHeaders.put(HttpString(header.header), header.value)
      }

      payload?.let {
        exchange.responseHeaders.put(Headers.CONTENT_TYPE, JSON_CONTENT_TYPE)

        // we switch to blocking mode to simplify stream handling, because
        // generation of JSON is presumed to be fast and will not hold the worker thread for long
        exchange.startBlocking()

        Json.createGenerator(OutputStreamWriter(exchange.outputStream))
            .writeSerializedObject(responseSerializer, payload)
            .flush()
      }
    } finally {
      exchange.endExchange()
    }
  }

  /**
   * Implementation for the protocol request wrapping an HTTP request
   */
  open class HttpRequest(
      private val exchange: HttpServerExchange,
      override val subject: Subject
  ) : Request {
    override val relativePath: String
      get() = exchange.relativePath
    override val method: String
      get() = exchange.requestMethod.toString()
    override val resolvedPath: String
      get() = exchange.resolvedPath

    override val queryParameters: Map<String, List<String>> = (exchange.pathParameters + exchange.queryParameters)
        .mapValues { (_, values) -> ArrayList(values) }

    override fun getParameterValue(name: String): String? = queryParameters[name]?.first()
    override fun getAllParameterValues(name: String): List<String>? = queryParameters[name]

    override fun toString(): String = "HttpRequest(method=$method, relativePath=$relativePath, " +
        "resolvedPath=$resolvedPath, parameters=$queryParameters)"
  }

  /**
   * Implementation for the protocol request wrapper accepting a deserialized payload object.
   */
  class HttpRequestWithPayload<T: Any>(
      exchange: HttpServerExchange,
      subject: Subject,
      override val payload: T
  ) : HttpRequest(exchange, subject), RequestWithPayload<T> {

    override fun toString(): String = "HttpRequest(method=$method, relativePath=$relativePath, " +
        "resolvedPath=$resolvedPath, parameters=$queryParameters, payload=$payload)"
  }
}

/**
 * Specific endpoint handler implementation able to translate an HTTP request
 * received via Jetty server into an invocation of a query via a particular endpoint.
 */
class QueryEndpointHandler<ResponseType: Any>(
    private val endpoint: QueryEndpoint<ResponseType>
) : AbstractEndpointHandler<ResponseType>(endpoint.responseType, endpoint.contextMappingParameters) {

  override fun canHandle(exchange: HttpServerExchange): Boolean {
    return exchange.requestMethod == Methods.GET
  }

  override fun doHandle(exchange: HttpServerExchange, subject: Subject) {
    val endpointRequest = HttpRequest(exchange, subject)
    val endpointResponse = endpoint.executeQuery(endpointRequest)
    sendResponse(exchange, endpointResponse)
  }

  override fun toString(): String {
    return "QueryEndpointHandler(responseType=${endpoint.responseType}, endpoint=${endpoint})"
  }
}

/**
 * Specific endpoint handler implementation able to translate an HTTP request
 * received via Jetty server into an invocation of an operation.
 *
 * The implementation normally expects an asynchronous execution,
 * but the endpoint may indicate a synchronously produced response by calling [Single.just]
 *
 * Operation endpoints may also implement [QueryEndpoint] interface, in which case
 * the handler will accept GET request, and delegate it to [QueryEndpoint.executeQuery] method.
 * Note that [OperationEndpoint.supportedMethods] must explicitly allow GET method for this to work.
 */
class OperationEndpointHandler<RequestType: Any, ResponseType: Any>(
    private val endpoint: OperationEndpoint<RequestType, ResponseType>
): AbstractEndpointHandler<ResponseType>(endpoint.responseType, endpoint.contextMappingParameters) {

  companion object {
    private val logger = loggerFor<OperationEndpointHandler<*, *>>()
  }

  private val requestSerializer by injectSerializer<RequestType>(endpoint.requestType)

  /**
   * This field will be not null if the endpoint is also
   */
  @Suppress("UNCHECKED_CAST")
  private val queryEndpoint = endpoint as? QueryEndpoint<ResponseType>

  init {
    if (queryEndpoint != null) {
      logger.debug("Operation endpoint at {} will also accept GET queries",
          endpoint.contextMappingParameters.path)
    }
  }

  override fun canHandle(exchange: HttpServerExchange): Boolean {
    return exchange.requestMethod.toString().toUpperCase() in endpoint.supportedMethods
  }

  override fun doHandle(exchange: HttpServerExchange, subject: Subject) {
    if (tryHandlingAsQuery(exchange, subject)) {
      return
    }

    // we have to use non-blocking IO to read request payload and not switch to a blocking mode
    // because API operations are performed asynchronously
    val asyncReceiver = exchange.requestReceiver
    asyncReceiver.receiveFullString({ e, s -> receiveRequestPayload(subject, e, s) }, Charsets.UTF_8)
  }

  private fun receiveRequestPayload(subject: Subject, exchange: HttpServerExchange, payloadString: String) {
    if (payloadString.isEmpty()) {
      sendError(exchange, EndpointErrorMessage(
          "Empty request payload", errorType = OperationErrorType.BAD_REQUEST))
      return
    }

    val endpointRequest = try {

      val requestJsonPayload = Json.createReader(StringReader(payloadString)).readObject()
      val requestPayload = requestSerializer.fromJson(requestJsonPayload)

      HttpRequestWithPayload(exchange, subject, requestPayload)

    } catch (e: JsonParsingException) {

      logger.debug("JSON parsing exception, which will be returned to the client", e)
      sendError(exchange, EndpointErrorMessage("Malformed JSON in the request payload",
          cause = e, errorType = OperationErrorType.BAD_REQUEST))
      return
    } catch (e: SerializationException) {

      logger.debug("Exception during payload deserialization, which will be returned to the client", e)
      sendError(exchange, EndpointErrorMessage("Unable to deserialize the request payload",
          cause = e, errorType = OperationErrorType.BAD_REQUEST))
      return
    }

    // invoke operation in the worker thread
    logger.debug("Invoking operation with request: {}", endpointRequest)

    val endpointResponse = endpoint.executeOperation(endpointRequest)

    // special handling for operations executed synchronously and using Single.just() to signal this
    if (endpointResponse is SingleJust<*>) {
      logger.debug("Endpoint operation at {} completed synchronously, sending response outright", endpointRequest.completePath)

      // operation result will be available outright, so we can simply get it and send back the response
      sendResponse(exchange, endpointResponse.blockingGet())

    } else {
      logger.debug("Awaiting completion of endpoint operation at {}", endpointRequest.completePath)

      // this is poorly documented in Undertow, the suggestion taken from here:
      // https://stackoverflow.com/questions/25204887/how-to-send-a-asynchronous-response-in-an-undertow-httphandler#25223070
      exchange.dispatch(SameThreadExecutor.INSTANCE, Runnable {
        // we need to wait for the result of the operation to become available,
        // which means we have to subscribe to the promise and send response payload once it's available
        endpointResponse.subscribe { response, error ->
          // either one or the other will be null
          if (response != null) {
            sendResponse(exchange, response)
          } else if (error != null) {
            sendError(exchange, (error as? EndpointOperationException)?.toErrorMessage()
                ?: EndpointErrorMessage("Unexpected internal error", error))
          }
        }
      })
    }
  }

  private fun tryHandlingAsQuery(exchange: HttpServerExchange, subject: Subject): Boolean {
    if (queryEndpoint == null || exchange.requestMethod != Methods.GET) {
      return false
    }
    val endpointRequest = HttpRequest(exchange, subject)
    val endpointResponse = queryEndpoint.executeQuery(endpointRequest)
    sendResponse(exchange, endpointResponse)
    return true
  }

  override fun toString(): String {
    return "OperationEndpointHandler(requestType=${endpoint.requestType}, " +
        "responseType=${endpoint.responseType}, endpoint=${endpoint})"
  }
}
