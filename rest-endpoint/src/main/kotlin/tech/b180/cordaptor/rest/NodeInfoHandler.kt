package tech.b180.cordaptor.rest

import net.corda.core.node.NodeInfo
import org.eclipse.jetty.http.HttpMethod
import org.eclipse.jetty.server.Request
import org.eclipse.jetty.server.handler.AbstractHandler
import org.koin.core.inject
import org.koin.core.parameter.parametersOf
import tech.b180.cordaptor.corda.CordaNodeState
import tech.b180.cordaptor.kernel.CordaptorComponent
import javax.servlet.http.HttpServletRequest
import javax.servlet.http.HttpServletResponse

/**
 * Responds to HTTP GET requests providing an instance of [NodeInfo] for the underlying Corda node.
 */
class NodeInfoHandler(
    override val contextPath: String
) : ContextMappedHandler, AbstractHandler(), CordaptorComponent {

  private val cordaNodeState: CordaNodeState by inject()
  private val nodeInfoSerializer: JsonSerializer<NodeInfo> by inject { parametersOf(NodeInfo::class) }

  override fun handle(target: String?, baseRequest: Request?, request: HttpServletRequest?, response: HttpServletResponse?) {
    baseRequest!!.isHandled = true

    if (request!!.method != HttpMethod.GET.asString()) {
      response!!.status = HttpServletResponse.SC_METHOD_NOT_ALLOWED
      return
    }

    JsonHome.createGenerator(response!!.writer)
        .writeSerializedObject(nodeInfoSerializer, cordaNodeState.nodeInfo)
        .flush()
  }
}