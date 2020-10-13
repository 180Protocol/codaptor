package tech.b180.cordaptor.rest

import org.eclipse.jetty.client.HttpClient
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

      // parameterized accessor for obtaining handler instances allowing them to have dependencies managed by Koin
      factory<QueryEndpointHandler<*>> { (endpoint: QueryEndpoint<*>) -> QueryEndpointHandler(endpoint) }

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
      assertEquals(AbstractEndpointHandler.Companion.JSON_CONTENT_TYPE, it.mediaType)
      assertEquals("""{"pathInfo":"/"}""".asJsonObject(), it.contentAsString.asJsonObject())
    }

    httpClient.GET("http://localhost:9000/echo-query-wrong-type").let {
      assertEquals(HttpServletResponse.SC_INTERNAL_SERVER_ERROR, it.status)
      assertEquals(AbstractEndpointHandler.Companion.JSON_CONTENT_TYPE, it.mediaType)

      val exceptionObject = it.contentAsString.asJsonObject()
      assertFalse(exceptionObject.containsKey("statusCode"), "Should not send a transient value")
      assertEquals("Endpoint returned an instance of class ${EchoPayload::class.java.canonicalName}, " +
          "where an instance of class java.lang.String was expected",
          exceptionObject.getString("message"))
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

data class EchoQueryPayload(val pathInfo: String)

class EchoQueryEndpoint(contextPath: String) : QueryEndpoint<EchoQueryPayload> {

  override val responseType = EchoQueryPayload::class.java
  override val contextMappingParameters = ContextMappingParameters(contextPath, false)

  override fun executeQuery(request: tech.b180.cordaptor.rest.Request): Response<EchoQueryPayload> {
    return Response(EchoQueryPayload(request.pathInfo!!), statusCode = HttpServletResponse.SC_ACCEPTED)
  }
}

class MisconfiguredEchoQueryEndpoint(contextPath: String) : QueryEndpoint<EchoQueryPayload> {

  // incorrect response type information specified here
  override val responseType = String::class.java
  override val contextMappingParameters = ContextMappingParameters(contextPath, false)

  override fun executeQuery(request: tech.b180.cordaptor.rest.Request): Response<EchoQueryPayload> {
    return Response(EchoQueryPayload(request.pathInfo!!), statusCode = HttpServletResponse.SC_ACCEPTED)
  }
}
