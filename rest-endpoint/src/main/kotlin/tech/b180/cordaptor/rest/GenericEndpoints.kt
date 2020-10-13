package tech.b180.cordaptor.rest

import io.reactivex.rxjava3.core.Observable
import io.reactivex.rxjava3.core.Single
import io.reactivex.rxjava3.internal.operators.single.SingleJust
import org.eclipse.jetty.server.handler.AbstractHandler
import tech.b180.cordaptor.corda.CordaFlowProgress
import tech.b180.cordaptor.corda.CordaFlowResult
import tech.b180.cordaptor.corda.CordaFlowSnapshot
import tech.b180.cordaptor.kernel.CordaptorComponent
import java.beans.Transient
import java.lang.reflect.ParameterizedType
import java.lang.reflect.Type
import javax.json.stream.JsonParsingException
import javax.servlet.http.HttpServletRequest
import javax.servlet.http.HttpServletResponse
import org.eclipse.jetty.server.Request as JettyRequest

/**
 * Base class for any exceptions that may arise when executing a Cordaptor API operation.
 * Instances of this exception will be represented in a respective payload.
 * Subclasses may add further fields to be serialized.
 */
open class EndpointOperationException(
    message: String,
    cause: Throwable? = null,
    @Suppress("unused") val errorType: String = "Error",
    @get:Transient val statusCode: Int = HttpServletResponse.SC_INTERNAL_SERVER_ERROR
) : Exception(message, cause)

/**
 * Indicates that passed parameters are invalid in the context of a particular operation
 */
class BadOperationRequestException(
    message: String,
    cause: Throwable? = null
) : EndpointOperationException(message, cause, errorType = "BadRequest", statusCode = HttpServletResponse.SC_BAD_REQUEST)

/**
 * Generic protocol request for an API operation,
 * allowing the logic to obtain information about the protocol query.
 */
interface Request {
  val pathInfo: String?
  val method: String
}

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
    val statusCode: Int = HttpServletResponse.SC_OK
)

/**
 * Provides basic information about an API endpoint mapped to a URL and generating a JSON response.
 */
interface GenericEndpoint {

  /**
   * Returned type may be parameterized in order to correctly configure the serializer
   * used by the endpoint handler
   */
  val responseType: Type

  /**
   * Parameters used to construct an HTTP query handler.
   */
  val contextMappingParameters: ContextMappingParameters
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
   * used by the endpoint handler
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
 * Base implementation of Jetty request handler that wraps a particular Cordaptor API endpoint.
 */
abstract class AbstractEndpointHandler<ResponseType: Any>(
    private val responseType: Type,
    override val mappingParameters: ContextMappingParameters
) : ContextMappedHandler, AbstractHandler(), CordaptorComponent {

  private val responseSerializer by injectSerializer<ResponseType>(responseType)
  private val errorSerializer by injectSerializer(EndpointOperationException::class)

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
      sendError(response, e)

    } catch (e: Throwable) {
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
    servletResponse.status = error.statusCode
    servletResponse.contentType = JSON_CONTENT_TYPE

    JsonHome.createGenerator(servletResponse.writer)
        .writeSerializedObject(errorSerializer, error)
        .flush()
  }

  fun sendResponse(servletResponse: HttpServletResponse, endpointResponse: Response<ResponseType>) {
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
      throw BadOperationRequestException("Malformed JSON in the request payload", e)
    } catch (e: SerializationException) {
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
    endpointResponse.subscribe { result, error ->
      try {
        if (result != null) {
          sendResponse(async.response as HttpServletResponse, result)
        }
        // error must be not null at this point
        sendError(async.response as HttpServletResponse, error as? EndpointOperationException
            ?: EndpointOperationException(error?.message ?: "Unknown internal error", error))

      } catch (e: SerializationException) {
        sendError(async.response as HttpServletResponse,
            EndpointOperationException("Unable to serialize response payload", e))

      } finally {
        async.complete()
      }
    }
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
