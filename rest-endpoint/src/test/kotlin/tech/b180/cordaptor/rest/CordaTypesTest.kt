import io.mockk.every
import io.mockk.mockkClass
import net.corda.core.contracts.ContractState
import net.corda.core.flows.FlowLogic
import net.corda.core.identity.CordaX500Name
import net.corda.core.identity.Party
import net.corda.core.identity.PartyAndCertificate
import net.corda.core.node.services.IdentityService
import net.corda.core.node.services.TransactionStorage
import net.corda.core.transactions.SignedTransaction
import net.corda.testing.core.TestIdentity
import org.junit.AfterClass
import org.koin.core.context.KoinContextHandler
import org.koin.core.context.startKoin
import org.koin.core.qualifier.TypeQualifier
import org.koin.dsl.bind
import org.koin.dsl.module
import tech.b180.cordaptor.corda.CordaFlowProgress
import tech.b180.cordaptor.corda.CordaFlowSnapshot
import tech.b180.cordaptor.kernel.lazyGetAll
import tech.b180.cordaptor.rest.*
import java.time.Instant
import java.util.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame

class CordaTypesTest {

  companion object {

    private val mockIdentityService = mockkClass(IdentityService::class)
    private val mockTransactionStorage = mockkClass(TransactionStorage::class)

    private val koinApp = startKoin {
      modules(module {
        single { SerializationFactory(lazyGetAll()) }

        // register custom serializers for the factory to discover
        single { CordaUUIDSerializer() } bind CustomSerializer::class
        single { CordaSecureHashSerializer() } bind CustomSerializer::class
        single { CordaX500NameSerializer() } bind CustomSerializer::class
        single { CordaPartySerializer(get(), mockIdentityService) } bind CustomSerializer::class
        single { CordaPartyAndCertificateSerializer(factory = get()) } bind CustomSerializer::class
        single { JavaInstantSerializer() } bind CustomSerializer::class
        single { ThrowableSerializer(get()) } bind CustomSerializer::class
        single { CordaSignedTransactionSerializer(get(), mockTransactionStorage) } bind CustomSerializer::class
        single { CordaTransactionSignatureSerializer(get()) } bind CustomSerializer::class
        single { CordaCoreTransactionSerializer(get()) } bind CustomSerializer::class
        single { CordaWireTransactionSerializer(get()) } bind CustomSerializer::class
        single { CordaTransactionStateSerializer(get()) } bind CustomSerializer::class
        single { CordaPublicKeySerializer(get(), mockIdentityService) } bind CustomSerializer::class
        single(qualifier = TypeQualifier(Any::class)) { DynamicObjectSerializer(Any::class, get()) } bind CustomSerializer::class
        single(qualifier = TypeQualifier(ContractState::class)) { DynamicObjectSerializer(ContractState::class, get()) } bind CustomSerializer::class

        // factory for requesting specific serializers into the non-generic serialization code
        factory<JsonSerializer<*>> { (key: SerializerKey) -> get<SerializationFactory>().getSerializer(key) }
      })
    }

    private val koin = koinApp.koin

    @JvmStatic @AfterClass
    fun closeKoin() {
      KoinContextHandler.stop()
    }
  }

  @Test
  fun `test x500 name serialization`() {
    val serializer = koin.getSerializer(CordaX500Name::class)
    assertEquals<Class<*>>(CordaX500NameSerializer::class.java, serializer.javaClass,
        "Parametrised resolution should yield custom serializer")

    assertEquals("""{"type":"string"}""".asJsonObject(), serializer.schema)

    assertEquals(""""O=Bank, L=London, C=GB"""",
        serializer.toJsonString(CordaX500Name.parse("O=Bank,L=London,C=GB")))

    assertEquals(CordaX500Name.parse("O=Bank,L=London,C=GB"),
        serializer.fromJson(""""O=Bank, L=London, C=GB"""".asJsonValue()))
  }

  @Test
  fun `test party serialization`() {
    val serializer = koin.getSerializer(Party::class)

    assertEquals(CordaPartySerializer::class.java, serializer.javaClass as Class<*>)

    assertEquals("""{"type":"object","properties":{"name":{"type":"string"}},"required":["name"]}""".asJsonObject(),
        serializer.schema)

    val party = TestIdentity(CordaX500Name("Bank", "London", "GB")).party
    assertEquals("""{"name":"O=Bank, L=London, C=GB"}""", serializer.toJsonString(party))

    every { mockIdentityService.wellKnownPartyFromX500Name(party.name) }.returns(party)
    every { mockIdentityService.wellKnownPartyFromX500Name(not(party.name)) }.returns(null)

    assertSame(party, serializer.fromJson("""{"name":"O=Bank, L=London, C=GB"}""".asJsonObject()),
        "Party should have been resolved through the identity service")

    assertFailsWith(SerializationException::class) {
      serializer.fromJson("""{"name":"O=UnknownBank, L=London, C=GB"}""".asJsonObject())
    }
  }

  @Test
  fun `test party and certificate serializer`() {
    val serializer = koin.getSerializer(PartyAndCertificate::class)
    assertEquals(CordaPartyAndCertificateSerializer::class.java, serializer.javaClass as Class<*>)

    assertEquals("""{
      |"type":"object",
      |"properties":{
      | "party":{
      |   "type":"object",
      |   "properties":{"name":{"type":"string"}},
      |   "required":["name"]}},
      |"required":[]}""".trimMargin().asJsonObject(),
        serializer.schema)

    val id = TestIdentity(CordaX500Name("Bank", "London", "GB")).identity

    assertEquals("""{"party":{"name":"O=Bank, L=London, C=GB"}}""", serializer.toJsonString(id))

    assertFailsWith(UnsupportedOperationException::class) {
      serializer.fromJson("""{"name":"O=UnknownBank, L=London, C=GB"}""".asJsonObject())
    }
  }

  @Test
  fun `test flows serialization`() {
    // most flows are expected to be just composable objects
    val serializer = koin.getSerializer(TestFlow::class)
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
      |"required":["stringParam"]}""".trimMargin().asJsonObject(), serializer.schema)

    assertEquals(TestFlow("ABC", null),
        serializer.fromJson("""{"stringParam":"ABC"}""".asJsonObject()))
  }

  @Test
  fun `test flow snapshot serialization`() {
    val serializer = koin.getSerializer(CordaFlowSnapshot::class, String::class)

    val uuid = UUID.randomUUID()
    val now = Instant.now()
    assertEquals("""{
      |"currentProgress":{"progress":[]},
      |"flowClass":"TestFlow",
      |"flowRunId":"$uuid",
      |"result":null,
      |"startedAt":"$now"}""".trimMargin().asJsonValue(), serializer.toJsonString(
        CordaFlowSnapshot(flowClass = TestFlow::class,
            result = null, flowRunId = uuid, startedAt = now,
            currentProgress = CordaFlowProgress.noProgressInfo)).asJsonValue())
  }

  @Test
  fun `test corda transaction serialization`() {
    val serializer = koin.getSerializer(SignedTransaction::class)
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