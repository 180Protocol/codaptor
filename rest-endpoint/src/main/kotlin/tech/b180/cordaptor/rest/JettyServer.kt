package tech.b180.cordaptor.rest

import org.eclipse.jetty.server.Handler
import org.eclipse.jetty.server.Server
import org.eclipse.jetty.server.handler.ContextHandler
import org.eclipse.jetty.server.handler.ContextHandlerCollection
import org.koin.core.get
import org.koin.core.parameter.parametersOf
import tech.b180.cordaptor.kernel.CordaptorComponent
import tech.b180.cordaptor.kernel.LifecycleAware
import tech.b180.cordaptor.kernel.getAll

/**
 * Describes a logic configuring a given instance of Jetty server.
 */
interface JettyConfigurator {
  fun configure(server: Server)
}

/**
 * Describes a factory that configures a number of context handlers
 * to be wrapped in Jetty's [ContextHandler] and included into a
 * [ContextHandlerCollection] for routing requests to respective endpoints.
 */
interface ContextMappedHandlerFactory {
  val handlers: List<ContextMappedHandler>
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

  private val server = Server()

  override fun initialize() {
    getAll<JettyConfigurator>().forEach() {
      it.configure(server)
    }

    val mappedHandlers : List<ContextMappedHandler> = getAll<ContextMappedHandler>() +
        getAll<ContextMappedHandlerFactory>().flatMap { it.handlers } +
        getAll<QueryEndpoint<*>>().map { get<QueryEndpointHandler<*>> { parametersOf(it) } } +
        getAll<OperationEndpoint<*, *>>().map { get<OperationEndpointHandler<*, *>> { parametersOf(it) } }

    val contextHandlers = mappedHandlers.map { handler ->
      ContextHandler(handler.mappingParameters.contextPath).also {
        it.allowNullPathInfo = handler.mappingParameters.allowNullPathInfo
        it.handler = handler
      }
    }

    server.handler = ContextHandlerCollection(*contextHandlers.toTypedArray())

    server.start()
    println("Jetty server started $server")
  }

  override fun shutdown() {
    // just stop the servers
    server.stop()
  }
}
