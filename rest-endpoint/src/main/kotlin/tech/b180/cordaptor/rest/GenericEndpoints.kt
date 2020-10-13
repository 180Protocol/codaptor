package tech.b180.cordaptor.rest

import io.reactivex.rxjava3.core.Observable
import io.reactivex.rxjava3.core.Single
import org.eclipse.jetty.server.handler.AbstractHandler
import tech.b180.cordaptor.kernel.CordaptorComponent
import java.beans.Transient
import java.lang.reflect.ParameterizedType
import java.lang.reflect.Type
import javax.servlet.http.HttpServletRequest
import javax.servlet.http.HttpServletResponse
import org.eclipse.jetty.server.Request as JettyRequest

/**
 * Base class for any exceptions that may arise when executing a Cordaptor API operation.
 * Instances of this exception will be represented in a respective payload.
 * Subclasses may add further fields to be serialized.
 */
class EndpointOperationException(
    message: String,
    cause: Throwable? = null,
    val errorType: String = "Error",
    val statusCode: Int = HttpServletResponse.SC_INTERNAL_SERVER_ERROR
    @Suppress("unused") val errorType: String = "Error",
    @get:Transient val statusCode: Int = HttpServletResponse.SC_INTERNAL_SERVER_ERROR
) : Exception(message, cause)

interface Request {
  val pathInfo: String?
}

interface RequestWithPayload<PayloadType: Any> : Request {
  val payload: PayloadType
}

data class Response<PayloadType: Any>(
    val payload: PayloadType,
    val statusCode: Int = HttpServletResponse.SC_OK
)

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
   * If the error is an instance of [EndpointOperationException],
   * it will be send back as a protocol payload.
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
    responseType: Type,
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

      val endpointResponse = doHandle(request)

      response.status = endpointResponse.statusCode
      response.contentType = JSON_CONTENT_TYPE

      JsonHome.createGenerator(response.writer)
          .writeSerializedObject(responseSerializer, endpointResponse.payload)
          .flush()

    } catch (e: EndpointOperationException) {
      response.status = e.statusCode
      response.contentType = JSON_CONTENT_TYPE

      JsonHome.createGenerator(response.writer)
          .writeSerializedObject(errorSerializer, e)
          .flush()

    } catch (e: Throwable) {
      response.sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR)
    }
  }

  abstract fun canHandle(request: HttpServletRequest): Boolean

  /**
   * @throws EndpointOperationException
   */
  abstract fun doHandle(request: HttpServletRequest): Response<ResponseType>

  open class HttpRequest(private val request: HttpServletRequest) : Request {
    override val pathInfo: String?
      get() = request.pathInfo
  }

  companion object {
    const val JSON_CONTENT_TYPE = "application/json"
  }
}

class QueryEndpointHandler<ResponseType: Any>(
    private val endpoint: QueryEndpoint<ResponseType>
) : AbstractEndpointHandler<ResponseType>(endpoint.responseType, endpoint.contextMappingParameters) {

  override fun canHandle(request: HttpServletRequest): Boolean {
    return request.method == "GET"
  }

  override fun doHandle(request: HttpServletRequest): Response<ResponseType> {
    val endpointRequest = HttpRequest(request)
    val endpointResponse = endpoint.executeQuery(endpointRequest)

    // validation for correct return type instead of failing to serialize with a cryptic message
    // this may occur if the endpoint's responseType is incorrectly set
    if (!endpoint.responseType.isAssignableFrom(endpointResponse.payload.javaClass)) {
      throw EndpointOperationException("Endpoint returned an instance of ${endpointResponse.payload.javaClass}, " +
          "where an instance of ${endpoint.responseType} was expected")
    }
    return endpointResponse
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
