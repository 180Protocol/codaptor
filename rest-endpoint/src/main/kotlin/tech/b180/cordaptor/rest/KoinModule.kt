package tech.b180.cordaptor.rest

import net.corda.core.contracts.ContractState
import org.koin.core.Koin
import org.koin.core.parameter.parametersOf
import org.koin.core.qualifier.TypeQualifier
import org.koin.dsl.bind
import org.koin.dsl.module
import tech.b180.cordaptor.kernel.*
import java.lang.reflect.Type
import javax.json.Json
import kotlin.reflect.KClass

/**
 * Implementation of the microkernel module provider that makes the components
 * of this module available for injection into other modules' components.
 *
 * This class is instantiated by the microkernel at runtime using [java.util.ServiceLoader].
 */
@Suppress("UNUSED")
class RestEndpointModuleProvider : ModuleProvider {
  override val salience = 100

  override fun provideModule(settings: BootstrapSettings) = module {
    single {
      JettyConnectorConfiguration(
        bindAddress = getHostAndPortProperty("listen.address")
      )
    }
    single { JettyServer() } bind LifecycleAware::class

    single { ConnectorFactory(get()) } bind JettyConfigurator::class

    // definitions for Cordaptor API endpoints handlers
    single { NodeInfoEndpoint("/node/info") } bind QueryEndpoint::class
    single { NodeVersionEndpoint("/node/version") } bind QueryEndpoint::class

    single { ApiDefinitionHandler("/api.json") } bind ContextMappedHandler::class
    single { SwaggerUIHandler("/swagger-ui.html") } bind ContextMappedHandler::class
    single { TransactionQueryHandler("/node/tx") } bind ContextMappedHandler::class
    single { VaultQueryHandler("/node/states") } bind ContextMappedHandler::class
    single { CountingVaultQueryHandler("/node/statesCount") } bind ContextMappedHandler::class
    single { AggregatingVaultQueryHandler("/node/statesTotalAmount") } bind ContextMappedHandler::class

    // parameterized accessor for obtaining handler instances allowing them to have dependencies managed by Koin
    factory<QueryEndpointHandler<*>> { (endpoint: QueryEndpoint<*>) -> QueryEndpointHandler(endpoint) }

    // contributes handlers for specific flow and state endpoints
    single { NodeStateApiProvider("/node") } bind ContextMappedHandlerFactory::class

    // JSON serialization enablement
    single { SerializationFactory(lazyGetAll()) }

    single { CordaX500NameSerializer() } bind CustomSerializer::class
    single { CordaSecureHashSerializer() } bind CustomSerializer::class
    single { CordaUUIDSerializer() } bind CustomSerializer::class
    single { CordaPartySerializer(get(), get()) } bind CustomSerializer::class
    single { CordaPartyAndCertificateSerializer(get()) } bind CustomSerializer::class
    single { JavaInstantSerializer() } bind CustomSerializer::class
    single { ThrowableSerializer(get()) } bind CustomSerializer::class
    single { CordaSignedTransactionSerializer(get(), get()) } bind CustomSerializer::class
    single { CordaCoreTransactionSerializer(get()) } bind CustomSerializer::class
    single { CordaWireTransactionSerializer(get()) } bind CustomSerializer::class
    single { CordaTransactionStateSerializer(get()) } bind CustomSerializer::class
    single { CordaPublicKeySerializer(get(), get()) } bind CustomSerializer::class
    single(qualifier = TypeQualifier(Any::class)) { DynamicObjectSerializer(Any::class, get()) } bind CustomSerializer::class
    single(qualifier = TypeQualifier(ContractState::class)) { DynamicObjectSerializer(ContractState::class, get()) } bind CustomSerializer::class

    // factory for requesting specific serializers into the non-generic serialization code
    factory<JsonSerializer<*>> { (key: SerializerKey) -> get<SerializationFactory>().getSerializer(key) }
  }
}

/**
 * A shorthand to be used in the client code requesting a JSON serializer
 * to be injected using given type information
 */
fun <T: Any> Koin.getSerializer(clazz: KClass<T>, vararg typeParameters: KClass<*>) =
    get<JsonSerializer<T>> { parametersOf(SerializerKey(clazz, *typeParameters)) }

/**
 * A shorthand to be used by an instance of [CordaptorComponent] requesting a JSON serializer
 * to be injected using given type information
 */
fun <T: Any> CordaptorComponent.injectSerializer(clazz: KClass<T>, vararg typeParameters: KClass<*>): Lazy<JsonSerializer<T>> =
    lazy { getKoin().get<JsonSerializer<T>> { parametersOf(SerializerKey(clazz, *typeParameters)) } }

/**
 * A shorthand to be used by an instance of [CordaptorComponent] requesting a JSON serializer
 * to be injected using given type information
 */
fun <T: Any> CordaptorComponent.injectSerializer(type: Type): Lazy<JsonSerializer<T>> =
    lazy { getKoin().get<JsonSerializer<T>> { parametersOf(SerializerKey.forType(type)) } }