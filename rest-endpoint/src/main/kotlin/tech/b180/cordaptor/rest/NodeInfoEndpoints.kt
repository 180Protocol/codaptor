package tech.b180.cordaptor.rest

import net.corda.core.node.NodeInfo
import net.corda.core.node.services.diagnostics.NodeVersionInfo
import org.koin.core.inject
import tech.b180.cordaptor.corda.CordaNodeState
import tech.b180.cordaptor.kernel.CordaptorComponent

const val NODE_DIAGNOSTIC_TAG = "nodeDiagnostic"

/**
 * Responds to HTTP GET requests providing an instance of [NodeInfo] for the underlying Corda node.
 */
class NodeInfoEndpoint(contextPath: String)
  : ContextMappedQueryEndpoint<NodeInfo>(contextPath, allowNullPathInfo = true), CordaptorComponent {

  private val cordaNodeState: CordaNodeState by inject()

  override fun executeQuery(request: Request): Response<NodeInfo> {
    return Response(cordaNodeState.nodeInfo)
  }

  override fun generatePathInfoSpecification(schemaGenerator: JsonSchemaGenerator): OpenAPI.PathItem =
      OpenAPI.PathItem(
          get = OpenAPI.Operation(
              summary = "Returns network map entry for the underlying Corda node",
              operationId = "getNodeInfo"
          ).withResponse(OpenAPI.HttpStatusCode.OK, OpenAPI.Response.createJsonResponse(
              description = "Successful operation",
              schema = schemaGenerator.generateSchema(SerializerKey(NodeInfo::class)))
          ).withUnauthorizedResponse().withTags(NODE_DIAGNOSTIC_TAG)
      )
}

/**
 * Responds to HTTP GET requests providing an instance of [NodeVersionInfo] for the underlying Corda node.
 */
class NodeVersionEndpoint(contextPath: String)
  : ContextMappedQueryEndpoint<NodeVersionInfo>(contextPath, allowNullPathInfo = true), CordaptorComponent {

  private val cordaNodeState: CordaNodeState by inject()

  override fun executeQuery(request: Request): Response<NodeVersionInfo> {
    return Response(cordaNodeState.nodeVersionInfo)
  }

  override fun generatePathInfoSpecification(schemaGenerator: JsonSchemaGenerator): OpenAPI.PathItem =
      OpenAPI.PathItem(
          get = OpenAPI.Operation(
              summary = "Returns software version information for the underlying Corda node",
              operationId = "getNodeVersion"
          ).withResponse(OpenAPI.HttpStatusCode.OK, OpenAPI.Response.createJsonResponse(
              description = "Successful operation",
              schema = schemaGenerator.generateSchema(SerializerKey(NodeVersionInfo::class)))
          ).withUnauthorizedResponse().withTags(NODE_DIAGNOSTIC_TAG)
      )
}
