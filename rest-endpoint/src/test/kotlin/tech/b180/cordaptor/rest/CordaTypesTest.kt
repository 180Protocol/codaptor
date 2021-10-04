package tech.b180.cordaptor.rest

import io.mockk.every
import io.mockk.mockkClass
import net.corda.core.contracts.Amount
import net.corda.core.contracts.LinearPointer
import net.corda.core.contracts.LinearState
import net.corda.core.contracts.UniqueIdentifier
import net.corda.core.crypto.Crypto
import net.corda.core.flows.FlowLogic
import net.corda.core.identity.*
import net.corda.core.transactions.SignedTransaction
import net.corda.core.utilities.OpaqueBytes
import net.corda.core.utilities.ProgressTracker
import net.corda.core.utilities.toBase58
import net.corda.core.utilities.toSHA256Bytes
import net.corda.finance.flows.AbstractCashFlow
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
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Duration
import java.time.Instant
import java.util.*
import kotlin.reflect.full.allSuperclasses
import kotlin.test.*

class CordaTypesTest : KoinTest {

  companion object {
    private val mockNodeState = mockkClass(CordaNodeState::class)

    val serializersModule = module {
      single { SerializationFactory(lazyGetAll(), lazyGetAll()) }

      // register custom serializers for the factory to discover
      single { BigDecimalSerializer() } bind CustomSerializer::class
      single { CurrencySerializer() } bind CustomSerializer::class
      single { CordaUUIDSerializer() } bind CustomSerializer::class
      single { CordaSecureHashSerializer() } bind CustomSerializer::class
      single { CordaX500NameSerializer() } bind CustomSerializer::class
      single { CordaAbstractPartySerializer(get(), mockNodeState) } bind CustomSerializer::class
      single { CordaPartySerializer(get()) } bind CustomSerializer::class
      single { CordaPartyAndCertificateSerializer(factory = get()) } bind CustomSerializer::class
      single { JavaInstantSerializer() } bind CustomSerializer::class
      single { ThrowableSerializer(get()) } bind CustomSerializer::class
      single { CordaSignedTransactionSerializer(get()) } bind CustomSerializer::class
      single { CordaTransactionSignatureSerializer(get()) } bind CustomSerializer::class
      single { CordaCoreTransactionSerializer(get()) } bind CustomSerializer::class
      single { CordaWireTransactionSerializer(get()) } bind CustomSerializer::class
      single { CordaPublicKeySerializer(get()) } bind CustomSerializer::class
      single { CordaOpaqueBytesSerializer() } bind CustomSerializer::class
      single { JsonObjectSerializer() } bind CustomSerializer::class
      single { JavaDurationSerializer() } bind CustomSerializer::class

      single { CordaFlowInstructionSerializerFactory(get()) } bind CustomSerializerFactory::class
      single { CordaAmountSerializerFactory(get()) } bind CustomSerializerFactory::class
      single { CordaLinearPointerSerializer(get()) } bind CustomSerializerFactory::class

      // factory for requesting specific serializers into the non-generic serialization code
      factory<JsonSerializer<*>> { (key: SerializerKey) -> get<SerializationFactory>().getSerializer(key) }
    }
  }

  @get:Rule
  val koinTestRule = KoinTestRule.create {
    modules(serializersModule)
  }

  @Test
  fun `test amount serializer`() {
    val serializer = getKoin().getSerializer(Amount::class, Currency::class)
    assertTrue(CustomSerializer::class in serializer::class.allSuperclasses)
    assertEquals(Amount::class.java, serializer.valueType.rawType)
    assertEquals(Currency::class.java, serializer.valueType.typeParameters[0].rawType)

    assertEquals("""{"type":"object",
      |"properties":{"quantity":{"type":"number"},"of":{"type":"string"}},
      |"required":["quantity","of"]}""".trimMargin().asJsonObject(), serializer.generateRecursiveSchema(getKoin().get()))

    val amount = Amount.fromDecimal(BigDecimal.valueOf(120),
        Currency.getInstance("GBP"), RoundingMode.UNNECESSARY)

    assertEquals("""{"quantity":120.00,"of":"GBP"}""",
        serializer.toJsonString(amount))

    assertEquals(amount,
        serializer.fromJson("""{"quantity":120,"of":"GBP"}""".asJsonValue()))
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
  fun `test abstract party serialization`() {
    val serializer = getKoin().getSerializer(AbstractParty::class)

    assertEquals(CordaAbstractPartySerializer::class.java, serializer.javaClass as Class<*>)

    assertEquals("""{"type":"object",
      "properties":{"owningKey":{"type":"object",
      "properties":{"hash":{"type":"string","readOnly":true}},"required":["hash"]},
      "name":{"type":"string"}},"required":[]}""".asJsonObject(),
        serializer.generateRecursiveSchema(getKoin().get()))

    val party = TestIdentity(CordaX500Name("Bank", "London", "GB")).party
    every { mockNodeState.partyFromKey(party.owningKey) }.returns(party)
    every { mockNodeState.partyFromKey(not(party.owningKey)) }.returns(null)

    val keyHash = party.owningKey.toSHA256Bytes().toBase58()
    assertEquals("""{"owningKey":{"hash":"$keyHash"},"name":"O=Bank, L=London, C=GB"}""",
        serializer.toJsonString(party))

    val randomKey = Crypto.generateKeyPair(Crypto.DEFAULT_SIGNATURE_SCHEME).public
    val randomKeyHash = randomKey.toSHA256Bytes().toBase58()
    assertEquals("""{"owningKey":{"hash":"$randomKeyHash"}}""",
        serializer.toJsonString(AnonymousParty(owningKey = randomKey)))

    every { mockNodeState.wellKnownPartyFromX500Name(party.name) }.returns(party)
    every { mockNodeState.wellKnownPartyFromX500Name(not(party.name)) }.returns(null)
    assertSame(party, serializer.fromJson("""{"name":"O=Bank, L=London, C=GB"}""".asJsonObject()),
        "Party should have been resolved through the identity service")

    assertFailsWith(SerializationException::class) {
      serializer.fromJson("""{"name":"O=UnknownBank, L=London, C=GB"}""".asJsonObject())
    }
  }

  @Test
  fun `test concrete party serialization`() {
    val serializer = getKoin().getSerializer(Party::class)

    assertEquals(CordaPartySerializer::class.java, serializer.javaClass as Class<*>)

    assertEquals("""{"type":"object",
      "properties":{"owningKey":{"type":"object",
      "properties":{"hash":{"type":"string","readOnly":true}},"required":["hash"]},
      "name":{"type":"string"}},"required":[]}""".asJsonObject(),
        serializer.generateRecursiveSchema(getKoin().get()))

    // CordaPartySerializer delegates to CordaAbstractPartySerializer, so it's tested above
  }

  @Test
  fun `test party and certificate serializer`() {
    val serializer = getKoin().getSerializer(PartyAndCertificate::class)
    assertEquals(CordaPartyAndCertificateSerializer::class.java, serializer.javaClass as Class<*>)

    assertEquals("""{"type":"object",
      "properties":{"party":{"type":"object",
      "properties":{"owningKey":{"type":"object",
      "properties":{"hash":{"type":"string","readOnly":true}},
      "required":["hash"]},
      "name":{"type":"string"}},
      "required":[]}},
      "required":["party"]}""".trimMargin().asJsonObject(),
        serializer.generateRecursiveSchema(getKoin().get()))

    val id = TestIdentity(CordaX500Name("Bank", "London", "GB")).identity
    every { mockNodeState.partyFromKey(id.party.owningKey) }.returns(id.party)
    every { mockNodeState.partyFromKey(not(id.party.owningKey)) }.returns(null)

    val hash = id.owningKey.toSHA256Bytes().toBase58()
    assertEquals("""{"party":{"owningKey":{"hash":"$hash"},"name":"O=Bank, L=London, C=GB"}}""",
        serializer.toJsonString(id))

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
    fun `test corda linear pointer serialization`() {
        val serializer = getKoin().getSerializer(LinearPointer::class, SimpleLinearState::class);
        val uuid = UniqueIdentifier();
        assertEquals("""{
            |"pointer": {"id": "$uuid"},
            |"type":"tech.b180.cordaptor.rest.SimpleLinearState"}""".trimMargin().asJsonValue(),
            serializer.toJsonString(LinearPointer(pointer = uuid, type= SimpleLinearState::class.java)).asJsonValue())

        assertEquals(LinearPointer(pointer = uuid, type = SimpleLinearState::class.java),
            serializer.fromJson("""{"pointer": {"id": "$uuid"}, "type":"tech.b180.cordaptor.rest.SimpleLinearState"}""".asJsonObject()))
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

    @Test
    fun `test corda non-composable serialization`() {
        val serializer = getKoin().getSerializer(CordaFlowInstruction::class, TestNonComposableFlow::class)

        println("Serializer Schema: " + serializer.generateRecursiveSchema(getKoin().get()))

        assertEquals(CordaFlowInstruction(flowClass = TestNonComposableFlow::class,
            arguments = mapOf("testProperty" to "ABC")),
            serializer.fromJson("""{"testProperty":"ABC"}""".asJsonObject()))
    }

  @Test
  fun `test corda opaque bytes serialization`() {
    val serializer = getKoin().getSerializer(OpaqueBytes::class)

    assertEquals("""{"type":"string","format":"base64"}""".asJsonObject(),
        serializer.generateRecursiveSchema(getKoin().get()))

    // "VEVTVA==" is base64 encoding of string "TEST" in UTF-8 charset
    assertEquals(""""VEVTVA=="""",
        serializer.toJsonString(OpaqueBytes("TEST".toByteArray(Charsets.UTF_8))))

    assertEquals(OpaqueBytes("TEST".toByteArray(Charsets.UTF_8)),
        serializer.fromJson(""""VEVTVA=="""".asJsonValue()))
  }

    @Test
    fun `test java duration serialization`() {
        val serializer = getKoin().getSerializer(Duration::class)

        assertEquals("""{"type":"string","format":"iso-8601-duration"}""".asJsonObject(),
                serializer.generateRecursiveSchema(getKoin().get()))

        // "PT4H" is string for duration of 4 hours
        var testString = "\"PT4H\""

        assertEquals(testString,
                serializer.toJsonString(Duration.ofHours(4)))

        assertEquals(Duration.ofHours(4),
                serializer.fromJson(testString.asJsonValue()))
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

data class SimpleLinearState(
    val participant: Party,
    override val linearId: UniqueIdentifier) : LinearState {
    override val participants: List<AbstractParty>
        get() = listOf(participant)
}

data class TestNonComposableFlow(
    val testProperty: String,
    override val progressTracker: ProgressTracker
) : FlowLogic<String>() {
    constructor(testProperty: String) : this(testProperty, ProgressTracker(
        AbstractCashFlow.Companion.GENERATING_TX,
        AbstractCashFlow.Companion.SIGNING_TX,
        AbstractCashFlow.Companion.FINALISING_TX
    ))

    override fun call(): String {
        throw AssertionError("Not expected to be called in the test")
    }
}