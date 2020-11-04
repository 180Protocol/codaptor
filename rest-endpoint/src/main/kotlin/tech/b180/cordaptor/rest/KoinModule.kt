package tech.b180.cordaptor.rest

import org.koin.core.Koin
import org.koin.core.parameter.parametersOf
import org.koin.dsl.bind
import org.koin.dsl.binds
import org.koin.dsl.module
import tech.b180.cordaptor.kernel.*
import java.time.Duration
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

  override val configPath = "openAPI"

  override fun provideModule(moduleConfig: Config) = module {
    // initializing our settings and making them available to definitions in this module
    val settings = Settings(moduleConfig)
    single { settings }

    // embedded Jetty server and its configuration
    single { settings.connectorConfiguration }
    single { JettyServer() } bind LifecycleAware::class
    single { ConnectorFactory(get(), get()) } bind JettyConfigurator::class

    // definitions for Cordaptor API endpoints handlers
    single { NodeInfoEndpoint("/node/info") } bind QueryEndpoint::class
    single { NodeVersionEndpoint("/node/version") } bind QueryEndpoint::class
    single { TransactionQueryEndpoint("/node/tx") } bind QueryEndpoint::class

    // allow OpenAPI specification and SwaggerUI to be disabled
    if (settings.isOpenAPISpecificationEnabled) {
      single { APISpecificationEndpointHandler("/api.json") } binds
          arrayOf(ContextMappedHandler::class, LifecycleAware::class)

      // if OpenAPI specification is disabled, SwaggerUI will not show regardless of the flag
      if (settings.isSwaggerUIEnabled) {
        single { SwaggerUIHandler("/swagger") } binds
            arrayOf(ContextMappedHandler::class, LifecycleAware::class)
      }
    }

    // parameterized accessor for obtaining handler instances allowing them to have dependencies managed by Koin
    factory<QueryEndpointHandler<*>> { (endpoint: QueryEndpoint<*>) -> QueryEndpointHandler(endpoint) }
    factory<OperationEndpointHandler<*, *>> { (endpoint: OperationEndpoint<*, *>) -> OperationEndpointHandler(endpoint) }

    // contributes handlers for specific flow and state endpoints
    single { NodeStateAPIProvider("/node") } bind EndpointProvider::class

    // JSON serialization enablement
    single { SerializationFactory(lazyGetAll(), lazyGetAll()) }

    single { CordaX500NameSerializer() } bind CustomSerializer::class
    single { CordaSecureHashSerializer() } bind CustomSerializer::class
    single { CordaUUIDSerializer() } bind CustomSerializer::class
    single { CordaPartySerializer(get(), get()) } bind CustomSerializer::class
    single { CordaPartyAndCertificateSerializer(get()) } bind CustomSerializer::class
    single { JavaInstantSerializer() } bind CustomSerializer::class
    single { ThrowableSerializer(get()) } bind CustomSerializer::class
    single { CordaSignedTransactionSerializer(get()) } bind CustomSerializer::class
    single { CordaTransactionSignatureSerializer(get()) } bind CustomSerializer::class
    single { CordaCoreTransactionSerializer(get()) } bind CustomSerializer::class
    single { CordaWireTransactionSerializer(get()) } bind CustomSerializer::class
    single { CordaPublicKeySerializer(get(), get()) } bind CustomSerializer::class
    single { CordaAttachmentConstraintSerializer(get()) } bind CustomSerializer::class
    single { CordaTimeWindowSerializer(get()) } bind CustomSerializer::class
    single { JsonObjectSerializer() } bind CustomSerializer::class

    single { CordaFlowInstructionSerializerFactory(get()) } bind CustomSerializerFactory::class

    // factory for requesting specific serializers into the non-generic serialization code
    factory<JsonSerializer<*>> { (key: SerializerKey) -> get<SerializationFactory>().getSerializer(key) }

    single { NodeNotifications() }
  }
}

/**
 * Eagerly-initialized typesafe wrapper for module's configuration.
 */
class Settings private constructor(
    val isOpenAPISpecificationEnabled: Boolean,
    val isSwaggerUIEnabled: Boolean,
    val isFlowSnapshotsEndpointEnabled: Boolean,
    val isUsingSecureEndpoint: Boolean,
    val listenAddress: HostAndPort,
    val maxFlowInitiationTimeout: Duration,
    val maxVaultQueryPageSize: Int
) {
  constructor(ourConfig: Config) : this(
      isOpenAPISpecificationEnabled = ourConfig.getBoolean("spec.enabled"),
      isSwaggerUIEnabled = ourConfig.getBoolean("swaggerUI.enabled"),
      isFlowSnapshotsEndpointEnabled = ourConfig.getBoolean("flowSnapshots.enabled"),
      isUsingSecureEndpoint = ourConfig.getBoolean("tls.enabled"),
      listenAddress = ourConfig.getHostAndPort("listenAddress"),
      maxFlowInitiationTimeout = ourConfig.getDuration("flowInitiation.maxTimeout"),
      maxVaultQueryPageSize = ourConfig.getInt("vaultQueries.maxPageSize")
  )

  val connectorConfiguration = JettyConnectorConfiguration(
      bindAddress = listenAddress, secure = isUsingSecureEndpoint)
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
fun <T: Any> CordaptorComponent.injectSerializer(serializerKey: SerializerKey): Lazy<JsonSerializer<T>> =
    lazy { getKoin().get<JsonSerializer<T>> { parametersOf(serializerKey) } }