package tech.b180.cordaptor_test

import net.corda.core.contracts.StateRef
import net.corda.core.crypto.SecureHash
import org.eclipse.jetty.client.HttpClient
import org.eclipse.jetty.client.util.StringContentProvider
import tech.b180.ref_cordapp.DelayedProgressFlow
import tech.b180.ref_cordapp.SimpleFlow
import java.io.StringReader
import java.time.Duration
import java.time.Instant
import javax.json.Json
import javax.json.JsonNumber
import javax.json.JsonString
import javax.json.JsonValue
import javax.servlet.http.HttpServletResponse
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CordaptorAPITestSuite(
    private val nodeName: String,
    private val baseUrl: String) {

  fun runTests() {
    val client = HttpClient()
    client.isFollowRedirects = false

    client.start()

    testOpenAPISpecification(client)
    testNodeInfoRequest(client)
    testFlowFireAndForget(client)
    testFlowWaitWithTimeout(client, true)
    testFlowWaitWithTimeout(client, false)
    val stateRef = testFlowWaitForCompletion(client)
    testTransactionQuery(client, stateRef.txhash)
    testStateQuery(client, stateRef)
    testVaultQueryViaGET(client)
    testVaultQueryViaPOST(client)
  }

  private fun testOpenAPISpecification(client: HttpClient) {
    val response = client.GET("$baseUrl/api.json")
    assertEquals(HttpServletResponse.SC_OK, response.status)
    assertEquals("application/json", response.mediaType)

    val spec = response.contentAsString.asJsonObject()

    val expectedSpec = javaClass.getResource("/ReferenceCordapp.api.json")
        .openStream().reader().readText().asJsonObject()

    assertEquals(expectedSpec, spec)
  }

  private fun testNodeInfoRequest(client: HttpClient) {
    val response = client.GET("$baseUrl/node/info")
    assertEquals(HttpServletResponse.SC_OK, response.status)
    assertEquals("application/json", response.mediaType)

    val nodeInfo = response.contentAsString.asJsonObject()
    assertEquals("localhost".asJsonValue(), nodeInfo.getValue("/addresses/0/host"))
    assertEquals(nodeName.asJsonValue(), nodeInfo.getValue("/legalIdentitiesAndCerts/0/party/name"))
    assertEquals(7.asJsonValue(), nodeInfo.getValue("/platformVersion"))
    assertEquals(JsonValue.ValueType.NUMBER, nodeInfo.getValue("/serial").valueType)
  }

  private fun testFlowFireAndForget(client: HttpClient) {
    val req = client.POST("$baseUrl/node/reference/SimpleFlow")

    val content = """{
      |"externalId":"TEST-111"}""".trimMargin()

    req.content(StringContentProvider("application/json", content, Charsets.UTF_8))
    val response = req.send()
    assertEquals(HttpServletResponse.SC_ACCEPTED, response.status)
    assertEquals("application/json", response.mediaType)

    val handle = response.contentAsString.asJsonObject()
    assertEquals(SimpleFlow::class.qualifiedName!!.asJsonValue(), handle.getValue("/flowClass"))
    assertEquals(JsonValue.ValueType.STRING, handle.getValue("/flowRunId").valueType)
    assertFalse(handle.containsKey("result"))
  }

  private fun testFlowWaitWithTimeout(client: HttpClient, trackProgress: Boolean) {
    val maxRequestTime = Duration.ofSeconds(4)    // more than wait parameter, less than delay
    val req = client.POST("$baseUrl/node/reference/DelayedProgressFlow?wait=2")

    val content = """{
      |"externalId":"TEST-111",
      |"delay":5,
      |"options":{"trackProgress":$trackProgress}}""".trimMargin()

    req.content(StringContentProvider("application/json", content, Charsets.UTF_8))
    val requestTimestamp = Instant.now()
    val response = req.send()
    assertTrue(Instant.now() < requestTimestamp + maxRequestTime,
        "Request should have completed before the flow, " +
            "took ${Duration.between(requestTimestamp, Instant.now()).toMillis()}ms")
    assertEquals(HttpServletResponse.SC_ACCEPTED, response.status)
    assertEquals("application/json", response.mediaType)

    val handle = response.contentAsString.asJsonObject()
    assertEquals(DelayedProgressFlow::class.qualifiedName, handle.getValue("/flowClass").asString())
    assertFalse(handle.containsKey("result"))
    if (trackProgress) {
      assertEquals(JsonValue.ValueType.OBJECT, handle.getValue("/currentProgress").valueType)
      assertEquals("Sleeping", handle.getValue("/currentProgress/currentStepName").asString())

      val lastProgressTimestamp = Instant.parse(handle.getValue("/currentProgress/timestamp").asString())
      assertTrue(lastProgressTimestamp > requestTimestamp)
      assertTrue(lastProgressTimestamp < Instant.now())

    } else {
      assertFalse(handle.containsKey("currentProgress"))
    }
  }

  private fun testFlowWaitForCompletion(client: HttpClient): StateRef {
    val maxRequestTime = Duration.ofSeconds(5)
    val req = client.POST("$baseUrl/node/reference/SimpleFlow?wait=100")

    val content = """{
      |"externalId":"TEST-111"}""".trimMargin()

    req.content(StringContentProvider("application/json", content, Charsets.UTF_8))
    val requestTimestamp = Instant.now()
    val response = req.send()
    assertEquals(HttpServletResponse.SC_OK, response.status)
    assertEquals("application/json", response.mediaType)
    assertTrue(Instant.now() < requestTimestamp + maxRequestTime,
        "Request should have completed before the flow, " +
            "took ${Duration.between(requestTimestamp, Instant.now()).toMillis()}ms")

    val handle = response.contentAsString.asJsonObject()
    assertEquals(SimpleFlow::class.qualifiedName!!.asJsonValue(), handle.getValue("/flowClass"))
    assertEquals(JsonValue.ValueType.STRING, handle.getValue("/flowRunId").valueType)
    assertEquals(JsonValue.ValueType.OBJECT, handle.getValue("/result").valueType)
    assertFalse(handle.containsKey("currentProgress"))

    val state = handle.getValue("/result/value/output/state/data").asJsonObject()
    assertEquals("TEST-111", state.getValue("/linearId/externalId").asString())
    assertEquals(nodeName, state.getValue("/participant/name").asString())

    return StateRef(SecureHash.parse(handle.getValue("/result/value/output/ref/txhash").asString()),
        handle.getValue("/result/value/output/ref/index").asInt())
  }

  private fun testTransactionQuery(client: HttpClient, txid: SecureHash) {
    val response = client.GET("$baseUrl/node/tx/${txid}")

    val tx = response.contentAsString.asJsonObject()
    assertEquals(txid.toString(), tx.getString("id"))
    assertEquals("wireTransaction",
        tx.getValue("/content/type").asString())
    assertEquals("TEST-111",
        tx.getValue("/content/outputs/0/data/linearId/externalId").asString())
  }

  private fun testStateQuery(client: HttpClient, stateRef: StateRef) {
    val response = client.GET(
        "$baseUrl/node/reference/SimpleLinearState/${stateRef}")

    assertEquals(HttpServletResponse.SC_OK, response.status)
    assertEquals("application/json", response.mediaType)

    val state = response.contentAsString.asJsonObject()
    assertEquals("TEST-111",
        state.getValue("/linearId/externalId").asString())
  }

  private fun testVaultQueryViaGET(client: HttpClient) {
    val response = client.GET(
        "$baseUrl/node/reference/SimpleLinearState/query?externalId=TEST-111")

    assertEquals(HttpServletResponse.SC_OK, response.status)
    assertEquals("application/json", response.mediaType)

    val page = response.contentAsString.asJsonObject()
    assertEquals(2, page.getInt("totalStatesAvailable"))
  }

  private fun testVaultQueryViaPOST(client: HttpClient) {
    val req = client.POST(
        "$baseUrl/node/reference/SimpleLinearState/query")

    val content = """{
      |"contractStateClass":"tech.b180.ref_cordapp.SimpleLinearState",
      |"linearStateExternalIds":["TEST-111"]}""".trimMargin()

    req.content(StringContentProvider("application/json", content, Charsets.UTF_8))
    val response = req.send()

    assertEquals(HttpServletResponse.SC_OK, response.status)
    assertEquals("application/json", response.mediaType)

    val page = response.contentAsString.asJsonObject()
    assertEquals(2, page.getInt("totalStatesAvailable"))
  }
}

fun String.asJsonObject() = Json.createReader(StringReader(this)).readObject()
fun String.asJsonValue() = Json.createValue(this)
fun Int.asJsonValue() = Json.createValue(this)

fun JsonValue.asString() = (this as JsonString).string
fun JsonValue.asInt() = (this as JsonNumber).intValue()