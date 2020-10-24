package tech.b180.cordaptor.rest

import org.eclipse.jetty.server.handler.ResourceHandler
import org.eclipse.jetty.util.resource.Resource

/**
 * Simple handler serving static resources required for Swagger UI.
 */
class SwaggerUIHandler(contextPath: String) : ContextMappedHandler, ResourceHandler() {

  init {
    isDirectoriesListed = false
    isRedirectWelcome = true
    welcomeFiles = arrayOf("index.html")
    baseResource = Resource.newClassPathResource("/swagger")
  }

  override val mappingParameters = ContextMappingParameters(contextPath, false)

}