package tech.b180.cordaptor.rest

import io.undertow.Undertow
import io.undertow.server.HttpHandler
import io.undertow.server.handlers.PathHandler
import org.koin.core.get
import org.koin.core.inject
import org.koin.core.parameter.parametersOf
import org.koin.core.qualifier.named
import tech.b180.cordaptor.kernel.*
import javax.net.ssl.SSLContext

/**
 * Describes a single handler for a particular path prefix used to route API requests.
 */
@ModuleAPI(since = "0.1")
interface ContextMappedHandler : HttpHandler {
  val mappingParameters: Parameters

  /** Specific set of parameters for the handler */
  data class Parameters(
      val path: String,
      val exactPathOnly: Boolean
  )
}

/**
 * Implementations contribute some aspect of configuration to the server.
 */
@ModuleAPI(since = "0.1")
interface UndertowConfigContributor {
  fun contribute(builder: Undertow.Builder)
}

/**
 * Wrapper for configuration section that is fed into the listener configuration.
 * We are not eagerly parsing config line by line to reduce the chance of a typo.
 */
data class SecureTransportSettings(
    val enabled: Boolean,
    val tlsConfig: Config
) : Config by tlsConfig {
  constructor(tlsConfig: Config) : this(
      enabled = tlsConfig.getBoolean("enabled"),
      tlsConfig = tlsConfig
  )
}

/**
 * Contains logic for configuring listeners for HTTP connections, optionally with SSL enabled.
 */
class UndertowListenerContributor(
    private val settings: WebServerSettings
) : UndertowConfigContributor {

  override fun contribute(builder: Undertow.Builder) {
    val address = settings.bindAddress
    if (settings.isSecure) {
      builder.addHttpsListener(address.port, address.hostname, SSLContext.getDefault())
    } else {
      builder.addHttpListener(address.port, address.hostname)
    }
  }
}

/**
 * Contains logic for configuring path handlers for HTTP requests,
 * as well as an overarching security handler.
 */
class UndertowHandlerContributor(
    private val securitySettings: SecuritySettings
) : UndertowConfigContributor, CordaptorComponent {

  companion object {
    private val logger = loggerFor<UndertowHandlerContributor>()
  }

  override fun contribute(builder: Undertow.Builder) {

    val factoryName = securitySettings.securityHandlerName
    logger.debug("Using security configuration: {}", factoryName)

    val factory = if (factoryName != SECURITY_CONFIGURATION_NONE) {
      get<SecurityHandlerFactory>(named(factoryName))
    } else {
      null
    }

    // gather all handlers to construct a path mapping
    val mappedHandlers : List<ContextMappedHandler> =
        // all handlers that are defined as Koin components directly
        getAll<ContextMappedHandler>() +
            // all handlers created via a factory that are Koin components
            getAll<EndpointProvider>().flatMap { provider ->
              provider.operationEndpoints.map {
                get<OperationEndpointHandler<*, *>> { parametersOf(it) }
              } +
                  provider.queryEndpoints.map {
                    get<QueryEndpointHandler<*>> { parametersOf(it) }
                  }
            } +
            // all query and operation endpoints defined as Koin components directly
            getAll<QueryEndpoint<*>>().map { get<QueryEndpointHandler<*>> { parametersOf(it) } } +
            getAll<OperationEndpoint<*, *>>().map { get<OperationEndpointHandler<*, *>> { parametersOf(it) } }

    val pathHandler = PathHandler()

    for (handler in mappedHandlers) {
      val (path, exactPathOnly) = handler.mappingParameters
      if (exactPathOnly) {
        logger.debug("Mapping handler {} to exact path {}", handler, path)
        pathHandler.addExactPath(path, handler)
      } else {
        logger.debug("Mapping handler {} to path prefix {}", handler, path)
        pathHandler.addPrefixPath(path, handler)
      }
    }

    if (factory != null) {
      logger.debug("API endpoints security configuration factory {}", factory)
      builder.setHandler(factory.createSecurityHandler(pathHandler))
    } else {
      logger.warn("API endpoints are not protected by any security configuration")
      builder.setHandler(pathHandler)
    }
  }
}

/**
 * Contains logic that configures Undertow server itself using container settings.
 * At the moment it only allows to initialize basic settings.
 */
class UndertowSettingsContributor(
    private val settings: WebServerSettings
) : UndertowConfigContributor, CordaptorComponent {

  override fun contribute(builder: Undertow.Builder) {
    builder.setIoThreads(settings.ioThreads)
    builder.setWorkerThreads(settings.workerThreads)
  }
}

/**
 * Wrapper managing the lifecycle of the Undertow webserver in line with the lifecycle of the container.
 * It delegates most of the work to instances of [UndertowConfigurationContributor] defined via Koin.
 */
class WebServer : LifecycleAware, CordaptorComponent {
  companion object {
    private val logger = loggerFor<WebServer>()
  }

  lateinit var server: Undertow

  private val control: LifecycleControl by inject()

  override fun onInitialize() {
    val builder = Undertow.builder()

    val contributors = getAll<UndertowConfigContributor>()
    for (contributor in contributors) {
      logger.debug("Invoking configuration contributor {}", contributor)
      contributor.contribute(builder)
    }

    server = builder.build()
    server.start()

    logger.info("Web server started")
    control.serverStarted()
  }

  override fun onShutdown() {
    server.stop()
  }
}
