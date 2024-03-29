package tech.b180.cordaptor.rest

import net.corda.core.contracts.Amount
import net.corda.core.flows.FlowLogic
import net.corda.core.serialization.SerializableCalculatedProperty
import net.corda.serialization.internal.AllWhitelist
import net.corda.serialization.internal.amqp.CachingCustomSerializerRegistry
import net.corda.serialization.internal.amqp.DefaultDescriptorBasedSerializerRegistry
import net.corda.serialization.internal.amqp.WhitelistBasedTypeModelConfiguration
import net.corda.serialization.internal.model.ConfigurableLocalTypeModel
import net.corda.serialization.internal.model.LocalPropertyInformation
import net.corda.serialization.internal.model.LocalTypeInformation
import net.corda.serialization.internal.model.LocalTypeModel
import tech.b180.cordaptor.shaded.javax.json.*
import tech.b180.cordaptor.shaded.javax.json.stream.JsonGenerator
import java.beans.Transient
import java.io.StringReader
import java.io.StringWriter
import java.util.*
import kotlin.reflect.KClass
import kotlin.reflect.KProperty
import kotlin.reflect.full.instanceParameter
import kotlin.reflect.jvm.javaType
import kotlin.test.*

class SerializationTest {

  private val localTypeModel: LocalTypeModel

  init {
    val customSerializerRegistry = CachingCustomSerializerRegistry(DefaultDescriptorBasedSerializerRegistry())
    val typeModelConfiguration = WhitelistBasedTypeModelConfiguration(AllWhitelist, customSerializerRegistry)
    localTypeModel = ConfigurableLocalTypeModel(typeModelConfiguration)
  }

  @Test
  fun `test assumptions about primitive types`() {

    // testing assumptions about Corda type introspection
    assertTrue(localTypeModel.inspect(String::class.java) is LocalTypeInformation.Atomic)
    assertTrue(localTypeModel.inspect(Int::class.java) is LocalTypeInformation.Atomic)
    assertTrue(localTypeModel.inspect(Boolean::class.java) is LocalTypeInformation.Atomic)

    assertTrue(localTypeModel.inspect(Unit::class.java) is LocalTypeInformation.Singleton)

    assertTrue(localTypeModel.inspect(UUID::class.java) is LocalTypeInformation.Opaque)

    assertTrue(localTypeModel.inspect(Amount::class.java) is LocalTypeInformation.NonComposable)

    assertEquals(LocalTypeInformation.AnInterface::class, localTypeModel.inspect(KClass::class.java)::class)
  }

  @Test
  fun `test assumptions about composable types`() {

    val info = localTypeModel.inspect(TestDataObject::class.java)
    assertTrue(info is LocalTypeInformation.Composable)
    assertEquals(TestDataObject::class.java, info.observedType)

    val ctor = (info as LocalTypeInformation.Composable).constructor
    assertNotNull(ctor)

    val props = info.propertiesOrEmptyMap
    assertEquals(3, props.size)

    assertTrue(props.containsKey("one"))
    assertTrue(props.containsKey("two"))
    assertTrue(props.containsKey("three"))

    props["one"].also {
      assertTrue(it is LocalPropertyInformation.ConstructorPairedProperty)
      assertTrue((it as LocalPropertyInformation.ConstructorPairedProperty).isMandatory)
      assertEquals(0, it.constructorSlot.parameterIndex)
      assertEquals(ctor, it.constructorSlot.constructorInformation)
    }

    props["two"].also {
      assertTrue(it is LocalPropertyInformation.ConstructorPairedProperty)
      assertTrue((it as LocalPropertyInformation.ConstructorPairedProperty).isMandatory)
      assertEquals(1, it.constructorSlot.parameterIndex)
      assertEquals(ctor, it.constructorSlot.constructorInformation)
    }

    props["three"].also {
      assertTrue(it is LocalPropertyInformation.ConstructorPairedProperty)
      assertFalse((it as LocalPropertyInformation.ConstructorPairedProperty).isMandatory)
      assertEquals(2, it.constructorSlot.parameterIndex)
      assertEquals(ctor, it.constructorSlot.constructorInformation)
    }
  }

  @Test
  fun `test json generation API`() {
    assertEquals("""{"prop1":"value","prop2":123,"array":["str",123]}""", generateJson {
      writeStartObject()
      write("prop1", "value")
      write("prop2", 123)
      writeStartArray("array")
      write("str")
      write(123)
      writeEnd()
      writeEnd()
    })
  }

  @Test
  fun `test json object parser API`() {
    val obj = """{"prop1":"value","prop2":123,"array":["str",123]}""".asJsonObject()
    assertEquals("value", obj.getString("prop1"))
    assertEquals(123, obj.getInt("prop2"))
    assertEquals("str", obj.getJsonArray("array").getString(0))
    assertEquals(123, obj.getJsonArray("array").getInt(1))
  }

  @Test
  fun `test atomic string serializers`() {
    assertEquals("literal", SerializationFactory.StringSerializer.fromJson("\"literal\"".asJsonValue()))
    assertEquals("123", SerializationFactory.StringSerializer.fromJson("123".asJsonValue()))
    assertEquals("true", SerializationFactory.StringSerializer.fromJson("true".asJsonValue()))
    assertEquals("false", SerializationFactory.StringSerializer.fromJson("false".asJsonValue()))
    assertEquals("", SerializationFactory.StringSerializer.fromJson("null".asJsonValue()))

    assertEquals("\"literal\"",
      generateJson { SerializationFactory.StringSerializer.toJson("literal", this) })
  }

  @Test
  fun `test class name serializer`() {
    assertEquals(
      TestDataObject::class.java,
      SerializationFactory.JavaClassSerializer.fromJson("\"${TestDataObject::class.java.canonicalName}\"".asJsonValue())
    )

    assertEquals(
      TestDataObject::class,
      SerializationFactory.KotlinClassSerializer.fromJson("\"${TestDataObject::class.qualifiedName}\"".asJsonValue())
    )

    assertEquals("\"${TestDataObject::class.java.canonicalName}\"",
      generateJson { SerializationFactory.JavaClassSerializer.toJson(TestDataObject::class.java, this) })

    assertEquals("\"${TestDataObject::class.qualifiedName}\"",
      generateJson { SerializationFactory.KotlinClassSerializer.toJson(TestDataObject::class, this) })
  }

  @Test
  fun `test atomic integer serializers`() {
    assertEquals(123, SerializationFactory.IntSerializer.fromJson("123".asJsonValue()))

    assertEquals("123",
      generateJson { SerializationFactory.IntSerializer.toJson(123, this) })

    assertEquals(Integer(123), SerializationFactory.JavaIntegerSerializer.fromJson("123".asJsonValue()))

    assertEquals("123",
      generateJson { SerializationFactory.JavaIntegerSerializer.toJson(Integer(123), this) })
  }

  @Test
  fun `test composable type serializer`() {

    val f = SerializationFactory(lazy { emptyList<CustomSerializer<Any>>() },
      lazy { emptyList<CustomSerializerFactory<Any>>() })

    val publicPropertiesSerializer = f.getSerializer(TestDataObject::class)
    val privatePropertiesSerializer = f.getSerializer(NonPublicPropertiesObject::class)
    val pojoSerializer = f.getSerializer(TestPojo::class)

    assertEquals(
      """{"one":"123","two":123}""",
      publicPropertiesSerializer.toJsonString(TestDataObject("123", 123, null))
    )
    assertEquals(
      """{"one":"123","two":123}""",
      privatePropertiesSerializer.toJsonString(NonPublicPropertiesObject("123", 123))
    )
    assertEquals("""{"one":"123","two":321}""",
      pojoSerializer.toJsonString(TestPojo().apply { one = "123"; two = 321 })
    )

    assertEquals(
      TestDataObject("321", 321, null),
      publicPropertiesSerializer.fromJson("""{"one":"321","two":321}""".asJsonObject())
    )
    assertEquals(
      NonPublicPropertiesObject("321", 321),
      privatePropertiesSerializer.fromJson("""{"one":"321","two":321}""".asJsonObject())
    )
    assertEquals(
      TestPojo().apply { one = "123"; two = 321 },
      pojoSerializer.fromJson("""{"one":"123","two":321}""".asJsonObject())
    )
  }

  @Test
  fun `test collection types serializer`() {

    val f = SerializationFactory(lazy { emptyList<CustomSerializer<Any>>() },
      lazy { emptyList<CustomSerializerFactory<Any>>() })

    // roundabout way to make sure type comes with generic
    val arraySerializer = ListSerializer(localTypeModel.inspectProperty(ObjectWithParametrizedProperties::array), f)
    assertEquals(SerializerKey(List::class.java, String::class.java), arraySerializer.valueType)

    val listSerializer = ListSerializer(localTypeModel.inspectProperty(ObjectWithParametrizedProperties::list), f)
    assertEquals(SerializerKey(List::class.java, String::class.java), listSerializer.valueType)

    val mapSerializer = MapSerializer(localTypeModel.inspectProperty(ObjectWithParametrizedProperties::map), f)
    assertEquals(SerializerKey(Map::class.java, String::class.java, Int::class.java), mapSerializer.valueType)

    val enumMapSerializer = MapSerializer(localTypeModel.inspectProperty(ObjectWithParametrizedProperties::enumMap), f)
    assertEquals(SerializerKey(Map::class.java, TestEnum::class.java, String::class.java), enumMapSerializer.valueType)

    assertEquals("""["A","B","C"]""", arraySerializer.toJsonString(arrayOf("A", "B", "C")))
    assertEquals("""["A","B","C"]""", listSerializer.toJsonString(listOf("A", "B", "C")))
    assertEquals("""{"A":1,"B":2,"C":3}""", generateJson {

      @Suppress("UNCHECKED_CAST")
      writeSerializedObject(mapSerializer, mapOf("A" to 1, "B" to 2, "C" to 3) as Map<Any?, Any?>)
    })
    assertEquals("""{"VAL1":"A","VAL2":"B"}""", generateJson {

      @Suppress("UNCHECKED_CAST")
      writeSerializedObject(enumMapSerializer, mapOf(TestEnum.VAL1 to "A", TestEnum.VAL2 to "B") as Map<Any?, Any?>)
    })

    // arrays cannot be deserialized currently, so no test

    assertEquals(
      listOf("A", "B", "C"),
      listSerializer.fromJson("""["A","B","C"]""".asJsonValue())
    )
    assertEquals(
      mapOf("A" to 1, "B" to 2, "C" to 3),
      mapSerializer.fromJson("""{"A":1,"B":2,"C":3}""".asJsonValue()) as Any
    )

    val map = enumMapSerializer.fromJson("""{"VAL1":"A","VAL2":"B"}""".asJsonValue())
    assertEquals("A", map[TestEnum.VAL1])
    assertEquals("B", map[TestEnum.VAL2])
  }

  @Test
  fun `test enum type serialization`() {
    val f = SerializationFactory(lazy { emptyList<CustomSerializer<Any>>() },
      lazy { emptyList<CustomSerializerFactory<Any>>() })

    val serializer = f.getSerializer(TestEnum::class)

    assertEquals(""""VAL1"""", serializer.toJsonString(TestEnum.VAL1))

    assertEquals(TestEnum.VAL2, serializer.fromJson(""""VAL2"""".asJsonValue()))

    assertEquals(
      """{
      |"type": "string",
      |"enum": ["VAL1","VAL2"]}""".trimMargin().asJsonObject(), serializer.generateRecursiveSchema(f)
    )
  }

  @Test
  fun `test atomic types schema`() {
    val f = SerializationFactory(lazy { emptyList<CustomSerializer<Any>>() },
      lazy { emptyList<CustomSerializerFactory<Any>>() })
    assertEquals(
      """{"type": "string"}""".asJsonObject(),
      SerializationFactory.StringSerializer.generateRecursiveSchema(f)
    )
    assertEquals(
      """{"type": "boolean"}""".asJsonObject(),
      SerializationFactory.BooleanSerializer.generateRecursiveSchema(f)
    )
    assertEquals(
      """{"type": "number", "format": "int32"}""".asJsonObject(),
      SerializationFactory.IntSerializer.generateRecursiveSchema(f)
    )
    assertEquals("""{"type": "null"}""".asJsonObject(), SerializationFactory.UnitSerializer.generateRecursiveSchema(f))
  }

  @Test
  fun `test composite type schema`() {
    val f = SerializationFactory(lazy { emptyList<CustomSerializer<Any>>() },
      lazy { emptyList<CustomSerializerFactory<Any>>() })

    val publicPropertiesSerializer = f.getSerializer(TestDataObject::class)

    assertEquals(
      """{
      |"type": "object",
      |"properties": {
      | "one":{
      |   "type":"string"
      | },
      | "two":{
      |   "type":"number",
      |   "format":"int32"
      | },
      | "three":{
      |   "type":"number",
      |   "format":"int32"
      | }
      |},
      |"required":[
      | "one",
      | "two"
      |]
      |}""".trimMargin().asJsonObject(), publicPropertiesSerializer.generateRecursiveSchema(f)
    )
  }

  @Test
  fun `test collection types schema`() {
    val f = SerializationFactory(lazy { emptyList<CustomSerializer<Any>>() },
      lazy { emptyList<CustomSerializerFactory<Any>>() })

    // roundabout way to make sure type comes with generic
    val arraySerializer = ListSerializer(localTypeModel.inspectProperty(ObjectWithParametrizedProperties::array), f)
    val listSerializer = ListSerializer(localTypeModel.inspectProperty(ObjectWithParametrizedProperties::list), f)
    val mapSerializer = MapSerializer(localTypeModel.inspectProperty(ObjectWithParametrizedProperties::map), f)

    assertEquals(
      """{
      |"type":"array",
      |"items":{"type":"string"}
      |}""".trimMargin().asJsonObject(), arraySerializer.generateRecursiveSchema(f)
    )

    assertEquals(
      """{
      |"type":"array",
      |"items":{"type":"string"}
      |}""".trimMargin().asJsonObject(), listSerializer.generateRecursiveSchema(f)
    )

    assertEquals(
      """{
      |"type":"object",
      |"additionalProperties":{"type":"number","format":"int32"}
      |}""".trimMargin().asJsonObject(), mapSerializer.generateRecursiveSchema(f)
    )
  }

  @Test
  fun `test class literals serialization`() {
    val f = SerializationFactory(lazy { emptyList<CustomSerializer<Any>>() },
      lazy { emptyList<CustomSerializerFactory<Any>>() })

    val kotlinClassSerializer = f.getSerializer(KClass::class.java)
    val javaClassSerializer = f.getSerializer(Class::class.java)

    assertEquals("\"net.corda.core.flows.FlowLogic\"", kotlinClassSerializer.toJsonString(FlowLogic::class))
    assertEquals("\"net.corda.core.flows.FlowLogic\"", javaClassSerializer.toJsonString(FlowLogic::class.java))
  }

  @Test
  fun `test transient annotation`() {
    val f = SerializationFactory(lazy { emptyList<CustomSerializer<Any>>() },
      lazy { emptyList<CustomSerializerFactory<Any>>() })

    val serializer = f.getSerializer(ObjectWithTransientProperties::class.java)

    assertEquals(
      """{"one":"123","two":123}""", serializer.toJsonString(
        ObjectWithTransientProperties("123", 123, 321)
      )
    )
  }

  @Test
  fun `test calculated property annotation`() {
    val f = SerializationFactory(lazy { emptyList<CustomSerializer<Any>>() },
      lazy { emptyList<CustomSerializerFactory<Any>>() })

    val serializer = f.getSerializer(ObjectWithCalculatedProperties::class.java)

    assertEquals(
      """{"one":"123","two":123}""", serializer.toJsonString(
        ObjectWithCalculatedProperties("123")
      )
    )
  }

  @Test
  fun `test abstract class serialization`() {
    val f = SerializationFactory(lazy { emptyList<CustomSerializer<Any>>() },
      lazy { emptyList<CustomSerializerFactory<Any>>() })

    val serializer = object : CustomAbstractClassSerializer<BaseObject>(f) {
      override val subclassesMap = mapOf(
        "one" to DerivedObjectOne::class,
        "two" to DerivedObjectTwo::class,
        "singleton" to DerivedSingleton::class
      )
    }

    assertEquals(
      """{"type":"one","stringValue":"ABC"}""",
      serializer.toJsonString(DerivedObjectOne("ABC"))
    )
    assertEquals(
      """{"type":"two","intValue":123}""",
      serializer.toJsonString(DerivedObjectTwo(123))
    )
    assertEquals(
      """{"type":"singleton"}""",
      serializer.toJsonString(DerivedSingleton)
    )

    assertEquals(
      DerivedObjectOne("ABC"),
      serializer.fromJson("""{"type":"one","stringValue":"ABC"}""".asJsonObject())
    )
    assertEquals(
      DerivedObjectTwo(123),
      serializer.fromJson("""{"type":"two","intValue":123}""".asJsonObject())
    )
    assertSame(
      DerivedSingleton,
      serializer.fromJson("""{"type":"singleton"}""".asJsonObject())
    )

    assertFailsWith(SerializationException::class) {
      serializer.fromJson("""{"type":"unknown"}""".asJsonObject())
    }
  }

  @Test
  fun `test typed schema for nested parameterized objects`() {
    val f = SerializationFactory(lazy { emptyList<CustomSerializer<Any>>() },
      lazy { emptyList<CustomSerializerFactory<Any>>() })

    val serializer1 = f.getSerializer(
      SerializerKey(ParameterizedObjectsContainer::class, DerivedObjectOne::class).localType
    )
    val serializer2 = f.getSerializer(
      SerializerKey(ParameterizedObjectsContainer::class, DerivedObjectTwo::class).localType
    )

    val schema1 = serializer1.generateRecursiveSchema(f)
    val schema2 = serializer2.generateRecursiveSchema(f)

    println(schema1)

    assertEquals(
      """{"stringValue":{"type":"string"}}""".asJsonObject(),
      schema1.getValue("/properties/list/items/properties/value/properties")
    )

    assertEquals(
      """{"stringValue":{"type":"string"}}""".asJsonObject(),
      schema1.getValue("/properties/map/additionalProperties/properties/value/properties")
    )

    assertEquals(
      """{"stringValue":{"type":"string"}}""".asJsonObject(),
      schema1.getValue("/properties/nestedSingle/properties/value/properties/value/properties")
    )

    assertEquals(
      """{"intValue":{"type":"number","format":"int32"}}""".asJsonObject(),
      schema2.getValue("/properties/list/items/properties/value/properties")
    )

    assertEquals(
      """{"intValue":{"type":"number","format":"int32"}}""".asJsonObject(),
      schema2.getValue("/properties/map/additionalProperties/properties/value/properties")
    )

    assertEquals(
      """{"intValue":{"type":"number","format":"int32"}}""".asJsonObject(),
      schema2.getValue("/properties/nestedSingle/properties/value/properties/value/properties")
    )
  }
}

/** Simple implementation for tests that just embeds nested schemas recursively into the resulting JSON Schema */
class RecursiveJsonSchemaGenerator(private val factory: SerializationFactory) : JsonSchemaGenerator {
  override fun generateSchema(key: SerializerKey) = factory.getSerializer(key).generateSchema(this)
}

fun <T: Any> JsonSerializer<T>.generateRecursiveSchema(factory: SerializationFactory) =
  generateSchema(RecursiveJsonSchemaGenerator(factory))

fun generateJson(block: JsonGenerator.() -> Unit): String {
  val w = StringWriter()
  val gen = Json.createGenerator(w)
  gen.apply(block)
  gen.flush()
  return w.toString()
}

fun <T> JsonSerializer<T>.toJsonString(obj: T): String {
  return generateJson { this@toJsonString.toJson(obj, this) }
}

fun <E> LocalTypeModel.inspectProperty(prop: KProperty<Array<E>>): LocalTypeInformation.AnArray {
  val p = this.inspect(prop.instanceParameter!!.type.javaType).propertiesOrEmptyMap[prop.name]
      ?: throw AssertionError()
  return p.type as LocalTypeInformation.AnArray
}

fun <E> LocalTypeModel.inspectProperty(prop: KProperty<List<E>>): LocalTypeInformation.ACollection {
  val p = this.inspect(prop.instanceParameter!!.type.javaType).propertiesOrEmptyMap[prop.name]
      ?: throw AssertionError()
  return p.type as LocalTypeInformation.ACollection
}

fun <K, V> LocalTypeModel.inspectProperty(prop: KProperty<Map<K, V>>): LocalTypeInformation.AMap {
  val p = this.inspect(prop.instanceParameter!!.type.javaType).propertiesOrEmptyMap[prop.name]
      ?: throw AssertionError()
  return p.type as LocalTypeInformation.AMap
}

fun String.asJsonObject(): JsonObject {
  val r = Json.createReader(StringReader(this))
  return r.readObject()
}

fun String.asJsonValue(): JsonValue {
  val r = Json.createReader(StringReader(this))
  return r.readValue()
}

fun JsonValue.asString(): String = (this as JsonString).string
fun JsonValue.asInt(): Int = (this as JsonNumber).intValue()

data class TestDataObject(
    val one: String,
    val two: Int,
    val three: Int?
)

/**
 * This type acts as a container for properties that have parameterized type
 * to make it easier to set up various test conditions
 */
data class ObjectWithParametrizedProperties(
    val array: Array<String>,
    val list: List<String>,
    val map: Map<String, Int>,
    val enumMap: Map<TestEnum, String>
)

/**
 * These objects have private properties accessed via reflection
 */
data class NonPublicPropertiesObject(
    private val one: String,
    private val two: Int
)

/**
 * These objects only have getters and setters, no constructor
 */
data class TestPojo private constructor(var one: String?, var two: Int?) {
  constructor() : this(null, null)
}

enum class TestEnum {
  VAL1,
  VAL2
}

data class ObjectWithTransientProperties(
    val one: String,
    val two: Int,
    @get:Transient val three: Int
)

data class ObjectWithCalculatedProperties(
    val one: String
) {
  @get:SerializableCalculatedProperty val two
      get() = one.toInt()
}

abstract class BaseObject

data class DerivedObjectOne(val stringValue: String) : BaseObject()
data class DerivedObjectTwo(val intValue: Int) : BaseObject()
object DerivedSingleton : BaseObject()

data class ParameterizedObject<T: BaseObject>(val value: T) : BaseObject()
data class ParameterizedObjectsContainer<T: BaseObject>(
    val list: List<ParameterizedObject<T>>,
    val map: Map<String, ParameterizedObject<T>>,
    val nestedSingle: ParameterizedObject<ParameterizedObject<T>>
)
