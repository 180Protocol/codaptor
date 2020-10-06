package tech.b180.cordaptor.rest

import org.eclipse.jetty.client.HttpClient
import org.eclipse.jetty.server.Request
import org.eclipse.jetty.server.handler.AbstractHandler
import org.junit.AfterClass
import org.junit.BeforeClass
import org.koin.core.context.KoinContextHandler
import org.koin.core.context.startKoin
import org.koin.dsl.koinApplication
import org.koin.dsl.module
import org.koin.test.KoinTest
import org.koin.test.get
import tech.b180.cordaptor.kernel.HostAndPort
import javax.json.Json
import javax.servlet.http.HttpServletRequest
import javax.servlet.http.HttpServletResponse
import kotlin.test.Test
import kotlin.test.assertEquals


class JettyServerTest : KoinTest {

  companion object {

    private val koinApp = startKoin {
      modules(module {
        // initialize a server with plain HTTP connection
        single { JettyServer() }
        single<JettyConfigurator> { ConnectorFactory(JettyConnectorConfiguration(
            HostAndPort("localhost", 9000))) }

        single<ContextMappedHandler> { EchoHandler("/test") }

        single { HttpClient() }
      })
    }
    private val koin = koinApp.koin

    @BeforeClass @JvmStatic
    fun `start Jetty`() {
      koin.get<JettyServer>().initialize()
      koin.get<HttpClient>().start()
    }

    @AfterClass @JvmStatic
    fun `stop Jetty`() {
      koin.get<HttpClient>().stop()
      koin.get<JettyServer>().shutdown()

      KoinContextHandler.stop()
    }
  }

  @Test
  fun `test plain http`() {
    val httpClient = get<HttpClient>()

    httpClient.GET("http://localhost:9000/test").let {
      assertEquals(HttpServletResponse.SC_OK, it.status)

      val data = it.contentAsString.asJsonObject()
      assertEquals("/", data.getString("target"))
      assertEquals("/test", data.getString("contextPath"))
      assertEquals("/", data.getString("pathInfo"))
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
}

class EchoHandler(override val contextPath: String) : ContextMappedHandler, AbstractHandler() {

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
