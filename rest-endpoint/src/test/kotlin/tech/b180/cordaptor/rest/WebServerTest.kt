package tech.b180.cordaptor.rest

import io.reactivex.rxjava3.core.Single
import io.undertow.server.HttpServerExchange
import io.undertow.util.Headers
import org.eclipse.jetty.client.HttpClient
import org.eclipse.jetty.client.util.StringContentProvider
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.koin.dsl.bind
import org.koin.dsl.module
import org.koin.test.KoinTest
import org.koin.test.KoinTestRule
import org.koin.test.get
import tech.b180.cordaptor.kernel.HostAndPort
import tech.b180.cordaptor.kernel.LifecycleControl
import tech.b180.cordaptor.kernel.TypesafeConfig
import tech.b180.cordaptor.shaded.javax.json.Json
import java.io.StringWriter
import java.util.concurrent.TimeUnit
import javax.servlet.http.HttpServletResponse
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse


class WebServerTest : KoinTest {

  @get:Rule
  val koinTestRule = KoinTestRule.create {
    // Your KoinApplication instance here
    modules(testModule)
  }

  companion object {

    private val testModule = module {
      single { SerializationFactory(lazy { emptyList<CustomSerializer<Any>>() }, lazy { emptyList<CustomSerializerFactory<Any>>() }) }

      factory<JsonSerializer<*>> { (key: SerializerKey) -> get<SerializationFactory>().getSerializer(key) }

      // initialize a server with plain HTTP connection
      single { WebServer() }
      single { object : LifecycleControl {
        override fun serverStarted() { }
      } as LifecycleControl }
      single { WebServerSettings(HostAndPort("localhost", 9000),
          SecureTransportSettings(false, TypesafeConfig.empty()), 1, 1) }
      single { UndertowHandlerContributor(get()) } bind UndertowConfigContributor::class
      single { UndertowListenerContributor(get()) } bind UndertowConfigContributor::class

      single { EchoHandler("/test") } bind ContextMappedHandler::class

      single { EchoQueryEndpoint("/echo-query") } bind QueryEndpoint::class
      single { MisconfiguredEchoQueryEndpoint("/echo-query-wrong-type") } bind QueryEndpoint::class
      single { SyncEchoOperationEndpoint("/echo-op-sync") } bind OperationEndpoint::class
      single { AsyncEchoOperationEndpoint("/echo-op-async") } bind OperationEndpoint::class

      single { SwaggerUIHandler("/swagger") } bind ContextMappedHandler::class

      // parameterized accessor for obtaining handler instances allowing them to have dependencies managed by Koin
      factory<QueryEndpointHandler<*>> { (endpoint: QueryEndpoint<*>) -> QueryEndpointHandler(endpoint) }
      factory<OperationEndpointHandler<*, *>> { (endpoint: OperationEndpoint<*, *>) -> OperationEndpointHandler(endpoint) }

      single { HttpClient() }
    }
  }

  @Before
  fun setUp() {
    get<WebServer>().onInitialize()
    get<HttpClient>().start()
  }

  @After
  fun tearDown() {
    get<HttpClient>().stop()
    get<WebServer>().onShutdown()
  }

  @Test
  fun `test plain http`() {
    val httpClient = get<HttpClient>()

    httpClient.GET("http://localhost:9000/test").let {
      assertEquals(HttpServletResponse.SC_OK, it.status)

      val data = it.contentAsString.asJsonObject()
      assertEquals("", data.getString("relativePath"))
      assertEquals("/test", data.getString("resolvedPath"))
      assertEquals("", data.getString("queryString"))
    }

    httpClient.GET("http://localhost:9000/test/info?query").let {
      assertEquals(HttpServletResponse.SC_OK, it.status)

      val data = it.contentAsString.asJsonObject()
      assertEquals("/info", data.getString("relativePath"))
      assertEquals("/test", data.getString("resolvedPath"))
      assertEquals("query", data.getString("queryString"))
    }
  }

  @Test
  fun `test query endpoint handling`() {
    val httpClient = get<HttpClient>()

    httpClient.GET("http://localhost:9000/echo-query").let {
      assertEquals(HttpServletResponse.SC_ACCEPTED, it.status)
      assertEquals(AbstractEndpointHandler.JSON_CONTENT_TYPE, it.mediaType)
      assertEquals("""{"relativePath":"","method":"GET","message":"echo"}""".asJsonObject(), it.contentAsString.asJsonObject())
    }

    httpClient.GET("http://localhost:9000/echo-query-wrong-type").let {
      assertEquals(HttpServletResponse.SC_INTERNAL_SERVER_ERROR, it.status)
      assertEquals(AbstractEndpointHandler.JSON_CONTENT_TYPE, it.mediaType)

      val exceptionObject = it.contentAsString.asJsonObject()
      assertFalse(exceptionObject.containsKey("statusCode"), "Should not send a transient value")
      assertEquals("Endpoint returned an instance of class ${EchoPayload::class.java.canonicalName}, " +
          "where an instance of class java.lang.String was expected",
          exceptionObject.getString("message"))
    }
  }

  @Test
  fun `test sync operation endpoint handling`() {
    val httpClient = get<HttpClient>()

    httpClient.POST("http://localhost:9000/echo-op-sync").let {
      it.content(StringContentProvider(""), AbstractEndpointHandler.JSON_CONTENT_TYPE)
      it.send()
    }.let {
      assertEquals(HttpServletResponse.SC_BAD_REQUEST, it.status)
      assertEquals(AbstractEndpointHandler.JSON_CONTENT_TYPE, it.mediaType)

      val exceptionObject = it.contentAsString.asJsonObject()
      assertEquals("BAD_REQUEST", exceptionObject.getString("errorType"))
      assertEquals("Empty request payload", exceptionObject.getString("message"))
    }

    httpClient.POST("http://localhost:9000/echo-op-sync").let {
      it.content(StringContentProvider("""{value:"ABC"}"""), AbstractEndpointHandler.JSON_CONTENT_TYPE)
      it.send()
    }.let {
      assertEquals(HttpServletResponse.SC_BAD_REQUEST, it.status)
      assertEquals(AbstractEndpointHandler.JSON_CONTENT_TYPE, it.mediaType)

      val exceptionObject = it.contentAsString.asJsonObject()
      assertEquals("BAD_REQUEST", exceptionObject.getString("errorType"))
      assertEquals("Malformed JSON in the request payload", exceptionObject.getString("message"))
    }

    httpClient.POST("http://localhost:9000/echo-op-sync").let {
      it.content(StringContentProvider("""{"value":"ABC"}"""), AbstractEndpointHandler.JSON_CONTENT_TYPE)
      it.send()
    }.let {
      assertEquals(HttpServletResponse.SC_ACCEPTED, it.status)
      assertEquals(AbstractEndpointHandler.JSON_CONTENT_TYPE, it.mediaType)

      assertEquals("""{"relativePath":"","method":"POST","message":"sync"}""".asJsonObject(),
          it.contentAsString.asJsonObject())
    }

    // test that operation endpoint may also accept GET requests if configured
    httpClient.GET("http://localhost:9000/echo-op-sync/pathInfo").let {
      assertEquals(HttpServletResponse.SC_ACCEPTED, it.status)
      assertEquals(AbstractEndpointHandler.JSON_CONTENT_TYPE, it.mediaType)

      assertEquals("""{"relativePath":"/pathInfo","method":"GET","message":"sync"}""".asJsonObject(),
          it.contentAsString.asJsonObject())
    }
  }

  @Test
  fun `test async operation endpoint handling`() {
    val httpClient = get<HttpClient>()

    httpClient.POST("http://localhost:9000/echo-op-async").let {
      it.content(StringContentProvider("""{"value":"ABC"}"""), AbstractEndpointHandler.JSON_CONTENT_TYPE)
      it.send()
    }.let {
      println(it)
      assertEquals(HttpServletResponse.SC_ACCEPTED, it.status)
      assertEquals(AbstractEndpointHandler.JSON_CONTENT_TYPE, it.mediaType)

      assertEquals("""{"relativePath":"","method":"POST","message":"async"}""".asJsonObject(),
          it.contentAsString.asJsonObject())
    }
  }

  @Test
  fun `test swagger UI resources handling`() {
    val httpClient = get<HttpClient>()

    httpClient.GET("http://localhost:9000/swagger").let { response ->
      assertEquals(HttpServletResponse.SC_OK, response.status)
      assertEquals("text/html", response.mediaType)
    }

    httpClient.GET("http://localhost:9000/swagger/cordaptor.bundle.js").let { response ->
      assertEquals(HttpServletResponse.SC_OK, response.status)
      assertEquals("application/javascript", response.mediaType)
    }
  }
}

class EchoHandler(contextPath: String) : ContextMappedHandler {

  override val mappingParameters = ContextMappedHandler.Parameters(contextPath, false)

  override fun handleRequest(exchange: HttpServerExchange) {
    exchange.statusCode = HttpServletResponse.SC_OK
    exchange.responseHeaders.put(Headers.CONTENT_TYPE, "text/plain")

    val w = StringWriter()
    Json.createGenerator(w)
        .writeStartObject()
        .write("relativePath", exchange.relativePath)
        .write("resolvedPath", exchange.resolvedPath)
        .write("queryString", exchange.queryString)
        .writeEnd()
        .flush()

    exchange.responseSender.send(w.toString())
  }
}

data class SimplePayload(val value: String)
data class EchoPayload(val relativePath: String, val method: String, val message: String = "echo")

class EchoQueryEndpoint(contextPath: String) : QueryEndpoint<EchoPayload> {

  override val responseType = SerializerKey(EchoPayload::class)
  override val contextMappingParameters = ContextMappedHandler.Parameters(contextPath, false)

  override fun executeQuery(request: Request): Response<EchoPayload> {
    return Response(EchoPayload(request.relativePath, request.method), statusCode = HttpServletResponse.SC_ACCEPTED)
  }

  override val resourceSpecification: OpenAPIResource
    get() = throw NotImplementedError("Not meant to be called")
}

class MisconfiguredEchoQueryEndpoint(contextPath: String) : QueryEndpoint<EchoPayload> {

  // incorrect response type information specified here
  override val responseType = SerializerKey(String::class)
  override val contextMappingParameters = ContextMappedHandler.Parameters(contextPath, false)

  override fun executeQuery(request: Request): Response<EchoPayload> {
    return Response(EchoPayload(request.relativePath, request.method), statusCode = HttpServletResponse.SC_ACCEPTED)
  }

  override val resourceSpecification: OpenAPIResource
    get() = throw NotImplementedError("Not meant to be called")
}

class SyncEchoOperationEndpoint(contextPath: String)
  : OperationEndpoint<SimplePayload, EchoPayload>, QueryEndpoint<EchoPayload> {
  override val responseType = SerializerKey(EchoPayload::class)
  override val contextMappingParameters = ContextMappedHandler.Parameters(contextPath, false)
  override val requestType = SerializerKey(SimplePayload::class)
  override val supportedMethods: Collection<String> = listOf("POST", "GET")

  override fun executeOperation(request: RequestWithPayload<SimplePayload>): Single<Response<EchoPayload>> {
    return Single.just(executeQuery(request))
  }

  override fun executeQuery(request: Request): Response<EchoPayload> {
    return Response(
        payload = EchoPayload(request.relativePath, request.method, message = "sync"),
        statusCode = HttpServletResponse.SC_ACCEPTED)
  }

  override val resourceSpecification: OpenAPIResource
    get() = throw NotImplementedError("Not meant to be called")
}

class AsyncEchoOperationEndpoint(contextPath: String) : OperationEndpoint<SimplePayload, EchoPayload> {
  override val responseType = SerializerKey(EchoPayload::class)
  override val contextMappingParameters = ContextMappedHandler.Parameters(contextPath, true)
  override val requestType = SerializerKey(SimplePayload::class)
  override val supportedMethods: Collection<String> = listOf("POST")

  override fun executeOperation(request: RequestWithPayload<SimplePayload>): Single<Response<EchoPayload>> {
    // actual amount of delay is irrelevant, anything that wraps SingleJust would trigger the async pathway
    return Single.just(Response(
        payload = EchoPayload(request.relativePath, request.method, message = "async"),
        statusCode = HttpServletResponse.SC_ACCEPTED)).delay(1, TimeUnit.SECONDS)
  }

  override val resourceSpecification: OpenAPIResource
    get() = throw NotImplementedError("Not meant to be called")
}