package tech.b180.cordaptor_test

import net.corda.core.identity.CordaX500Name
import net.corda.core.utilities.getOrThrow
import net.corda.node.services.Permissions
import net.corda.testing.driver.*
import net.corda.testing.node.NotarySpec
import net.corda.testing.node.TestCordapp
import net.corda.testing.node.User
import org.eclipse.jetty.client.HttpClient
import org.eclipse.jetty.client.util.StringContentProvider
import org.junit.BeforeClass
import tech.b180.ref_cordapp.SimpleFlow
import java.io.StringReader
import java.nio.charset.Charset
import javax.json.Json
import javax.json.JsonValue
import javax.servlet.http.HttpServletResponse
import kotlin.test.Test
import kotlin.test.assertEquals

const val NODE_NAME = "O=Bank, L=London, C=GB"

class EmbeddedBundleTest {

  @Test
  fun testRestAPI() = withDriver {
    val handle = startNode(CordaX500Name.parse(NODE_NAME))

    val client = HttpClient()
    client.start()

    testNodeInfoRequest(client)
  }

  private fun testNodeInfoRequest(client: HttpClient) {
    val response = client.GET("http://localhost:8500/node/info")
    assertEquals(HttpServletResponse.SC_OK, response.status)
    assertEquals("application/json", response.mediaType)

    val nodeInfo = Json.createReader(StringReader(response.contentAsString)).readObject()
    assertEquals("localhost".asJsonValue(), nodeInfo.getValue("/addresses/0/host"))
    assertEquals(NODE_NAME.asJsonValue(), nodeInfo.getValue("/legalIdentitiesAndCerts/0/name"))
    assertEquals(7.asJsonValue(), nodeInfo.getValue("/platformVersion"))
    assertEquals(JsonValue.ValueType.NUMBER, nodeInfo.getValue("/serial").valueType)
  }
//
//  private fun testFlowFireAndForget(client: HttpClient) {
//    val req = client.POST("http://localhost:8500/node/${SimpleFlow::class.qualifiedName}")
//
//    val content = """{
//      |"externalId":"TEST-111"}""".trimMargin()
//
//    req.content(StringContentProvider("application/json", content, Charset.forName("UTF-8")))
//    val flowResponse = req.send()
//    assertEquals(HttpServletResponse.SC_ACCEPTED, flowResponse.status)
//  }

  private fun DriverDSL.startNode(name: CordaX500Name): NodeHandle {
    return startNode(
        defaultParameters = NodeParameters(
            providedName = name,
            additionalCordapps = listOf(TestCordapp.findCordapp("tech.b180.cordaptor").withConfig(
                mapOf("useLocalCache" to false)
            )),
            rpcUsers = listOf(
                User(
                    username = "client",
                    password = "test",
                    permissions = setOf(Permissions.all()))
            )
        )
    ).getOrThrow()
  }
}

private fun String.asJsonValue() = Json.createValue(this)
private fun Int.asJsonValue() = Json.createValue(this)

private fun withDriver(test: DriverDSL.() -> Unit) = driver(
    DriverParameters(isDebug = true, startNodesInProcess = true)
        .withNotarySpecs(listOf(
            NotarySpec(
                validating = false,
                name = CordaX500Name.parse("O=Notary,L=London,C=GB"),
                rpcUsers = emptyList(),
                verifierType = VerifierType.InMemory)))
) {
  test()
}
