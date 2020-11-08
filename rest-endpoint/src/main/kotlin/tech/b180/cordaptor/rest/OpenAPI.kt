package tech.b180.cordaptor.rest

import io.undertow.util.StatusCodes
import net.corda.core.serialization.SerializableCalculatedProperty
import tech.b180.cordaptor.shaded.javax.json.Json
import tech.b180.cordaptor.shaded.javax.json.JsonObject
import java.net.URL
import java.util.*

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
    val paths: SortedMap<ResourcePath, PathItem>,
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
      val variables: SortedMap<String, ServerVariable>? = null
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
      val responses: SortedMap<HttpStatusCode, Response> = emptyMap<HttpStatusCode, Response>().toSortedMap(),

      val parameters: List<Parameter>? = null,
      val description: String? = null,
      val tags: List<String>? = null,
      val deprecated: Boolean? = null,
      val servers: List<Server>? = null
  ) {

    fun withResponse(statusCode: HttpStatusCode, response: Response): Operation =
        copy(responses = (responses.plus(statusCode to response).toSortedMap()))

    fun withForbiddenResponse(): Operation =
        copy(responses = (responses.plus(
            HttpStatusCode.FORBIDDEN to Response("Permission denied")).toSortedMap()))

    fun withRequestBody(requestBody: RequestBody): Operation =
        copy(requestBody = requestBody)

    fun withParameter(parameter: Parameter): Operation =
        copy(parameters = listOf(parameter) + (parameters ?: emptyList()))

    fun withTags(vararg tags: String): Operation = copy(tags = tags.asList())
  }

  /** [https://github.com/OAI/OpenAPI-Specification/blob/master/versions/3.0.3.md#componentsObject] */
  data class Components(
      val schemas: SortedMap<String, JsonObject>? = null,
      val responses: SortedMap<String, Response>? = null,
      val parameters: SortedMap<String, Parameter>? = null,
      val headers: SortedMap<String, Header>? = null,
      val securitySchemes: SortedMap<String, SecurityScheme>? = null
  )

  /** [https://github.com/OAI/OpenAPI-Specification/blob/master/versions/3.0.3.md#responseObject] */
  data class Response(
      val description: String,
      val content: SortedMap<ContentType, MediaType>? = null,
      val headers: SortedMap<HttpHeader, Header>? = null
  ) {

    companion object {
      fun createJsonResponse(description: String, schema: JsonObject) =
          Response(description = description, content = sortedMapOf(JSON_CONTENT_TYPE to MediaType(schema)))
    }

    fun withHeader(header: Pair<HttpHeader, Header>): Response =
        copy(headers = headers?.plus(header)?.toSortedMap() ?: sortedMapOf(header))
  }

  /** [https://github.com/OAI/OpenAPI-Specification/blob/master/versions/3.0.3.md#mediaTypeObject] */
  data class MediaType(
      val schema: JsonObject
  )

  /** [https://github.com/OAI/OpenAPI-Specification/blob/master/versions/3.0.3.md#headerObject] */
  data class Header(
      val description: String,
      val schema: JsonObject
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
      val content: SortedMap<ContentType, MediaType>,
      val required: Boolean = false,
      val description: String? = null
  ) {
    companion object {
      fun createJsonRequest(schema: JsonObject, required: Boolean) =
          RequestBody(content = sortedMapOf(JSON_CONTENT_TYPE to MediaType(schema)), required = required)
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
    OK(StatusCodes.OK),
    ACCEPTED(StatusCodes.ACCEPTED),

    // 3xx codes
    SEE_OTHER(StatusCodes.SEE_OTHER),

    // 4xx codes
    FORBIDDEN(StatusCodes.FORBIDDEN),
    NOT_FOUND(StatusCodes.NOT_FOUND),
    GONE(StatusCodes.GONE),

    // 5xx codes
    INTERNAL_SERVER_ERROR(StatusCodes.INTERNAL_SERVER_ERROR)
  }

  object PrimitiveTypes {

    val POSITIVE_INTEGER: JsonObject = Json.createObjectBuilder()
        .add("type", "number")
        .add("minimum", 0)
        .build()

    val NON_EMPTY_STRING: JsonObject = Json.createObjectBuilder()
        .add("type", "string")
        .add("minLength", 1)
        .build()

    val UUID_STRING: JsonObject = Json.createObjectBuilder()
        .add("type", "string")
        .add("format", "uuid")
        .build()

    val URL_STRING: JsonObject = Json.createObjectBuilder()
        .add("type", "string")
        .add("format", "url")
        .build()
  }
}
