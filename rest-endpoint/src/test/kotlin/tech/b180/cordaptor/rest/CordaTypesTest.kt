import net.corda.core.identity.CordaX500Name
import net.corda.serialization.internal.AllWhitelist
import net.corda.serialization.internal.amqp.CachingCustomSerializerRegistry
import net.corda.serialization.internal.amqp.CustomSerializerRegistry
import net.corda.serialization.internal.amqp.DefaultDescriptorBasedSerializerRegistry
import net.corda.serialization.internal.amqp.WhitelistBasedTypeModelConfiguration
import net.corda.serialization.internal.model.ConfigurableLocalTypeModel
import net.corda.serialization.internal.model.LocalTypeModel
import net.corda.serialization.internal.model.LocalTypeModelConfiguration
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

class CordaTypesTest {

  companion object {

    private val koinApp = startKoin {
      modules(module {
        single<CustomSerializerRegistry> { CachingCustomSerializerRegistry(DefaultDescriptorBasedSerializerRegistry()) }
        single<LocalTypeModelConfiguration> { WhitelistBasedTypeModelConfiguration(AllWhitelist, get()) }
        single<LocalTypeModel> { ConfigurableLocalTypeModel(get()) }
        single { SerializationFactory(get(), lazyGetAll()) }

        // register custom serializers for the factory to discover
        single { CordaX500NameSerializer() } bind CustomSerializer::class

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
}