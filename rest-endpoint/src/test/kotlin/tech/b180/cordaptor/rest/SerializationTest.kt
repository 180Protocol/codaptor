package tech.b180.cordaptor.rest

import net.corda.core.contracts.Amount
import net.corda.serialization.internal.AllWhitelist
import net.corda.serialization.internal.amqp.CachingCustomSerializerRegistry
import net.corda.serialization.internal.amqp.DefaultDescriptorBasedSerializerRegistry
import net.corda.serialization.internal.amqp.WhitelistBasedTypeModelConfiguration
import net.corda.serialization.internal.model.ConfigurableLocalTypeModel
import net.corda.serialization.internal.model.LocalPropertyInformation
import net.corda.serialization.internal.model.LocalTypeInformation
import net.corda.serialization.internal.model.LocalTypeModel
import java.awt.Point
import java.io.StringReader
import java.io.StringWriter
import java.util.*
import javax.json.*
import javax.json.stream.JsonGenerator
import kotlin.reflect.KProperty
import kotlin.reflect.full.instanceParameter
import kotlin.reflect.jvm.javaType
import kotlin.test.*

class SerializationTest {

  val localTypeModel: LocalTypeModel

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

    assertTrue(localTypeModel.inspect(UUID::class.java) is LocalTypeInformation.Opaque)

    assertTrue(localTypeModel.inspect(Amount::class.java) is LocalTypeInformation.NonComposable)
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
        generateJson { SerializationFactory.StringSerializer.toJson("literal", this) } )
  }

  @Test
  fun `test atomic integer serializers`() {
    assertEquals(123, SerializationFactory.IntSerializer.fromJson("123".asJsonValue()))

    assertEquals("123",
        generateJson { SerializationFactory.IntSerializer.toJson(123, this) } )

    assertEquals(Integer(123), SerializationFactory.JavaIntegerSerializer.fromJson("123".asJsonValue()))

    assertEquals("123",
        generateJson { SerializationFactory.JavaIntegerSerializer.toJson(Integer(123), this) } )
  }

  @Test
  fun `test composable type serializer`() {

    val f = SerializationFactory(localTypeModel)

    val publicPropertiesSerializer = f.getSerializer(TestDataObject::class)
    val privatePropertiesSerializer = f.getSerializer(NonPublicPropertiesObject::class)
    val pojoSerializer = f.getSerializer(TestPojo::class)

    assertEquals("""{"one":"123","three":null,"two":123}""", generateJson {
      writeSerializedObject(publicPropertiesSerializer, TestDataObject("123", 123, null))
    })
    assertEquals("""{"one":"123","two":123}""", generateJson {
      writeSerializedObject(privatePropertiesSerializer, NonPublicPropertiesObject("123", 123))
    })
    assertEquals("""{"one":"123","two":321}""", generateJson {
      writeSerializedObject(pojoSerializer, TestPojo(one = "123", two = 321))
    })

    assertEquals(TestDataObject("321", 321, null),
        publicPropertiesSerializer.fromJson("""{"one":"321","two":321}""".asJsonObject()))
    assertEquals(NonPublicPropertiesObject("321", 321),
        privatePropertiesSerializer.fromJson("""{"one":"321","two":321}""".asJsonObject()))
    assertEquals(TestPojo(one = "123", two = 321),
        pojoSerializer.fromJson("""{"one":"123","two":321}""".asJsonObject()))
  }

  @Test
  fun `test collection types serializer`() {

    val f = SerializationFactory(localTypeModel)

    // roundabout way to make sure type comes with generic
    val arraySerializer = ListSerializer(localTypeModel.inspectProperty(ObjectWithParametrizedProperties::array), f)
    val listSerializer = ListSerializer(localTypeModel.inspectProperty(ObjectWithParametrizedProperties::list), f)
    val mapSerializer = MapSerializer(localTypeModel.inspectProperty(ObjectWithParametrizedProperties::map), f)

    assertEquals("""["A","B","C"]""", generateJson {
      arraySerializer.toJson(arrayOf("A", "B", "C"), this)
    })
    assertEquals("""["A","B","C"]""", generateJson {
      listSerializer.toJson(listOf("A", "B", "C"), this)
    })
    assertEquals("""{"A":1,"B":2,"C":3}""", generateJson {
      mapSerializer.toJson(mapOf("A" to 1, "B" to 2, "C" to 3), this)
    })

    // arrays cannot be deserialized currently, so no test

    assertEquals(listOf("A", "B", "C"),
        listSerializer.fromJson("""["A","B","C"]""".asJsonValue()))
    assertEquals(mapOf("A" to 1, "B" to 2, "C" to 3),
        mapSerializer.fromJson("""{"A":1,"B":2,"C":3}""".asJsonValue()) as Any)
  }

  private fun generateJson(block: JsonGenerator.() -> Unit): String {
    val w = StringWriter()
    val gen = Json.createGenerator(w)
    gen.apply(block)
    gen.flush()
    return w.toString()
  }
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
    val map: Map<String, Int>
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
data class TestPojo(var one: String?, var two: Int?) {
}