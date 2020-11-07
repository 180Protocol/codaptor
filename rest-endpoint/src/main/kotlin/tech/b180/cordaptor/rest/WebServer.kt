package tech.b180.cordaptor.rest

import io.undertow.Undertow
import io.undertow.server.HttpHandler
import io.undertow.server.handlers.PathHandler
import io.undertow.server.handlers.ResponseCodeHandler
import org.koin.core.get
import org.koin.core.inject
import org.koin.core.parameter.parametersOf
import org.pac4j.undertow.handler.SecurityHandler
import tech.b180.cordaptor.kernel.*
import javax.net.ssl.SSLContext

/**
 * Describes a single handler for a particular path prefix used to route API requests.
 */
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
@ModuleAPI
interface UndertowConfigContributor {
  fun contribute(builder: Undertow.Builder)
}

/**
 * Utility allowing public URLs for API endpoints to be obtained
 * in a way specific to the configuration of the server.
 */
@ModuleAPI
interface URLBuilder {
  val baseUrl: String

  fun toAbsoluteUrl(contextPath: String): String
}

/**
 * Wrapper for HTTP connector settings and utility functions helping to construct absolute URLs.
 */
data class WebServerSettings(
    val bindAddress: HostAndPort,
    val secureTransportSettings: SecureTransportSettings,
    val ioThreads: Int,
    val workerThreads: Int
) : URLBuilder {
  constructor(serverConfig: Config) : this(
      bindAddress = serverConfig.getHostAndPort("listenAddress"),
      secureTransportSettings = SecureTransportSettings(serverConfig.getSubtree("tls")),
      ioThreads = serverConfig.getInt("ioThreads"),
      workerThreads = serverConfig.getInt("workerThreads")
  )

  override fun toAbsoluteUrl(contextPath: String) = baseUrl + contextPath

  override val baseUrl = "${if (isSecure) "https" else "http"}://${bindAddress.hostname}:${bindAddress.port}"

  val isSecure: Boolean get() = secureTransportSettings.enabled
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
    private val settings: WebServerSettings
) : UndertowConfigContributor, CordaptorComponent {

  companion object {
    private val logger = loggerFor<UndertowHandlerContributor>()
  }

  override fun contribute(builder: Undertow.Builder) {

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
//    builder.setHandler(SecurityHandler.build())
    builder.setHandler(pathHandler)
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
