package tech.b180.cordaptor.rest

import io.reactivex.rxjava3.core.Single
import org.eclipse.jetty.client.HttpClient
import org.eclipse.jetty.client.util.StringContentProvider
import org.eclipse.jetty.server.Request
import org.eclipse.jetty.server.handler.AbstractHandler
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.koin.dsl.bind
import org.koin.dsl.module
import org.koin.test.KoinTest
import org.koin.test.KoinTestRule
import org.koin.test.get
import tech.b180.cordaptor.kernel.HostAndPort
import java.lang.reflect.Type
import java.util.concurrent.TimeUnit
import javax.json.Json
import javax.servlet.http.HttpServletRequest
import javax.servlet.http.HttpServletResponse
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse


class JettyServerTest : KoinTest {

  @get:Rule
  val koinTestRule = KoinTestRule.create {
    // Your KoinApplication instance here
    modules(testModule)
  }

  companion object {

    private val testModule = module {
      single { SerializationFactory(lazy { emptyList<CustomSerializer<Any>>() }) }

      factory<JsonSerializer<*>> { (key: SerializerKey) -> get<SerializationFactory>().getSerializer(key) }

      // initialize a server with plain HTTP connection
      single { JettyServer() }
      single<JettyConfigurator> { ConnectorFactory(JettyConnectorConfiguration(
          HostAndPort("localhost", 9000))) }

      single<ContextMappedHandler> { EchoHandler("/test") }

      single { EchoQueryEndpoint("/echo-query") } bind QueryEndpoint::class
      single { MisconfiguredEchoQueryEndpoint("/echo-query-wrong-type") } bind QueryEndpoint::class
      single { SyncEchoOperationEndpoint("/echo-op-sync") } bind OperationEndpoint::class
      single { AsyncEchoOperationEndpoint("/echo-op-async") } bind OperationEndpoint::class

      // parameterized accessor for obtaining handler instances allowing them to have dependencies managed by Koin
      factory<QueryEndpointHandler<*>> { (endpoint: QueryEndpoint<*>) -> QueryEndpointHandler(endpoint) }
      factory<OperationEndpointHandler<*, *>> { (endpoint: OperationEndpoint<*, *>) -> OperationEndpointHandler(endpoint) }

      single { HttpClient() }
    }
  }

  @Before
  fun setUp() {
    get<JettyServer>().initialize()
    get<HttpClient>().start()
  }

  @After
  fun tearDown() {
    get<HttpClient>().stop()
    get<JettyServer>().shutdown()
  }

  @Test
  fun `test plain http`() {
    val httpClient = get<HttpClient>()

    httpClient.GET("http://localhost:9000/test").let {
      assertEquals(HttpServletResponse.SC_OK, it.status)

      val data = it.contentAsString.asJsonObject()
      assertEquals("/", data.getString("target"))
      assertEquals("/test", data.getString("contextPath"))
      assertEquals("", data.getString("pathInfo"))
      assertEquals("", data.getString("queryString"))
    }

    httpClient.GET("http://localhost:9000/test/info?query").let {
      assertEquals(HttpServletResponse.SC_OK, it.status)

      val data = it.contentAsString.asJsonObject()
      assertEquals("/info", data.getString("target"))
      assertEquals("/test", data.getString("contextPath"))
      assertEquals("/info", data.getString("pathInfo"))
      assertEquals("query", data.getString("queryString"))
    }
  }

  @Test
  fun `test query endpoint handling`() {
    val httpClient = get<HttpClient>()

    httpClient.GET("http://localhost:9000/echo-query").let {
      assertEquals(HttpServletResponse.SC_ACCEPTED, it.status)
      assertEquals(AbstractEndpointHandler.JSON_CONTENT_TYPE, it.mediaType)
      assertEquals("""{"pathInfo":"/","method":"GET","message":"echo"}""".asJsonObject(), it.contentAsString.asJsonObject())
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

      assertEquals("""{"pathInfo":"","method":"POST","message":"sync"}""".asJsonObject(),
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
      assertEquals(HttpServletResponse.SC_ACCEPTED, it.status)
      assertEquals(AbstractEndpointHandler.JSON_CONTENT_TYPE, it.mediaType)

      assertEquals("""{"pathInfo":"","method":"POST","message":"async"}""".asJsonObject(),
          it.contentAsString.asJsonObject())
    }
  }
}

class EchoHandler(contextPath: String) : ContextMappedHandler, AbstractHandler() {

  override val mappingParameters = ContextMappingParameters(contextPath, true)

  override fun handle(target: String?, baseRequest: Request?, request: HttpServletRequest?, response: HttpServletResponse?) {
    response!!.status = HttpServletResponse.SC_OK
    response.contentType = "text/plain"

    Json.createGenerator(response.writer)
        .writeStartObject()
        .write("target", target ?: "")
        .write("contextPath", request!!.contextPath)
        .write("pathInfo", request.pathInfo)
        .write("queryString", request.queryString ?: "")
        .writeEnd()
        .flush()

    baseRequest!!.isHandled = true
  }
}

data class SimplePayload(val value: String)
data class EchoPayload(val pathInfo: String, val method: String, val message: String = "echo")

class EchoQueryEndpoint(contextPath: String) : QueryEndpoint<EchoPayload> {

  override val responseType = EchoPayload::class.java
  override val contextMappingParameters = ContextMappingParameters(contextPath, false)

  override fun executeQuery(request: tech.b180.cordaptor.rest.Request): Response<EchoPayload> {
    return Response(EchoPayload(request.pathInfo!!, request.method), statusCode = HttpServletResponse.SC_ACCEPTED)
  }
}

class MisconfiguredEchoQueryEndpoint(contextPath: String) : QueryEndpoint<EchoPayload> {

  // incorrect response type information specified here
  override val responseType = String::class.java
  override val contextMappingParameters = ContextMappingParameters(contextPath, false)

  override fun executeQuery(request: tech.b180.cordaptor.rest.Request): Response<EchoPayload> {
    return Response(EchoPayload(request.pathInfo!!, request.method), statusCode = HttpServletResponse.SC_ACCEPTED)
  }
}

class SyncEchoOperationEndpoint(contextPath: String) : OperationEndpoint<SimplePayload, EchoPayload> {
  override val responseType = EchoPayload::class.java
  override val contextMappingParameters = ContextMappingParameters(contextPath, true)
  override val requestType: Type = SimplePayload::class.java
  override val supportedMethods: Collection<String> = listOf("POST")

  override fun executeOperation(request: RequestWithPayload<SimplePayload>): Single<Response<EchoPayload>> {
    return Single.just(Response(
        payload = EchoPayload(request.pathInfo ?: "", request.method, message = "sync"),
        statusCode = HttpServletResponse.SC_ACCEPTED))
  }
}

class AsyncEchoOperationEndpoint(contextPath: String) : OperationEndpoint<SimplePayload, EchoPayload> {
  override val responseType = EchoPayload::class.java
  override val contextMappingParameters = ContextMappingParameters(contextPath, true)
  override val requestType: Type = SimplePayload::class.java
  override val supportedMethods: Collection<String> = listOf("POST")

  override fun executeOperation(request: RequestWithPayload<SimplePayload>): Single<Response<EchoPayload>> {
    // actual amount of delay is irrelevant, anything that wraps SingleJust would trigger the async pathway
    return Single.just(Response(
        payload = EchoPayload(request.pathInfo ?: "", request.method, message = "async"),
        statusCode = HttpServletResponse.SC_ACCEPTED)).delay(1, TimeUnit.SECONDS)
  }
}