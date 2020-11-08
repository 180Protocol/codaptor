package tech.b180.cordaptor.rest

import io.undertow.util.StatusCodes
import org.eclipse.jetty.client.HttpClient
import org.eclipse.jetty.http.HttpMethod
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.koin.core.qualifier.named
import org.koin.dsl.bind
import org.koin.dsl.module
import org.koin.test.KoinTest
import org.koin.test.KoinTestRule
import org.koin.test.get
import tech.b180.cordaptor.kernel.HostAndPort
import tech.b180.cordaptor.kernel.LifecycleControl
import tech.b180.cordaptor.kernel.TypesafeConfig
import kotlin.test.Test
import kotlin.test.assertEquals

class APIKeyTest : KoinTest {

  companion object {
    private val testModule = module {

      // initialize a server with plain HTTP connection
      single { WebServer() }
      single { NoopLifecycleControl as LifecycleControl }
      single { WebServerSettings(HostAndPort("localhost", 9000),
          SecureTransportSettings(false, TypesafeConfig.empty()),
          1, 1) } bind URLBuilder::class
      single { SecuritySettings(securityHandlerName = "apiKey") }
      single { UndertowHandlerContributor(get()) } bind UndertowConfigContributor::class
      single { UndertowListenerContributor(get()) } bind UndertowConfigContributor::class

      single<SecurityHandlerFactory>(named(SECURITY_CONFIGURATION_API_KEY)) {
        APIKeySecurityHandlerFactory(TypesafeConfig.fromMap(mapOf(
            "header" to "X-API-Key",
            "keys" to listOf("TEST-KEY")
        )))
      }

      single(named("not secured")) { EchoHandler("/echo") } bind ContextMappedHandler::class
      single(named("secured")) { EchoHandler("/node/echo") } bind ContextMappedHandler::class

      single { HttpClient() }
    }
  }

  @get:Rule
  val koinTestRule = KoinTestRule.create {
    // Your KoinApplication instance here
    modules(testModule)
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
  fun `test unsecured URL`() {
    val httpClient = get<HttpClient>()
    val urlBuilder = get<URLBuilder>()

    httpClient.GET(urlBuilder.toAbsoluteUrl("/echo")).let {
      assertEquals(StatusCodes.OK, it.status)

      val data = it.contentAsString.asJsonObject()
      assertEquals("", data.getString("relativePath"))
      assertEquals("/echo", data.getString("resolvedPath"))
      assertEquals("", data.getString("queryString"))
    }
  }

  @Test
  fun `test secured URL no header`() {
    val httpClient = get<HttpClient>()
    val urlBuilder = get<URLBuilder>()

    httpClient.GET(urlBuilder.toAbsoluteUrl("/node/echo")).let {
      assertEquals(StatusCodes.FORBIDDEN, it.status)
    }
  }

  @Test
  fun `test secured URL incorrect header`() {
    val httpClient = get<HttpClient>()
    val urlBuilder = get<URLBuilder>()

    httpClient.newRequest(urlBuilder.toAbsoluteUrl("/node/echo")).let {
      it.method(HttpMethod.GET)
      it.header("X-API-Key", "INCORRECT-KEY")
    }.send().let {
      assertEquals(StatusCodes.FORBIDDEN, it.status)
    }
  }

  @Test
  fun `test secured URL access granted`() {
    val httpClient = get<HttpClient>()
    val urlBuilder = get<URLBuilder>()

    httpClient.newRequest(urlBuilder.toAbsoluteUrl("/node/echo")).let {
      it.method(HttpMethod.GET)
      it.header("X-API-Key", "TEST-KEY")
    }.send().let {
      assertEquals(StatusCodes.OK, it.status)
    }
  }
}