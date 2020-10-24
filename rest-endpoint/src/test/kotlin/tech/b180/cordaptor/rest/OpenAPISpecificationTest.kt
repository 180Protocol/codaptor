package tech.b180.cordaptor.rest

import io.reactivex.rxjava3.core.Single
import org.junit.Rule
import org.koin.test.KoinTest
import org.koin.test.KoinTestRule
import org.koin.test.get
import tech.b180.cordaptor.corda.CordaFlowSnapshot
import tech.b180.cordaptor.shaded.javax.json.JsonObject
import java.net.URL
import kotlin.test.Test
import kotlin.test.assertEquals

class OpenAPISpecificationTest : KoinTest {

  @get:Rule
  val koinTestRule = KoinTestRule.create {
    modules(CordaTypesTest.serializersModule)
  }

  @Test
  fun `test collecting schemas`() {
    val generator = CollectingJsonSchemaGenerator("/", get())
    val schema = generator.generateSchema(SerializerKey(CordaFlowSnapshot::class.java, TestFlow::class.java))
    assertEquals("/CordaFlowSnapshot_TestFlow", schema.getString("\$ref"))

    val schemas= generator.collectedSchemas

    schemas["CordaFlowSnapshot_TestFlow"]?.let {
      assertEquals("/CordaFlowResult_TestFlow", it.getValue("/properties/result/\$ref").asString())
      assertEquals("/CordaFlowProgress", it.getValue("/properties/currentProgress/\$ref").asString())
      assertEquals("string", it.getValue("/properties/flowClass/type").asString())
    } ?: error("Missing entry")

    schemas["CordaFlowResult_TestFlow"]?.let {
      assertEquals("/TestFlow", it.getValue("/properties/value/\$ref").asString())
      assertEquals("/Throwable", it.getValue("/properties/error/\$ref").asString())
      assertEquals("string", it.getValue("/properties/timestamp/type").asString())
    } ?: error("Missing entry")

    schemas["TestFlow"]?.let {
      assertEquals("/TestFlowParam", it.getValue("/properties/objectParam/\$ref").asString())
      assertEquals("string", it.getValue("/properties/stringParam/type").asString())
    } ?: error("Missing entry")

    schemas["TestFlowParam"]?.let {
      assertEquals("number", it.getValue("/properties/intParam/type").asString())
    } ?: error("Missing entry")
  }

  @Test
  fun `test open API serialization`() {
    // this test ensures that unintended consequences for changes
    // do not affect our ability to serialize OpenAPI constructs
    val f = get<SerializationFactory>()
    val gen = CollectingJsonSchemaGenerator(OpenAPI.COMPONENTS_SCHEMA_PREFIX, f)
    val schema = gen.generateSchema(SerializerKey.forType(OpenAPI::class.java))

    assertEquals("""{"${'$'}ref":"#/components/schemas/OpenAPI"}"""".asJsonObject(), schema)

    val componentsSerializer = f.getSerializer(
        SerializerKey(Map::class.java, String::class.java, JsonObject::class.java))

    val components = generateJson { componentsSerializer.toJson(gen.collectedSchemas, this) }.asJsonObject()

    val expectedSchema = javaClass.getResource("/OpenAPI.schema.json")
        .openStream().reader().readText().asJsonObject()

    assertEquals(expectedSchema, components)
  }

  @Test
  fun `test open API builder`() {
    val spec = OpenAPISpecificationBuilder(get())
        .withInfo(OpenAPI.Info("title", "version"))
        .withServer(OpenAPI.Server(URL("http://localhost")))
        .withOperationEndpoints(listOf(TestOperationEndpoint("/operation")))
        .withQueryEndpoints(listOf(TestQueryEndpoint("/query")))
        .buildSpec()

    val jsonSpec = getKoin().getSerializer(OpenAPI::class).toJsonString(spec).asJsonObject()

    val expectedSpec = javaClass.getResource("/Test.api.json")
        .openStream().reader().readText().asJsonObject()

    assertEquals(expectedSpec, jsonSpec)
  }
}

class TestOperationEndpoint(contextPath: String)
  : ContextMappedOperationEndpoint<TestPayload, TestPayload>(contextPath, allowNullPathInfo = true) {

  override val supportedMethods: Collection<String>
    get() = throw NotImplementedError("Not intended to be invoked")

  override fun executeOperation(request: RequestWithPayload<TestPayload>): Single<Response<TestPayload>> {
    throw NotImplementedError("Not intended to be invoked")
  }

  override fun generatePathInfoSpecification(schemaGenerator: JsonSchemaGenerator): OpenAPI.PathItem =
    OpenAPI.PathItem(
        post = OpenAPI.Operation(
            summary = "test operation",
            operationId = "operation"
        ).withRequestBody(OpenAPI.RequestBody.createJsonRequest(
            schemaGenerator.generateSchema(SerializerKey(TestPayload::class)),
            required = true)
        ).withResponse(OpenAPI.HttpStatusCode.OK, OpenAPI.Response.createJsonResponse(
            description = "default response",
            schema = schemaGenerator.generateSchema(SerializerKey(TestPayload::class)))
        )
    )
}

class TestQueryEndpoint(contextPath: String)
  : ContextMappedQueryEndpoint<TestPayload>(contextPath, allowNullPathInfo = true) {

  override fun executeQuery(request: Request): Response<TestPayload> {
    throw NotImplementedError("Not intended to be invoked")
  }

  override fun generatePathInfoSpecification(schemaGenerator: JsonSchemaGenerator): OpenAPI.PathItem =
      OpenAPI.PathItem(
          post = OpenAPI.Operation(
              summary = "test query",
              operationId = "query"
          ).withParameter(OpenAPI.Parameter(name = "param", `in` = OpenAPI.ParameterLocation.QUERY,
              description = "test parameter", required = true,
              schema = schemaGenerator.generateSchema(SerializerKey(TestPayload::class)))
          ).withResponse(OpenAPI.HttpStatusCode.OK, OpenAPI.Response.createJsonResponse(
              description = "default response",
              schema = schemaGenerator.generateSchema(SerializerKey(TestPayload::class)))
          )
      )
}

class TestPayload(val value: String)