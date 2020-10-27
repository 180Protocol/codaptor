package tech.b180.cordaptor.rest

import io.mockk.every
import io.mockk.mockkClass
import net.corda.core.flows.FlowLogic
import net.corda.core.identity.CordaX500Name
import net.corda.core.identity.Party
import net.corda.core.identity.PartyAndCertificate
import net.corda.core.transactions.SignedTransaction
import net.corda.testing.core.TestIdentity
import org.junit.Rule
import org.koin.dsl.bind
import org.koin.dsl.module
import org.koin.test.KoinTest
import org.koin.test.KoinTestRule
import tech.b180.cordaptor.corda.CordaFlowInstruction
import tech.b180.cordaptor.corda.CordaFlowProgress
import tech.b180.cordaptor.corda.CordaFlowSnapshot
import tech.b180.cordaptor.corda.CordaNodeState
import tech.b180.cordaptor.kernel.lazyGetAll
import java.time.Instant
import java.util.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame

class CordaTypesTest : KoinTest {

  companion object {
    private val mockNodeState = mockkClass(CordaNodeState::class)

    val serializersModule = module {
      single { SerializationFactory(lazyGetAll(), lazyGetAll()) }

      // register custom serializers for the factory to discover
      single { CordaUUIDSerializer() } bind CustomSerializer::class
      single { CordaSecureHashSerializer() } bind CustomSerializer::class
      single { CordaX500NameSerializer() } bind CustomSerializer::class
      single { CordaPartySerializer(get(), mockNodeState) } bind CustomSerializer::class
      single { CordaPartyAndCertificateSerializer(factory = get()) } bind CustomSerializer::class
      single { JavaInstantSerializer() } bind CustomSerializer::class
      single { ThrowableSerializer(get()) } bind CustomSerializer::class
      single { CordaSignedTransactionSerializer(get()) } bind CustomSerializer::class
      single { CordaTransactionSignatureSerializer(get()) } bind CustomSerializer::class
      single { CordaCoreTransactionSerializer(get()) } bind CustomSerializer::class
      single { CordaWireTransactionSerializer(get()) } bind CustomSerializer::class
      single { CordaTransactionStateSerializer(get()) } bind CustomSerializer::class
      single { CordaPublicKeySerializer(get(), mockNodeState) } bind CustomSerializer::class
      single { JsonObjectSerializer() } bind CustomSerializer::class

      single { CordaFlowInstructionSerializerFactory(get()) } bind CustomSerializerFactory::class

      // factory for requesting specific serializers into the non-generic serialization code
      factory<JsonSerializer<*>> { (key: SerializerKey) -> get<SerializationFactory>().getSerializer(key) }
    }
  }

  @get:Rule
  val koinTestRule = KoinTestRule.create {
    modules(serializersModule)
  }

  @Test
  fun `test x500 name serialization`() {
    val serializer = getKoin().getSerializer(CordaX500Name::class)
    assertEquals<Class<*>>(CordaX500NameSerializer::class.java, serializer.javaClass,
        "Parametrised resolution should yield custom serializer")

    assertEquals("""{"type":"string"}""".asJsonObject(), serializer.generateRecursiveSchema(getKoin().get()))

    assertEquals(""""O=Bank, L=London, C=GB"""",
        serializer.toJsonString(CordaX500Name.parse("O=Bank,L=London,C=GB")))

    assertEquals(CordaX500Name.parse("O=Bank,L=London,C=GB"),
        serializer.fromJson(""""O=Bank, L=London, C=GB"""".asJsonValue()))
  }

  @Test
  fun `test party serialization`() {
    val serializer = getKoin().getSerializer(Party::class)

    assertEquals(CordaPartySerializer::class.java, serializer.javaClass as Class<*>)

    assertEquals("""{"type":"object","properties":{"name":{"type":"string"}},"required":["name"]}""".asJsonObject(),
        serializer.generateRecursiveSchema(getKoin().get()))

    val party = TestIdentity(CordaX500Name("Bank", "London", "GB")).party
    assertEquals("""{"name":"O=Bank, L=London, C=GB"}""", serializer.toJsonString(party))

    every { mockNodeState.wellKnownPartyFromX500Name(party.name) }.returns(party)
    every { mockNodeState.wellKnownPartyFromX500Name(not(party.name)) }.returns(null)

    assertSame(party, serializer.fromJson("""{"name":"O=Bank, L=London, C=GB"}""".asJsonObject()),
        "Party should have been resolved through the identity service")

    assertFailsWith(SerializationException::class) {
      serializer.fromJson("""{"name":"O=UnknownBank, L=London, C=GB"}""".asJsonObject())
    }
  }

  @Test
  fun `test party and certificate serializer`() {
    val serializer = getKoin().getSerializer(PartyAndCertificate::class)
    assertEquals(CordaPartyAndCertificateSerializer::class.java, serializer.javaClass as Class<*>)

    assertEquals("""{
      |"type":"object",
      |"properties":{
      | "party":{
      |   "type":"object",
      |   "properties":{"name":{"type":"string"}},
      |   "required":["name"]}},
      |"required":["party"]}""".trimMargin().asJsonObject(),
        serializer.generateRecursiveSchema(getKoin().get()))

    val id = TestIdentity(CordaX500Name("Bank", "London", "GB")).identity

    assertEquals("""{"party":{"name":"O=Bank, L=London, C=GB"}}""", serializer.toJsonString(id))

    assertFailsWith(UnsupportedOperationException::class) {
      serializer.fromJson("""{"name":"O=UnknownBank, L=London, C=GB"}""".asJsonObject())
    }
  }

  @Test
  fun `test flows serialization`() {
    // most flows are expected to be just composable objects
    val serializer = getKoin().getSerializer(TestFlow::class)
    assertEquals(ComposableTypeJsonSerializer::class.java, serializer.javaClass as Class<*>)

    assertEquals("""{
      |"type":"object",
      |"properties":{
      | "objectParam":{
      |   "type":"object",
      |   "properties":{"intParam":{"type":"number","format":"int32"}},
      |   "required":["intParam"]
      | },
      | "stringParam":{
      |   "type":"string"
      | }
      |},
      |"required":["stringParam"]}""".trimMargin().asJsonObject(), serializer.generateRecursiveSchema(getKoin().get()))

    assertEquals(TestFlow("ABC", null),
        serializer.fromJson("""{"stringParam":"ABC"}""".asJsonObject()))
  }

  @Test
  fun `test flow snapshot serialization`() {
    val serializer = getKoin().getSerializer(CordaFlowSnapshot::class, String::class)

    val uuid = UUID.randomUUID()
    val now = Instant.now()
    assertEquals("""{
      |"flowClass":"tech.b180.cordaptor.rest.TestFlow",
      |"flowRunId":"$uuid",
      |"startedAt":"$now"}""".trimMargin().asJsonValue(), serializer.toJsonString(
        CordaFlowSnapshot(flowClass = TestFlow::class,
            result = null, flowRunId = uuid, startedAt = now,
            currentProgress = null)).asJsonValue())

    assertEquals("""{
      |"currentProgress":{"currentStepName":"Step 1","timestamp":"$now"},
      |"flowClass":"tech.b180.cordaptor.rest.TestFlow",
      |"flowRunId":"$uuid",
      |"startedAt":"$now"}""".trimMargin().asJsonValue(), serializer.toJsonString(
        CordaFlowSnapshot(flowClass = TestFlow::class,
            result = null, flowRunId = uuid, startedAt = now,
            currentProgress = CordaFlowProgress("Step 1", now))).asJsonValue())
  }

  @Test
  fun `test corda transaction serialization`() {
    val serializer = getKoin().getSerializer(SignedTransaction::class)
  }

  @Test
  fun `test corda flow instruction serialization`() {
    val serializer = getKoin().getSerializer(CordaFlowInstruction::class, TestFlow::class)

    assertEquals("""{"type":"object",
      "properties":{
      "options":{"type":"object","properties":{"trackProgress":{"type":"boolean"}},"required":[]},
      "stringParam":{"type":"string"},
      "objectParam":{"type":"object",
      "properties":{"intParam":{"type":"number","format":"int32"}},
      "required":["intParam"]}},
      "required":["stringParam"]}""".asJsonObject(), serializer.generateRecursiveSchema(getKoin().get()))

    assertEquals(CordaFlowInstruction(flowClass = TestFlow::class, options = CordaFlowInstruction.Options(true),
        arguments = mapOf("stringParam" to "ABC", "objectParam" to TestFlowParam(intParam = 123))),
        serializer.fromJson("""{"options":{"trackProgress":true},"stringParam":"ABC","objectParam":{"intParam":123}}""".asJsonObject()))

    assertEquals(CordaFlowInstruction(flowClass = TestFlow::class,
        arguments = mapOf("stringParam" to "ABC")),
        serializer.fromJson("""{"stringParam":"ABC"}""".asJsonObject()))

    assertFailsWith(SerializationException::class, message = "Missing mandatory flow class constructor parameter") {
      serializer.fromJson("""{"objectParam":{"intParam":123}}}""".asJsonObject())
    }
  }
}

data class TestFlowParam(val intParam: Int)

data class TestFlow(
    val stringParam: String,
    val objectParam: TestFlowParam?
) : FlowLogic<String>() {

  override fun call(): String {
    throw AssertionError("Not expected to be called in the test")
  }
}