package tech.b180.cordaptor.rest

import org.eclipse.jetty.server.Request
import org.eclipse.jetty.server.handler.AbstractHandler
import tech.b180.cordaptor.rest.ContextMappedHandler
import javax.servlet.http.HttpServletRequest
import javax.servlet.http.HttpServletResponse

class SwaggerUIHandler(contextPath: String) : ContextMappedHandler, AbstractHandler() {

  override val mappingParameters = ContextMappingParameters(contextPath, true)

  override fun handle(target: String?, baseRequest: Request?, request: HttpServletRequest?, response: HttpServletResponse?) {
    TODO("Not yet implemented")
  }

}