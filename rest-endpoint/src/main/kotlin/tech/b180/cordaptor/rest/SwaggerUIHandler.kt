package tech.b180.cordaptor.rest

import io.undertow.server.handlers.resource.ClassPathResourceManager
import io.undertow.server.handlers.resource.ResourceHandler
import org.koin.core.inject
import tech.b180.cordaptor.kernel.CordaptorComponent
import tech.b180.cordaptor.kernel.LifecycleAware

/**
 * Simple handler serving static resources required for Swagger UI.
 */
class SwaggerUIHandler(
    contextPath: String
) : ResourceHandler(resourceManager), ContextMappedHandler, CordaptorComponent, LifecycleAware {

  companion object {
    val resourceManager = ClassPathResourceManager(SwaggerUIHandler::class.java.classLoader, "swagger")
  }

  init {
    isDirectoryListingEnabled = false
    setWelcomeFiles("index.html")
  }

  override val mappingParameters = ContextMappedHandler.Parameters(contextPath, false)

  private val urlBuilder: URLBuilder by inject()
  private val notifications: NodeNotifications by inject()

  override fun onStarted() {
    val swaggerUrl = urlBuilder.toAbsoluteUrl(mappingParameters.path)
    notifications.emitOperatorMessage("Cordaptor: Swagger-UI is available at $swaggerUrl")
  }
}