package tech.b180.cordaptor_test

import net.corda.core.contracts.StateRef
import net.corda.core.crypto.SecureHash
import org.eclipse.jetty.client.HttpClient
import org.eclipse.jetty.client.util.MultiPartContentProvider
import org.eclipse.jetty.client.util.PathContentProvider
import org.eclipse.jetty.client.util.StringContentProvider
import org.eclipse.jetty.http.HttpHeader
import org.junit.jupiter.api.assertDoesNotThrow
import tech.b180.ref_cordapp.DelayedProgressFlow
import tech.b180.ref_cordapp.SimpleFlow
import java.io.StringReader
import java.nio.file.Paths
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
    private val baseUrl: String,
    private val localCacheEnabled: Boolean
) {

  fun runTests() {
    val client = HttpClient()
    client.isFollowRedirects = false

    client.start()

    testOpenAPISpecification(client)
    testNodeInfoRequest(client)
    testFlowFireAndForget(client, localCacheEnabled)
    testFlowWaitWithTimeout(client, true, localCacheEnabled)
    testFlowWaitWithTimeout(client, false, localCacheEnabled)
    val stateRef = testFlowWaitForCompletion(client, localCacheEnabled)
    testTransactionQuery(client, stateRef.txhash)
    testStateQuery(client, stateRef)
    testVaultQueryViaGET(client)
    testVaultQueryViaPOST(client)
    testNodeAttachmentViaPOST(client)
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

  private fun testFlowFireAndForget(client: HttpClient, localCacheEnabled: Boolean) {
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

    if (localCacheEnabled) {
      assertTrue(response.headers.contains(HttpHeader.LOCATION))

      val flowSnapshotUrl = response.headers.get(HttpHeader.LOCATION)!!
      val flowRunId = handle.getValue("/flowRunId").asString()
      assertEquals("$baseUrl/node/reference/SimpleFlow/snapshot/$flowRunId", flowSnapshotUrl)
    } else {
      assertFalse(response.headers.contains(HttpHeader.LOCATION))
    }
  }

  private fun testFlowWaitWithTimeout(client: HttpClient, trackProgress: Boolean, localCacheEnabled: Boolean) {
    val maxRequestTime = Duration.ofSeconds(4)    // more than wait parameter, less than delay
    val req = client.POST("$baseUrl/node/reference/DelayedProgressFlow?wait=2")

    val content = """{
      |"externalId":"TEST-222",
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

    val snapshot = response.contentAsString.asJsonObject()
    assertEquals(DelayedProgressFlow::class.qualifiedName, snapshot.getValue("/flowClass").asString())
    assertFalse(snapshot.containsKey("result"))
    if (trackProgress) {
      assertEquals(JsonValue.ValueType.OBJECT, snapshot.getValue("/currentProgress").valueType)
      assertEquals("Sleeping", snapshot.getValue("/currentProgress/currentStepName").asString())

      val lastProgressTimestamp = Instant.parse(snapshot.getValue("/currentProgress/timestamp").asString())
      assertTrue(lastProgressTimestamp > requestTimestamp)
      assertTrue(lastProgressTimestamp < Instant.now())

    } else {
      assertFalse(snapshot.containsKey("currentProgress"))
    }

    if (localCacheEnabled) {
      val flowSnapshotUrl = response.headers.get(HttpHeader.LOCATION)
      val snapshotResponse = client.GET(flowSnapshotUrl)
      assertEquals(HttpServletResponse.SC_OK, snapshotResponse.status)
      assertEquals("application/json", snapshotResponse.mediaType)

      val cachedSnapshot = snapshotResponse.contentAsString.asJsonObject()
      assertEquals(snapshot, cachedSnapshot)
    }
  }

  private fun testFlowWaitForCompletion(client: HttpClient, localCacheEnabled: Boolean): StateRef {
    val maxRequestTime = Duration.ofSeconds(5)
    val req = client.POST("$baseUrl/node/reference/DelayedProgressFlow?wait=100")

    val content = """{
      |"externalId":"TEST-333",
      |"delay":1}""".trimMargin()

    req.content(StringContentProvider("application/json", content, Charsets.UTF_8))
    val requestTimestamp = Instant.now()
    val response = req.send()
    assertEquals(HttpServletResponse.SC_OK, response.status)
    assertEquals("application/json", response.mediaType)
    assertTrue(Instant.now() < requestTimestamp + maxRequestTime,
        "Request should have completed before the flow, " +
            "took ${Duration.between(requestTimestamp, Instant.now()).toMillis()}ms")

    val snapshot = response.contentAsString.asJsonObject()
    assertEquals(DelayedProgressFlow::class.qualifiedName!!.asJsonValue(), snapshot.getValue("/flowClass"))
    assertEquals(JsonValue.ValueType.STRING, snapshot.getValue("/flowRunId").valueType)
    assertEquals(JsonValue.ValueType.OBJECT, snapshot.getValue("/result").valueType)
    assertFalse(snapshot.containsKey("currentProgress"))

    val state = snapshot.getValue("/result/value/output/state/data").asJsonObject()
    assertEquals("TEST-333", state.getValue("/linearId/externalId").asString())
    assertEquals(nodeName, state.getValue("/participant/name").asString())

    if (localCacheEnabled) {
      val flowSnapshotUrl = response.headers.get(HttpHeader.LOCATION)
      val snapshotResponse = client.GET(flowSnapshotUrl)
      assertEquals(HttpServletResponse.SC_NOT_FOUND, snapshotResponse.status,
          "Flow snapshot should have been evicted after flow completion")
    }

    return StateRef(SecureHash.parse(snapshot.getValue("/result/value/output/ref/txhash").asString()),
        snapshot.getValue("/result/value/output/ref/index").asInt())
  }

  private fun testTransactionQuery(client: HttpClient, txid: SecureHash) {
    val response = client.GET("$baseUrl/node/tx/${txid}")

    val tx = response.contentAsString.asJsonObject()
    assertEquals(txid.toString(), tx.getString("id"))
    assertEquals("wireTransaction",
        tx.getValue("/content/type").asString())
    assertEquals("TEST-333",
        tx.getValue("/content/outputs/0/data/linearId/externalId").asString())
  }

  private fun testStateQuery(client: HttpClient, stateRef: StateRef) {
    val response = client.GET(
        "$baseUrl/node/reference/SimpleLinearState/${stateRef}")

    assertEquals(HttpServletResponse.SC_OK, response.status)
    assertEquals("application/json", response.mediaType)

    val state = response.contentAsString.asJsonObject()
    assertEquals("TEST-333",
        state.getValue("/linearId/externalId").asString())
  }

  private fun testVaultQueryViaGET(client: HttpClient) {
    val response = client.GET(
        "$baseUrl/node/reference/SimpleLinearState/query?externalId=TEST-333")

    assertEquals(HttpServletResponse.SC_OK, response.status)
    assertEquals("application/json", response.mediaType)

    val page = response.contentAsString.asJsonObject()
    assertEquals(1, page.getInt("totalStatesAvailable"))
  }

  private fun testNodeAttachmentViaPOST(client: HttpClient) {
    val req = client.POST(
        "$baseUrl/node/uploadNodeAttachment")

    val multiPartContentProvider = MultiPartContentProvider()

    multiPartContentProvider.addFieldPart("filename",  StringContentProvider("testData.csv"), null)
    multiPartContentProvider.addFieldPart("dataType",  StringContentProvider("testDataType"), null)
    multiPartContentProvider.addFieldPart("uploader",  StringContentProvider("User"), null)
    multiPartContentProvider.addFilePart("data",  "testData.csv",
      PathContentProvider(Paths.get(CordaptorAPITestSuite::class.java.classLoader.getResource("testData.csv").toURI())), null)

    multiPartContentProvider.close()
    req.content(multiPartContentProvider)
    val response = req.send()

    assertEquals("application/json", response.mediaType)
    assertEquals(HttpServletResponse.SC_ACCEPTED, response.status)
    assertDoesNotThrow { SecureHash.parse(response.contentAsString) }

    /*val page = response.contentAsString.asJsonObject()
    assertEquals(1, page.getInt("totalStatesAvailable"))*/
  }

  private fun testVaultQueryViaPOST(client: HttpClient) {
    val req = client.POST(
      "$baseUrl/node/reference/SimpleLinearState/query")

    val content = """{
      |"contractStateClass":"tech.b180.ref_cordapp.SimpleLinearState",
      |"linearStateExternalIds":["TEST-333"]}""".trimMargin()

    req.content(StringContentProvider("application/json", content, Charsets.UTF_8))
    val response = req.send()

    assertEquals(HttpServletResponse.SC_OK, response.status)
    assertEquals("application/json", response.mediaType)

    val page = response.contentAsString.asJsonObject()
    assertEquals(1, page.getInt("totalStatesAvailable"))
  }
}

fun String.asJsonObject() = Json.createReader(StringReader(this)).readObject()
fun String.asJsonValue() = Json.createValue(this)
fun Int.asJsonValue() = Json.createValue(this)

fun JsonValue.asString() = (this as JsonString).string
fun JsonValue.asInt() = (this as JsonNumber).intValue()