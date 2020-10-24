package tech.b180.cordaptor.rest

import io.reactivex.rxjava3.core.Observable
import io.reactivex.rxjava3.core.Single
import io.reactivex.rxjava3.internal.operators.single.SingleJust
import io.reactivex.rxjava3.observers.DisposableSingleObserver
import org.eclipse.jetty.server.handler.AbstractHandler
import tech.b180.cordaptor.kernel.CordaptorComponent
import tech.b180.cordaptor.kernel.loggerFor
import tech.b180.cordaptor.shaded.javax.json.stream.JsonParsingException
import java.beans.Transient
import java.lang.reflect.ParameterizedType
import java.lang.reflect.Type
import javax.servlet.http.HttpServletRequest
import javax.servlet.http.HttpServletResponse
import org.eclipse.jetty.server.Request as JettyRequest

enum class OperationErrorType(val protocolStatusCode: Int) {
  GENERIC_ERROR(HttpServletResponse.SC_INTERNAL_SERVER_ERROR),
  BAD_REQUEST(HttpServletResponse.SC_BAD_REQUEST),
  NOT_FOUND(HttpServletResponse.SC_NOT_FOUND)
}

/**
 * Base class for any exceptions that may arise when executing a Cordaptor API operation.
 * Instances of this exception will be represented in a respective payload.
 * Subclasses may add further fields to be serialized.
 */
open class EndpointOperationException(
    message: String,
    cause: Throwable? = null,
    @Suppress("UNUSED_PARAMETER") val errorType: OperationErrorType = OperationErrorType.GENERIC_ERROR,
    @get:Transient val statusCode: Int = errorType.protocolStatusCode
) : Exception(message, cause)

/**
 * Indicates that passed parameters are invalid in the context of a particular operation.
 * Optionally may indicate which parameter had invalid value.
 */
class BadOperationRequestException(
    message: String,
    cause: Throwable? = null,
    @Suppress("UNUSED_PARAMETER") parameterName: String? = null
) : EndpointOperationException(message, cause, errorType = OperationErrorType.BAD_REQUEST)

/**
 * Generic protocol request for an API operation,
 * allowing the logic to obtain information about the protocol query.
 */
interface Request {
  val pathInfo: String?
  val method: String

  /**
   * Returns a query string parameter value or null
   */
  fun getParameter(name: String): String?
}

fun Request.getIntParameter(name: String): Int? =
    getParameter(name)?.let {
      it.toIntOrNull() ?: throw BadOperationRequestException(
          "Expected integer value for parameter $name, got [$it]", parameterName = name)
    }

fun Request.getIntParameter(name: String, defaultValue: Int): Int =
    getIntParameter(name) ?: defaultValue

fun Request.getPositiveIntParameter(name: String): Int? =
    getIntParameter(name)?.let {
      if (it >= 0) it else throw BadOperationRequestException(
          "Expected positive value for parameter $name, got $it", parameterName = name)
    }

fun Request.getPositiveIntParameter(name: String, defaultValue: Int): Int =
    getPositiveIntParameter(name) ?: defaultValue

/**
 * Extension of the protocol request type for an API operation
 * supporting a payload.
 */
interface RequestWithPayload<PayloadType: Any> : Request {
  val payload: PayloadType
}

/**
 * Wrapper type for an API response payload able to configure the details of the protocol response.
 */
data class Response<PayloadType: Any>(
    val payload: PayloadType,
    val statusCode: Int = DEFAULT_STATUS_CODE
) {
  companion object {
    /** Assumed by default unless overridden in the constructor */
    const val DEFAULT_STATUS_CODE = HttpServletResponse.SC_OK
  }
}

/**
 * Provides basic information about an API endpoint mapped to a URL and generating a JSON response.
 */
interface GenericEndpoint {

  /**
   * Returned type may be parameterized in order to correctly configure the serializer
   * used by the endpoint handler. Note that returned type could be parameterized,
   * which comes handy if a specific serializer need to be used.
   */
  val responseType: Type

  /**
   * Parameters used to construct an HTTP query handler.
   */
  val contextMappingParameters: ContextMappingParameters

  /**
   * Specification for the resource within OpenAPI specification corresponding to this endpoint.
   */
  val resourceSpecification: OpenAPIResource
}

/**
 * Entry point for creating a section of OpenAPI specification document corresponding
 * to a particular endpoint.
 */
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
abstract class ContextMappedResourceEndpoint(
    private val contextPath: String,
    allowNullPathInfo: Boolean
) : GenericEndpoint, OpenAPIResource {

  override val resourcePath: String
    get() = contextPath
  override val contextMappingParameters = ContextMappingParameters(contextPath, allowNullPathInfo)

  override val resourceSpecification: OpenAPIResource
    get() = this
}

/**
 * Extension of a basic [ContextMappedResourceEndpoint] for a [QueryEndpoint] removing some boilerplate code.
 * Note that extending this class only makes sense if subclass is giving a meaningful value for [ResponseType],
 * otherwise this base class adds no value.
 */
abstract class ContextMappedQueryEndpoint<ResponseType: Any>(
    contextPath: String,
    allowNullPathInfo: Boolean
) : ContextMappedResourceEndpoint(contextPath, allowNullPathInfo), QueryEndpoint<ResponseType> {

  override val responseType: Type =
      SerializerKey.fromSuperclassTypeArgument(QueryEndpoint::class, this::class).asType()
}

/**
 * Extension of a basic [ContextMappedResourceEndpoint] for an [OperationEndpoint] removing some boilerplate code.
 * Note that extending this class only makes sense if subclass is giving meaningful values for
 * [RequestType] and [ResponseType], otherwise this base class adds no value.
 */
abstract class ContextMappedOperationEndpoint<RequestType: Any, ResponseType: Any>(
    contextPath: String,
    allowNullPathInfo: Boolean
) : ContextMappedResourceEndpoint(contextPath, allowNullPathInfo), OperationEndpoint<RequestType, ResponseType> {

  override val requestType: Type =
      SerializerKey.fromSuperclassTypeArgument(OperationEndpoint::class, this::class, 0).asType()

  override val responseType: Type =
      SerializerKey.fromSuperclassTypeArgument(OperationEndpoint::class, this::class, 1).asType()
}

/**
 * Contract of a Cordaptor API endpoint that accepts
 * an HTTP get request and returns a result synchronously.
 */
interface QueryEndpoint<ResponseType: Any> : GenericEndpoint {

  /**
   * @throws EndpointOperationException
   */
  fun executeQuery(request: Request): Response<ResponseType>
}

/**
 * Contract of a Cordaptor API endpoint that takes a HTTP request representing an
 * action verb (POST, PUT, etc), and performs an operation.
 */
interface OperationEndpoint<RequestType: Any, ResponseType: Any> : GenericEndpoint {

  /**
   * Returned type may be parameterized in order to correctly configure the serializer
   * used by the endpoint handler. Note that returned type could be parameterized,
   * which comes handy if a specific serializer need to be used.
   */
  val requestType: Type

  /**
   * HTTP methods supported by the operation
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
  val requestType: Type

  fun beginInteraction(futureRequests: Observable<RequestType>): Observable<ResponseType>
}

/**
 * Denotes a component that initializes a number of endpoints of various types.
 * The expectation for the implementation is to initialize the endpoints upon construction or first use,
 * and then return the same set of endpoints, because it might be called repeatedly
 */
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
    private val responseType: Type,
    override val mappingParameters: ContextMappingParameters
) : ContextMappedHandler, AbstractHandler(), CordaptorComponent {

  val responseSerializer by injectSerializer<ResponseType>(responseType)
  val errorSerializer by injectSerializer(EndpointOperationException::class)

  override fun handle(target: String?, baseRequest: JettyRequest?,
                      request: HttpServletRequest?, response: HttpServletResponse?) {

    // if context mapping was successful, the request will be handled
    baseRequest!!.isHandled = true

    if (request == null || response == null) {
      throw AssertionError("Jetty should not pass null request and/or response at this point")
    }

    try {
      if (!canHandle(request)) {
        response.sendError(HttpServletResponse.SC_METHOD_NOT_ALLOWED)
        return
      }

      doHandle(request, response)

    } catch (e: EndpointOperationException) {
      logger.debug("Endpoint operation threw a protocol exception, which will be serialized", e)
      sendError(response, e)

    } catch (e: Throwable) {
      logger.error("Endpoint operation threw an unexpected exception", e)
      response.sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR)
    }
  }

  abstract fun canHandle(request: HttpServletRequest): Boolean

  /**
   * Handles request in a way specific to the endpoint type
   *
   * @throws EndpointOperationException
   */
  abstract fun doHandle(request: HttpServletRequest, response: HttpServletResponse)

  fun sendError(servletResponse: HttpServletResponse, error: EndpointOperationException) {
    logger.debug("Sending a protocol error to the client: {}", error)

    servletResponse.status = error.statusCode
    servletResponse.contentType = JSON_CONTENT_TYPE

    JsonHome.createGenerator(servletResponse.writer)
        .writeSerializedObject(errorSerializer, error)
        .flush()
  }

  fun sendResponse(servletResponse: HttpServletResponse, endpointResponse: Response<ResponseType>) {
    logger.debug("Sending response payload: {}", endpointResponse)

    // validation for correct return type instead of failing to serialize with a cryptic message
    // this may occur if the endpoint's responseType is incorrectly set
    if (!responseType.isAssignableFrom(endpointResponse.payload.javaClass)) {
      throw EndpointOperationException("Endpoint returned an instance of ${endpointResponse.payload.javaClass}, " +
          "where an instance of $responseType was expected")
    }

    servletResponse.status = endpointResponse.statusCode
    servletResponse.contentType = JSON_CONTENT_TYPE

    JsonHome.createGenerator(servletResponse.writer)
        .writeSerializedObject(responseSerializer, endpointResponse.payload)
        .flush()
  }

  /**
   * Implementation for the protocol request wrapping an HTTP request
   */
  open class HttpRequest(private val request: HttpServletRequest) : Request {
    override val pathInfo: String?
      get() = request.pathInfo
    override val method: String
      get() = request.method

    override fun getParameter(name: String): String? = request.getParameter(name)
  }

  /**
   * Implementation for the protocol request wrapper accepting a deserialized payload object.
   */
  class HttpRequestWithPayload<T: Any>(
      request: HttpServletRequest,
      override val payload: T
  ) : HttpRequest(request), RequestWithPayload<T>

  companion object {
    const val JSON_CONTENT_TYPE = "application/json"

    private val logger = loggerFor<AbstractEndpointHandler<*>>()
  }
}

/**
 * Specific endpoint handler implementation able to translate an HTTP request
 * received via Jetty server into an invocation of a query via a particular endpoint.
 */
class QueryEndpointHandler<ResponseType: Any>(
    private val endpoint: QueryEndpoint<ResponseType>
) : AbstractEndpointHandler<ResponseType>(endpoint.responseType, endpoint.contextMappingParameters) {

  override fun canHandle(request: HttpServletRequest): Boolean {
    return request.method == "GET"
  }

  override fun doHandle(request: HttpServletRequest, response: HttpServletResponse) {
    val endpointRequest = HttpRequest(request)
    val endpointResponse = endpoint.executeQuery(endpointRequest)
    sendResponse(response, endpointResponse)
  }

  override fun toString(): String {
    return "QueryEndpointHandler(responseType=${endpoint.responseType.typeName}, endpoint=${endpoint}"
  }
}

/**
 * Specific endpoint handler implementation able to translate an HTTP request
 * received via Jetty server into an invocation of an operation.
 *
 * The implementation normally expects an asynchronous execution,
 * but the endpoint may indicate a synchronously produced response by calling [Single.just]
 */
class OperationEndpointHandler<RequestType: Any, ResponseType: Any>(
    private val endpoint: OperationEndpoint<RequestType, ResponseType>
): AbstractEndpointHandler<ResponseType>(endpoint.responseType, endpoint.contextMappingParameters) {

  companion object {
    private val logger = loggerFor<OperationEndpointHandler<*, *>>()
  }

  private val requestSerializer by injectSerializer<RequestType>(endpoint.requestType)

  override fun canHandle(request: HttpServletRequest): Boolean {
    return request.method in endpoint.supportedMethods
  }

  override fun doHandle(request: HttpServletRequest, response: HttpServletResponse) {
    if (request.contentLength == 0) {
      throw BadOperationRequestException("Empty request payload")
    }
    val requestPayload = try {
      val requestJsonPayload = JsonHome.createReader(request.reader).readObject()
      requestSerializer.fromJson(requestJsonPayload)
    } catch (e: JsonParsingException) {
      logger.debug("JSON parsing exception, which will be returned to the client", e)
      throw BadOperationRequestException("Malformed JSON in the request payload", e)
    } catch (e: SerializationException) {
      logger.debug("Exception during payload deserialization, which will be returned to the client", e)
      throw BadOperationRequestException("Unable to deserialize the request payload", e)
    }

    val endpointRequest = HttpRequestWithPayload(request, requestPayload)
    val endpointResponse = endpoint.executeOperation(endpointRequest)

    // special handling for operations executed synchronously and using Single.just() to signal this
    if (endpointResponse is SingleJust<*>) {
      sendResponse(response, endpointResponse.blockingGet())
      return
    }

    // switch into async mode and only return when the result promise returns or times out
    val async = request.startAsync()
    endpointResponse.subscribe(object : DisposableSingleObserver<Response<ResponseType>>() {
      override fun onSuccess(result: Response<ResponseType>?) = sendAndClose {
        sendResponse(async.response as HttpServletResponse, result!!)
      }

      override fun onError(error: Throwable?) = sendAndClose {
        sendError(async.response as HttpServletResponse, error as? EndpointOperationException
            ?: EndpointOperationException(error?.message ?: "Unknown internal error", error))
      }

      /** Wrapper for the action communicating the outcome of the operation to the client
       * that makes sure that all necessary resources are disposed correctly */
      private fun sendAndClose(action: () -> Unit) {
        try {
          action()
        } catch (e: SerializationException) {
          sendError(async.response as HttpServletResponse,
              EndpointOperationException("Unable to send response payload", e))

        } finally {
          async.complete()
          dispose()
        }
      }
    })
  }

  override fun toString(): String {
    return "OperationEndpointHandler(requestType=${endpoint.requestType.typeName}, responseType=${endpoint.responseType.typeName}, endpoint=${endpoint}"
  }
}

fun Type.isAssignableFrom(clazz: Class<*>): Boolean = when(this) {
  is Class<*> -> this.isAssignableFrom(clazz)
  is ParameterizedType -> {
    val rawType = this.rawType
    // this cannot be done recursively because compiler cannot check this for correctness
    when (rawType) {
      is Class<*> -> rawType.isAssignableFrom(clazz)
      else -> throw AssertionError("Don't know how to check if ${rawType.typeName} is assignable from ${clazz.canonicalName}")
    }
  }
  else -> throw AssertionError("Don't know how to check if ${this.typeName} is assignable from ${clazz.canonicalName}")
}
