package tech.b180.cordaptor.rest

import org.koin.core.qualifier.named
import org.koin.dsl.bind
import org.koin.dsl.module
import tech.b180.cordaptor.kernel.LifecycleAware
import tech.b180.cordaptor.kernel.ModuleProvider
import tech.b180.cordaptor.kernel.getHostAndPortProperty
import tech.b180.cordaptor.kernel.lazyGetAll
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

  override val module = module {
    single {
      JettyConnectorConfiguration(
        bindAddress = getHostAndPortProperty("listen.address")
      )
    }
    single { JettyServer() } bind LifecycleAware::class

    single<JettyConfigurator> { ConnectorFactory(get()) }

    // definitions for various context handlers
    single<ContextMappedHandler> { ApiDefinitionHandler("/api.json") }
    single<ContextMappedHandler> { SwaggerUIHandler("/swagger-ui.html") }
    single<ContextMappedHandler> { NodeInfoHandler("/node/info") }
    single<ContextMappedHandler> { TransactionQueryHandler("/node/tx") }
    single<ContextMappedHandler> { VaultQueryHandler("/node/states") }
    single<ContextMappedHandler> { CountingVaultQueryHandler("/node/statesCount") }
    single<ContextMappedHandler> { AggregatingVaultQueryHandler("/node/statesTotalAmount") }

    // contributes handlers for specific flow and state endpoints
    single<ContextMappedHandlerFactory> { NodeStateApiProvider("/node") }

    // JSON serialization enablement
    single { SerializationFactory(get(), lazyGetAll()) }

    single { CordaX500NameSerializer() } bind CustomSerializer::class
    single { CordaSecureHashSerializer() } bind CustomSerializer::class
    single { CordaUUIDSerializer() } bind CustomSerializer::class
    single { CordaPartySerializer(get(), get(), get()) } bind CustomSerializer::class
    single { CordaSignedTransactionSerializer(get(), get(), get()) } bind CustomSerializer::class
    single { CordaPartyAndCertificateSerializer() } bind CustomSerializer::class

    // factory for requesting specific serializers into the non-generic serialization code
    single<JsonSerializer<*>> { (clazz: KClass<*>) -> get<SerializationFactory>().getSerializer(clazz) }
  }
}
