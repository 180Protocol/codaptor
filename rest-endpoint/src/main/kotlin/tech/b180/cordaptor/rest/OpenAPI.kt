package tech.b180.cordaptor.rest

import net.corda.core.serialization.SerializableCalculatedProperty
import tech.b180.cordaptor.shaded.javax.json.Json
import tech.b180.cordaptor.shaded.javax.json.JsonObject
import java.net.URL
import javax.servlet.http.HttpServletResponse

typealias SemanticVersion = String
typealias ResourcePath = String
typealias OperationID = String
typealias ContentType = String
typealias HttpHeader = String

/**
 * Top level object for OpenAPI specification, and a namespace container for
 * other specification objects.
 */
data class OpenAPI(
    val info: Info,
    val servers: List<Server>,

    /** [https://github.com/OAI/OpenAPI-Specification/blob/master/versions/3.0.3.md#paths-object] */
    val paths: Map<ResourcePath, PathItem>,
    val components: Components? = null
) {

  @Suppress("unused", "SpellCheckingInspection")
  @get:SerializableCalculatedProperty // FIXME hack to make it read-only, to replace with JSON serialization annotations
  val openapi = VERSION

  companion object {
    const val VERSION = "3.0.3"

    const val JSON_CONTENT_TYPE: ContentType = "application/json"

    const val COMPONENTS_SCHEMA_PREFIX = "#/components/schemas/"
  }

  /** [https://github.com/OAI/OpenAPI-Specification/blob/master/versions/3.0.3.md#infoObject] */
  data class Info(
      val title: String,
      val version: SemanticVersion,
      val description: String? = null,
      val termsOfService: String? = null,
      val contact: Contact? = null,
      val license: License? = null
  )

  /** [https://github.com/OAI/OpenAPI-Specification/blob/master/versions/3.0.3.md#contactObject] */
  data class Contact(
      val name: String,
      val url: URL,
      val email: String
  )

  /** [https://github.com/OAI/OpenAPI-Specification/blob/master/versions/3.0.3.md#licenseObject] */
  data class License(
      val name: String,
      val url: URL
  )

  /** [https://github.com/OAI/OpenAPI-Specification/blob/master/versions/3.0.3.md#serverObject] */
  data class Server(
      val url: URL,
      val description: String? = null,
      val variables: Map<String, ServerVariable>? = null
  )

  /** [https://github.com/OAI/OpenAPI-Specification/blob/master/versions/3.0.3.md#serverVariableObject] */
  data class ServerVariable(
      val default: String,
      val enum: List<String>? = null,
      val description: String? = null
  )

  /** [https://github.com/OAI/OpenAPI-Specification/blob/master/versions/3.0.3.md#pathItemObject] */
  data class PathItem(
      val summary: String? = null,
      val description: String? = null,
      val get: Operation? = null,
      val post: Operation? = null,
      val put: Operation? = null,
      val patch: Operation? = null,
      val delete: Operation? = null,
      val head: Operation? = null,
      val options: Operation? = null,
      val trace: Operation? = null,
      val servers: List<Server>? = null,
      val parameters: List<Parameter>? = null
  ) {
    fun mergeOperationsWith(other: PathItem) = copy(
        get = get ?: other.get,
        post = get ?: other.post,
        put = get ?: other.put,
        patch = get ?: other.patch,
        delete = get ?: other.delete,
        head = get ?: other.head,
        options = get ?: other.options,
        trace = get ?: other.trace
    )
  }

  /** [https://github.com/OAI/OpenAPI-Specification/blob/master/versions/3.0.3.md#operation-object] */
  data class Operation(
      val summary: String,
      val operationId: OperationID,
      val requestBody: RequestBody? = null,

      // FIXME 'default' response is not supported
      /** [https://github.com/OAI/OpenAPI-Specification/blob/master/versions/3.0.3.md#responsesObject] */
      val responses: Map<HttpStatusCode, Response> = emptyMap(),
      val parameters: List<Parameter>? = null,
      val description: String? = null,
      val tags: List<String>? = null,
      val deprecated: Boolean? = null,
      val servers: List<Server>? = null
  ) {

    fun withResponse(statusCode: HttpStatusCode, response: Response): Operation =
        copy(responses = responses + (statusCode to response))

    fun withRequestBody(requestBody: RequestBody): Operation =
        copy(requestBody = requestBody)

    fun withParameter(parameter: Parameter): Operation =
        copy(parameters = listOf(parameter) + (parameters ?: emptyList()))
  }

  /** [https://github.com/OAI/OpenAPI-Specification/blob/master/versions/3.0.3.md#componentsObject] */
  data class Components(
      val schemas: Map<String, JsonObject>? = null,
      val responses: Map<String, Response>? = null,
      val parameters: Map<String, Parameter>? = null,
      val headers: Map<String, Header>? = null,
      val securitySchemes: Map<String, SecurityScheme>? = null
  )

  /** [https://github.com/OAI/OpenAPI-Specification/blob/master/versions/3.0.3.md#responseObject] */
  data class Response(
      val description: String,
      val content: Map<ContentType, MediaType>? = null,
      val headers: Map<HttpHeader, Header>? = null
  ) {

    companion object {
      fun createJsonResponse(description: String, schema: JsonObject) =
          Response(description = description, content = mapOf(JSON_CONTENT_TYPE to MediaType(schema)))
    }
  }

  /** [https://github.com/OAI/OpenAPI-Specification/blob/master/versions/3.0.3.md#mediaTypeObject] */
  data class MediaType(
      val schema: JsonObject
  )

  /** [https://github.com/OAI/OpenAPI-Specification/blob/master/versions/3.0.3.md#headerObject] */
  data class Header(
      val schema: JsonObject,
      val description: String? = null
  )

  /** [https://github.com/OAI/OpenAPI-Specification/blob/master/versions/3.0.3.md#parameterObject] */
  data class Parameter(
      val name: String,
      val schema: JsonObject,
      val `in`: ParameterLocation,
      val description: String? = null,
      val required: Boolean? = null,
      val deprecated: Boolean? = null,
      val allowEmptyValue: Boolean? = null,
      val explode: Boolean? = null
  )

  /** [https://github.com/OAI/OpenAPI-Specification/blob/master/versions/3.0.3.md#requestBodyObject] */
  data class RequestBody(
      val content: Map<ContentType, MediaType>,
      val required: Boolean = false,
      val description: String? = null
  ) {
    companion object {
      fun createJsonRequest(schema: JsonObject, required: Boolean) =
          RequestBody(content = mapOf(JSON_CONTENT_TYPE to MediaType(schema)), required = required)
    }
  }

  /** [https://github.com/OAI/OpenAPI-Specification/blob/master/versions/3.0.3.md#securitySchemeObject] */
  data class SecurityScheme(
      val name: String,
      val type: SecuritySchemeType,
      val `in`: ParameterLocation? = null,
      val description: String? = null,
      val scheme: String? = null,
      val bearerFormat: String? = null,
      val openIdConnectUrl: URL? = null
  )

  @Suppress("unused")
  enum class HttpAuthorizationScheme(override val jsonValue: String) : SerializableEnum {
    BASIC("basic"),
    BEARER("bearer"),
  }

  @Suppress("unused")
  enum class SecuritySchemeType(override val jsonValue: String) : SerializableEnum {
    API_KEY("apiKey"),
    HTTP("http"),
    OAUTH2("oauth2"),
    OPEN_ID_CONNECT("openIdConnect")
  }

  @Suppress("unused")
  enum class ParameterLocation(override val jsonValue: String) : SerializableEnum {
    QUERY("query"),
    HEADER("header"),
    PATH("path"),
    COOKIE("cookie")
  }

  @Suppress("unused")
  enum class HttpStatusCode(
      statusCode: Int? = null,
      override val jsonValue: String = statusCode!!.toString()) : SerializableEnum {

    /** Use for response type map */
    DEFAULT(jsonValue = "default"),

    // 2xx codes
    OK(HttpServletResponse.SC_OK),
    ACCEPTED(HttpServletResponse.SC_ACCEPTED),

    // 3xx codes
    SEE_OTHER(HttpServletResponse.SC_SEE_OTHER),

    // 4xx codes
    NOT_FOUND(HttpServletResponse.SC_NOT_FOUND),

    // 5xx codes
    INTERNAL_SERVER_ERROR(HttpServletResponse.SC_INTERNAL_SERVER_ERROR)
  }

  object PrimitiveTypes {

    val POSITIVE_INTEGER: JsonObject = Json.createObjectBuilder()
        .add("type", "number")
        .add("minimum", 0)
        .build()
  }
}
