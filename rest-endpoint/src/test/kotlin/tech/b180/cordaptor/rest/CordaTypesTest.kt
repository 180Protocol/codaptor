import net.corda.core.identity.CordaX500Name
import net.corda.core.identity.PartyAndCertificate
import net.corda.core.node.NodeInfo
import net.corda.core.utilities.NetworkHostAndPort
import net.corda.serialization.internal.AllWhitelist
import net.corda.serialization.internal.amqp.CachingCustomSerializerRegistry
import net.corda.serialization.internal.amqp.CustomSerializerRegistry
import net.corda.serialization.internal.amqp.DefaultDescriptorBasedSerializerRegistry
import net.corda.serialization.internal.amqp.WhitelistBasedTypeModelConfiguration
import net.corda.serialization.internal.model.ConfigurableLocalTypeModel
import net.corda.serialization.internal.model.LocalTypeModel
import net.corda.serialization.internal.model.LocalTypeModelConfiguration
import org.junit.BeforeClass
import org.koin.core.context.startKoin
import org.koin.core.parameter.parametersOf
import org.koin.core.qualifier.named
import org.koin.dsl.bind
import org.koin.dsl.module
import tech.b180.cordaptor.rest.*
import java.security.cert.CertPath
import kotlin.reflect.KClass
import kotlin.test.Test
import kotlin.test.assertEquals

class CordaTypesTest {

  companion object {

    val koin = startKoin {
      modules(module {
        single<CustomSerializerRegistry> { CachingCustomSerializerRegistry(DefaultDescriptorBasedSerializerRegistry()) }
        single<LocalTypeModelConfiguration> { WhitelistBasedTypeModelConfiguration(AllWhitelist, get()) }
        single<LocalTypeModel> { ConfigurableLocalTypeModel(get()) }
        single { SerializationFactory(get(), lazy { getAll<CustomSerializer<Any>>(CustomSerializer::class) }) }

        // register custom serializers for the factory to discover
        single { CordaX500NameSerializer() } bind CustomSerializer::class

        // factory for requesting specific serializers into the non-generic serialization code
        single<JsonSerializer<*>> { (clazz: KClass<*>) -> get<SerializationFactory>().getSerializer(clazz) }
      })
    }.koin
  }

  @Test
  fun `test x500 name serialization`() {
    val serializer = koin.get<SerializationFactory>().getSerializer(CordaX500Name::class)
    assertEquals<Class<*>>(CordaX500NameSerializer::class.java, serializer.javaClass)

    assertEquals("""{"type":"string"}""".asJsonObject(), serializer.schema)

    assertEquals(""""O=Bank, L=London, C=GB"""",
        serializer.toJsonString(CordaX500Name.parse("O=Bank,L=London,C=GB")))

    assertEquals(CordaX500Name.parse("O=Bank,L=London,C=GB"),
        serializer.fromJson(""""O=Bank, L=London, C=GB"""".asJsonValue()))
  }
}