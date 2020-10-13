package tech.b180.cordaptor.rest

import net.corda.core.node.NodeInfo
import net.corda.core.node.services.diagnostics.NodeVersionInfo
import org.koin.core.inject
import tech.b180.cordaptor.corda.CordaNodeState
import tech.b180.cordaptor.kernel.CordaptorComponent
import java.lang.reflect.Type

/**
 * Responds to HTTP GET requests providing an instance of [NodeInfo] for the underlying Corda node.
 */
class NodeInfoEndpoint(contextPath: String) : QueryEndpoint<NodeInfo>, CordaptorComponent {

  private val cordaNodeState: CordaNodeState by inject()

  override fun executeQuery(request: Request): Response<NodeInfo> {
    return Response(cordaNodeState.nodeInfo)
  }

  override val responseType: Type = NodeInfo::class.java
  override val contextMappingParameters = ContextMappingParameters(contextPath, true)
}

/**
 * Responds to HTTP GET requests providing an instance of [NodeVersionInfo] for the underlying Corda node.
 */
class NodeVersionEndpoint(contextPath: String) : QueryEndpoint<NodeVersionInfo>, CordaptorComponent {

  private val cordaNodeState: CordaNodeState by inject()

  override fun executeQuery(request: Request): Response<NodeVersionInfo> {
    return Response(cordaNodeState.nodeVersionInfo)
  }

  override val responseType: Type = NodeVersionInfo::class.java
  override val contextMappingParameters = ContextMappingParameters(contextPath, true)
}