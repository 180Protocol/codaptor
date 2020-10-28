package tech.b180.cordaptor.rest

import org.eclipse.jetty.server.Handler
import org.eclipse.jetty.server.Server
import org.eclipse.jetty.server.handler.ContextHandler
import org.eclipse.jetty.server.handler.ContextHandlerCollection
import org.koin.core.get
import org.koin.core.inject
import org.koin.core.parameter.parametersOf
import tech.b180.cordaptor.kernel.*

/**
 * Describes a logic configuring a given instance of Jetty server.
 */
interface JettyConfigurator {
  fun beforeServerStarted(server: Server)
  fun afterServerStarted(server: Server)
}

data class ContextMappingParameters(
    val contextPath: String,
    val allowNullPathInfo: Boolean
)

/**
 * Describes a single handler for a particular context path
 * to be wrapped in Jetty's [ContextHandler] and included into a
 * [ContextHandlerCollection] for routing requests to respective endpoints.
 */
interface ContextMappedHandler : Handler {
  val mappingParameters: ContextMappingParameters
}

/**
 * Container for Jetty server instance. Responsible for obtaining various
 * aspects of the configuration and applying them to the server,
 * as well as starting and stopping the server in line with the container lifecycle.
 */
class JettyServer : LifecycleAware, CordaptorComponent {

  companion object {
    private val logger = loggerFor<JettyServer>()
  }

  private val server = Server()

  private val control: LifecycleControl by inject()

  override fun onInitialize() {
    val configurators = getAll<JettyConfigurator>()
    for (configurator in configurators) {
      configurator.beforeServerStarted(server)
    }

    val mappedHandlers : List<ContextMappedHandler> = getAll<ContextMappedHandler>() +
        getAll<EndpointProvider>().flatMap { provider ->
          provider.operationEndpoints.map {
            get<OperationEndpointHandler<*, *>> { parametersOf(it) }
          } +
          provider.queryEndpoints.map {
            get<QueryEndpointHandler<*>> { parametersOf(it) }
          }
        } +
        getAll<QueryEndpoint<*>>().map { get<QueryEndpointHandler<*>> { parametersOf(it) } } +
        getAll<OperationEndpoint<*, *>>().map { get<OperationEndpointHandler<*, *>> { parametersOf(it) } }

    val contextHandlers = mappedHandlers.map { handler ->
      logger.info("Mapping $handler at ${handler.mappingParameters.contextPath} " +
          "(allowNullPathInfo=${handler.mappingParameters.allowNullPathInfo})")

      ContextHandler(handler.mappingParameters.contextPath).also {
        it.allowNullPathInfo = handler.mappingParameters.allowNullPathInfo
        it.handler = handler
      }
    }

    server.handler = ContextHandlerCollection(*contextHandlers.toTypedArray())

    // this may fail with IOException wrapping BindException if the port is not available
    // but we cannot sensibly recover from it, so will simply propagate the exception

    server.start()
    logger.info("Jetty server started")

    for (configurator in configurators) {
      configurator.afterServerStarted(server)
    }

    control.serverStarted()
  }

  override fun onShutdown() {
    // just stop the servers
    server.stop()
  }
}
