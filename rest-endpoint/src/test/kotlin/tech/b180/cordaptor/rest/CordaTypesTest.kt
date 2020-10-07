import io.mockk.every
import io.mockk.mockkClass
import net.corda.core.flows.FlowLogic
import net.corda.core.identity.CordaX500Name
import net.corda.core.identity.Party
import net.corda.core.identity.PartyAndCertificate
import net.corda.core.node.services.IdentityService
import net.corda.core.transactions.SignedTransaction
import net.corda.serialization.internal.AllWhitelist
import net.corda.serialization.internal.amqp.CachingCustomSerializerRegistry
import net.corda.serialization.internal.amqp.CustomSerializerRegistry
import net.corda.serialization.internal.amqp.DefaultDescriptorBasedSerializerRegistry
import net.corda.serialization.internal.amqp.WhitelistBasedTypeModelConfiguration
import net.corda.serialization.internal.model.ConfigurableLocalTypeModel
import net.corda.serialization.internal.model.LocalTypeModel
import net.corda.serialization.internal.model.LocalTypeModelConfiguration
import net.corda.testing.core.TestIdentity
import org.junit.AfterClass
import org.koin.core.context.KoinContextHandler
import org.koin.core.context.startKoin
import org.koin.core.parameter.parametersOf
import org.koin.dsl.bind
import org.koin.dsl.module
import tech.b180.cordaptor.kernel.lazyGetAll
import tech.b180.cordaptor.rest.*
import kotlin.reflect.KClass
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame

class CordaTypesTest {

  companion object {

    private val mockIdentityService = mockkClass(IdentityService::class)

    private val koinApp = startKoin {
      modules(module {
        single<CustomSerializerRegistry> { CachingCustomSerializerRegistry(DefaultDescriptorBasedSerializerRegistry()) }
        single<LocalTypeModelConfiguration> { WhitelistBasedTypeModelConfiguration(AllWhitelist, get()) }
        single<LocalTypeModel> { ConfigurableLocalTypeModel(get()) }
        single { SerializationFactory(get(), lazyGetAll()) }

        // register custom serializers for the factory to discover
        single { CordaX500NameSerializer() } bind CustomSerializer::class
        single { CordaPartySerializer(factory = get(), localTypeModel = get(),
            identityService = mockIdentityService) } bind CustomSerializer::class
        single { CordaPartyAndCertificateSerializer(factory = get()) } bind CustomSerializer::class

        // factory for requesting specific serializers into the non-generic serialization code
        single<JsonSerializer<*>> { (clazz: KClass<*>) -> get<SerializationFactory>().getSerializer(clazz) }
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
    val serializer = koin.get<SerializationFactory>().getSerializer(CordaX500Name::class)
    assertEquals<Class<*>>(CordaX500NameSerializer::class.java, serializer.javaClass)

    assertEquals(serializer, koin.get { parametersOf(CordaX500Name::class) },
    "Parametrised resolution should yield custom serializer")

    assertEquals("""{"type":"string"}""".asJsonObject(), serializer.schema)

    assertEquals(""""O=Bank, L=London, C=GB"""",
        serializer.toJsonString(CordaX500Name.parse("O=Bank,L=London,C=GB")))

    assertEquals(CordaX500Name.parse("O=Bank,L=London,C=GB"),
        serializer.fromJson(""""O=Bank, L=London, C=GB"""".asJsonValue()))
  }

  @Test
  fun `test party serialization`() {
    val serializer = koin.get<SerializationFactory>().getSerializer(Party::class)
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
    val serializer =
        koin.get<SerializationFactory>().getSerializer(PartyAndCertificate::class)
    assertEquals(CordaPartyAndCertificateSerializer::class.java, serializer.javaClass as Class<*>)

    assertEquals("""{"type":"object","properties":{"name":{"type":"string"}},"required":["name"]}""".asJsonObject(),
        serializer.schema)

    val id = TestIdentity(CordaX500Name("Bank", "London", "GB")).identity

    assertEquals("""{"name":"O=Bank, L=London, C=GB"}""", serializer.toJsonString(id))

    assertFailsWith(UnsupportedOperationException::class) {
      serializer.fromJson("""{"name":"O=UnknownBank, L=London, C=GB"}""".asJsonObject())
    }
  }

  @Test
  fun `test flows serialization`() {
    // most flows are expected to be just composable objects
    val serializer = koin.get<SerializationFactory>().getSerializer(TestFlow::class)
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
}

data class TestFlowParam(val intParam: Int)

data class TestFlow(
    val stringParam: String,
    val objectParam: TestFlowParam?
) : FlowLogic<SignedTransaction>() {

  override fun call(): SignedTransaction {
    throw AssertionError("Not expected to be called in the test")
  }
}