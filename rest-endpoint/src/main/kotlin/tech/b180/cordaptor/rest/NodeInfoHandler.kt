package tech.b180.cordaptor.rest

import net.corda.core.node.NodeInfo
import net.corda.core.node.ServiceHub
import org.eclipse.jetty.http.HttpMethod
import org.eclipse.jetty.server.Request
import org.eclipse.jetty.server.handler.AbstractHandler
import org.koin.core.KoinComponent
import org.koin.core.inject
import org.koin.core.parameter.parametersOf
import tech.b180.cordaptor.rest.ContextMappedHandler
import javax.json.Json
import javax.servlet.http.HttpServletRequest
import javax.servlet.http.HttpServletResponse

/**
 * Responds to HTTP GET requests providing an instance of [NodeInfo] for the underlying Corda node.
 */
class NodeInfoHandler(
    override val contextPath: String
) : ContextMappedHandler, AbstractHandler(), KoinComponent {

  private val serviceHub: ServiceHub by inject()
  private val nodeInfoSerializer: JsonSerializer<NodeInfo> by inject { parametersOf(NodeInfo::class) }

  override fun handle(target: String?, baseRequest: Request?, request: HttpServletRequest?, response: HttpServletResponse?) {
    baseRequest!!.isHandled = true

    if (request!!.method != HttpMethod.GET.asString()) {
      response!!.status = HttpServletResponse.SC_METHOD_NOT_ALLOWED
      return
    }

    Json.createGenerator(response!!.writer)
        .writeSerializedObject(nodeInfoSerializer, serviceHub.myInfo)
        .flush()
  }
}