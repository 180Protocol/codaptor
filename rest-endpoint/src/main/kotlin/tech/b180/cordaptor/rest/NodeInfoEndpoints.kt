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
    if (!request.subject.isPermitted(OPERATION_GET_NODE_INFO)) {
      throw UnauthorizedOperationException(OPERATION_GET_NODE_INFO)
    }
    return Response(cordaNodeState.nodeInfo)
  }

  override fun generatePathInfoSpecification(schemaGenerator: JsonSchemaGenerator): OpenAPI.PathItem =
      OpenAPI.PathItem(
          get = OpenAPI.Operation(
              summary = "Returns network map entry for the underlying Corda node",
              operationId = OPERATION_GET_NODE_INFO
          ).withResponse(OpenAPI.HttpStatusCode.OK, OpenAPI.Response.createJsonResponse(
              description = "Successful operation",
              schema = schemaGenerator.generateSchema(SerializerKey(NodeInfo::class)))
          ).withForbiddenResponse().withTags(NODE_DIAGNOSTIC_TAG)
      )
}

/**
 * Responds to HTTP GET requests providing an instance of [NodeVersionInfo] for the underlying Corda node.
 */
class NodeVersionEndpoint(contextPath: String)
  : ContextMappedQueryEndpoint<NodeVersionInfo>(contextPath, allowNullPathInfo = true), CordaptorComponent {

  private val cordaNodeState: CordaNodeState by inject()

  override fun executeQuery(request: Request): Response<NodeVersionInfo> {
    if (!request.subject.isPermitted(OPERATION_GET_NODE_VERSION)) {
      throw UnauthorizedOperationException(OPERATION_GET_NODE_VERSION)
    }
    return Response(cordaNodeState.nodeVersionInfo)
  }

  override fun generatePathInfoSpecification(schemaGenerator: JsonSchemaGenerator): OpenAPI.PathItem =
      OpenAPI.PathItem(
          get = OpenAPI.Operation(
              summary = "Returns software version information for the underlying Corda node",
              operationId = OPERATION_GET_NODE_VERSION
          ).withResponse(OpenAPI.HttpStatusCode.OK, OpenAPI.Response.createJsonResponse(
              description = "Successful operation",
              schema = schemaGenerator.generateSchema(SerializerKey(NodeVersionInfo::class)))
          ).withForbiddenResponse().withTags(NODE_DIAGNOSTIC_TAG)
      )
}
