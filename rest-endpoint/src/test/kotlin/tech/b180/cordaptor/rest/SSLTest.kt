package tech.b180.cordaptor.rest

import io.undertow.util.StatusCodes
import org.eclipse.jetty.client.HttpClient
import org.eclipse.jetty.util.ssl.SslContextFactory
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.koin.dsl.bind
import org.koin.dsl.module
import org.koin.test.KoinTest
import org.koin.test.KoinTestRule
import org.koin.test.get
import tech.b180.cordaptor.kernel.*
import kotlin.test.assertEquals

class SSLTest : KoinTest {

  companion object {
    private val testModule = module {

      // initialize a server with plain HTTP connection
      single { WebServer() }
      single { NoopLifecycleControl as LifecycleControl }
      single { WebServerSettings(HostAndPort("localhost", 9000),
          SecureTransportSettings(TypesafeConfig.loadFrom("ssl.conf")),
          1, 1) } bind URLBuilder::class
      single { SecuritySettings(securityHandlerName = SECURITY_CONFIGURATION_NONE) }
      single { UndertowHandlerContributor(get(), get(), get()) } bind UndertowConfigContributor::class
      single { UndertowListenerContributor(get(), get()) } bind UndertowConfigContributor::class
      single { DefaultSSLConfigurator(get()) as SSLConfigurator }
      single { ConfigSecretsStore(TypesafeConfig.loadFrom("ssl.conf")) as SecretsStore }

      single { EchoHandler("/echo") } bind ContextMappedHandler::class

      single { HttpClient(SslContextFactory.Client(true)) }
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
  fun `ssl connection`() {
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
}