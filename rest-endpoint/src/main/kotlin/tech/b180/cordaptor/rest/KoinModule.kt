package tech.b180.cordaptor.rest

import net.corda.core.transactions.SignedTransaction
import org.koin.dsl.bind
import org.koin.dsl.module
import tech.b180.cordaptor.kernel.LifecycleAware
import tech.b180.cordaptor.kernel.ModuleProvider
import tech.b180.cordaptor.kernel.getHostAndPortProperty
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
    single { SerializationFactory(get()) }

    // all custom object serializers are defined here for the serialization factory to discover and register
//    single<CustomSerializer<*>> { CordaSignedTransactionSerializer() }

    // for cleaner code it is possible to directly inject serializers for types known at compile-time
    // by using inject { parametersOf(<class>) } construct in a KoinComponent
//    factory { (objectClass: KClass<*>) -> get<SerializationFactory>().getObjectSerializer(objectClass.java) }
  }

  companion object {
    private val definedObjectSerializers = arrayOf(
        SignedTransaction::class
    )
  }
}
