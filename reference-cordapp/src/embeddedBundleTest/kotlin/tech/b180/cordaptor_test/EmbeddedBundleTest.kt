package tech.b180.cordaptor_test

import net.corda.core.contracts.StateRef
import net.corda.core.crypto.SecureHash
import net.corda.core.identity.CordaX500Name
import net.corda.core.utilities.getOrThrow
import net.corda.node.services.Permissions
import net.corda.testing.driver.*
import net.corda.testing.node.NotarySpec
import net.corda.testing.node.TestCordapp
import net.corda.testing.node.User
import org.eclipse.jetty.client.HttpClient
import org.eclipse.jetty.client.util.StringContentProvider
import tech.b180.ref_cordapp.SimpleFlow
import tech.b180.ref_cordapp.SimpleLinearState
import java.io.StringReader
import javax.json.Json
import javax.json.JsonNumber
import javax.json.JsonString
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
    client.isFollowRedirects = false

    client.start()

    testNodeInfoRequest(client)
    testFlowFireAndForget(client)
    val stateRef = testFlowWaitForCompletion(client)
    testTransactionQuery(client, stateRef.txhash)
    testStateQuery(client, stateRef)
  }

  private fun testNodeInfoRequest(client: HttpClient) {
    val response = client.GET("http://localhost:8500/node/info")
    assertEquals(HttpServletResponse.SC_OK, response.status)
    assertEquals("application/json", response.mediaType)

    val nodeInfo = Json.createReader(StringReader(response.contentAsString)).readObject()
    assertEquals("localhost".asJsonValue(), nodeInfo.getValue("/addresses/0/host"))
    assertEquals(NODE_NAME.asJsonValue(), nodeInfo.getValue("/legalIdentitiesAndCerts/0/party/name"))
    assertEquals(7.asJsonValue(), nodeInfo.getValue("/platformVersion"))
    assertEquals(JsonValue.ValueType.NUMBER, nodeInfo.getValue("/serial").valueType)
  }

  private fun testFlowFireAndForget(client: HttpClient) {
    val req = client.POST("http://localhost:8500/node/${SimpleFlow::class.qualifiedName}")

    val content = """{
      |"externalId":"TEST-111"}""".trimMargin()

    req.content(StringContentProvider("application/json", content, Charsets.UTF_8))
    val response = req.send()
    assertEquals(HttpServletResponse.SC_ACCEPTED, response.status)
    assertEquals("application/json", response.mediaType)

    val handle = Json.createReader(StringReader(response.contentAsString)).readObject()
    assertEquals(SimpleFlow::class.qualifiedName!!.asJsonValue(), handle.getValue("/flowClass"))
    assertEquals(JsonValue.ValueType.STRING, handle.getValue("/flowRunId").valueType)
    assertEquals(JsonValue.ValueType.NULL, handle.getValue("/result").valueType)
  }

  private fun testFlowWaitForCompletion(client: HttpClient): StateRef {
    val req = client.POST("http://localhost:8500/node/${SimpleFlow::class.qualifiedName}?wait=-1")

    val content = """{
      |"externalId":"TEST-111"}""".trimMargin()

    req.content(StringContentProvider("application/json", content, Charsets.UTF_8))
    val response = req.send()
    assertEquals(HttpServletResponse.SC_OK, response.status)
    assertEquals("application/json", response.mediaType)

    val handle = Json.createReader(StringReader(response.contentAsString)).readObject()
    assertEquals(SimpleFlow::class.qualifiedName!!.asJsonValue(), handle.getValue("/flowClass"))
    assertEquals(JsonValue.ValueType.STRING, handle.getValue("/flowRunId").valueType)
    assertEquals(JsonValue.ValueType.OBJECT, handle.getValue("/result").valueType)

    val state = handle.getValue("/result/value/output/state/data").asJsonObject()
    assertEquals("TEST-111", state.getValue("/linearId/externalId").asString())
    assertEquals(NODE_NAME, state.getValue("/participant/name").asString())

    return StateRef(SecureHash.parse(handle.getValue("/result/value/output/ref/txhash").asString()),
        handle.getValue("/result/value/output/ref/index").asInt())
  }

  private fun testTransactionQuery(client: HttpClient, txid: SecureHash) {
    val response = client.GET("http://localhost:8500/node/tx/${txid}")

    val tx = Json.createReader(StringReader(response.contentAsString)).readObject()
    assertEquals(txid.toString(), tx.getString("id"))
    assertEquals("TEST-111",
        tx.getValue("/core/wireTransaction/outputs/0/data/linearId/externalId").asString())
  }

  private fun testStateQuery(client: HttpClient, stateRef: StateRef) {
    val response = client.GET("http://localhost:8500/node/${SimpleLinearState::class.qualifiedName}/${stateRef}")

    val state = Json.createReader(StringReader(response.contentAsString)).readObject()
    assertEquals("TEST-111",
        state.getValue("/linearId/externalId").asString())
  }

  private fun DriverDSL.startNode(name: CordaX500Name): NodeHandle {
    return startNode(
        defaultParameters = NodeParameters(
            providedName = name,
            additionalCordapps = listOf(
                TestCordapp.findCordapp("tech.b180.cordaptor").withConfig(mapOf("useLocalCache" to false)),
                TestCordapp.findCordapp("tech.b180.ref_cordapp")
            ),
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

private fun JsonValue.asString() = (this as JsonString).string
private fun JsonValue.asInt() = (this as JsonNumber).intValue()

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
