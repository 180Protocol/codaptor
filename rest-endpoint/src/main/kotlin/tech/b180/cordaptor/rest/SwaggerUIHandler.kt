package tech.b180.cordaptor.rest

import org.eclipse.jetty.server.handler.ResourceHandler
import org.eclipse.jetty.util.resource.Resource
import org.koin.core.inject
import tech.b180.cordaptor.kernel.CordaptorComponent
import tech.b180.cordaptor.kernel.LifecycleAware

/**
 * Simple handler serving static resources required for Swagger UI.
 */
class SwaggerUIHandler(contextPath: String)
  : ContextMappedHandler, ResourceHandler(), CordaptorComponent, LifecycleAware {

  init {
    isDirectoriesListed = false
    isRedirectWelcome = true
    welcomeFiles = arrayOf("index.html")
    baseResource = Resource.newClassPathResource("/swagger")
  }

  override val mappingParameters = ContextMappingParameters(contextPath, false)

  private val connectorConfiguration: JettyConnectorConfiguration by inject()
  private val notifications: NodeNotifications by inject()

  override fun onStarted() {
    val swaggerUrl = connectorConfiguration.toAbsoluteUrl(mappingParameters.contextPath)
    notifications.emitOperatorMessage("Cordaptor: Swagger-UI is available at $swaggerUrl")
  }
}