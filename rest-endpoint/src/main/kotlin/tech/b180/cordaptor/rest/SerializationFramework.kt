package tech.b180.cordaptor.rest

import net.corda.serialization.internal.model.LocalTypeInformation
import java.lang.reflect.ParameterizedType
import java.lang.reflect.Type
import java.math.BigDecimal
import java.math.BigInteger
import javax.json.JsonObject
import javax.json.JsonValue
import javax.json.stream.JsonGenerator
import kotlin.reflect.KClass
import kotlin.reflect.KProperty
import kotlin.reflect.jvm.javaMethod

/**
 * Base class for creating serializers of structured JSON object,
 * represented in JSON schema as 'object' type with a fixed set of properties.
 * Subclasses are responsible for providing details of the properties,
 * as well as a way to obtain property values from an existing object and/or
 * means initialize a new instance from a set of property values.
 */
abstract class CustomStructuredObjectSerializer<T: Any>(
    override val appliedTo: KClass<T>,
    factory: SerializationFactory,
    override val serialize: Boolean = true,
    override val deserialize: Boolean = true
) : StructuredObjectSerializer<T>(appliedTo, factory), CustomSerializer<T>

abstract class CustomAbstractClassSerializer<T: Any>(
    override val appliedTo: KClass<T>,
    factory: SerializationFactory,
    override val serialize: Boolean = true,
    override val deserialize: Boolean = true
) : AbstractClassSerializer<T>(appliedTo, factory), CustomSerializer<T>

/**
 * Base implementation for JSON serializer that knows how to
 * read and write list of properties
 */
abstract class StructuredObjectSerializer<T: Any>(
    private val objectClass: KClass<*>,
    factory: SerializationFactory
) : JsonSerializer<T> {

  data class PropertyWithSerializer(
      private val property: ObjectProperty,
      val serializer: JsonSerializer<Any>
  ) : ObjectProperty by property

  /**
   * Override in subclasses to provide a list of properties to be
   * used for this serializer and produced JSON schema.
   */
  abstract val properties: Map<String, ObjectProperty>
  abstract val serialize: Boolean
  abstract val deserialize: Boolean

  private val structure: Map<String, PropertyWithSerializer> by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
    properties.mapValues { (name, property) ->
      try {
        PropertyWithSerializer(property, factory.getSerializer(property.valueType))
      } catch (e: Throwable) {
        throw SerializationException("Error finding serializer for property $name of object ${objectClass.qualifiedName}", e)
      }
    }
  }

  override val schema: JsonObject by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
    JsonHome.createObjectBuilder()
        .add("type", "object")
        .add("properties", JsonHome.createObjectBuilder().also { b ->
          structure.forEach { (name, prop) ->
            b.add(name, JsonHome.createObjectBuilder(prop.serializer.schema).also {
              if (!prop.serialize) {
                it.add("writeOnly", true)
              } else if (!prop.deserialize) {
                it.add("readOnly", true)
              }
            })
          }
        }.build())
        .add("required", structure.filterValues { it.isMandatory }.keys.asJsonArray())
        .build()
  }

  override fun fromJson(value: JsonValue): T {
    if (!deserialize) {
      throw UnsupportedOperationException("Cannot deserialize ${objectClass.qualifiedName} from JSON")
    }

    if (value.valueType != JsonValue.ValueType.OBJECT) {
      throw SerializationException("Expected JSON object, got ${value.valueType}")
    }

    val jsonObject = value as JsonObject

    // this logic driven from the introspection, so any unknown properties are silently ignored
    val values = structure.filterValues { it.deserialize }
        .mapValues { (propertyName, prop) ->

          val propValue = jsonObject[propertyName]

          // absence of value or explicit null are treated the same way
          if (propValue == null || propValue.valueType == JsonValue.ValueType.NULL) {
            if (prop.isMandatory) {
              throw SerializationException("Received null value for mandatory property $propertyName " +
                  "of type ${objectClass.qualifiedName}")
            }
          }

          // by this point if it's null, we know it's legitimate
          propValue?.let { prop.serializer.fromJson(it) }
        }

    return initializeInstance(values)
  }

  open fun initializeInstance(values: Map<String, Any?>): T {
    throw AssertionError("JSON Serialization is allowed for class ${objectClass.qualifiedName}, " +
        "  initializeInstance() method is not implemented")
  }

  override fun toJson(obj: T, generator: JsonGenerator) {
    if (!serialize) {
      throw UnsupportedOperationException("Cannot serialize ${objectClass.qualifiedName} to JSON")
    }

    generator.writeStartObject()
    structure.filterValues { it.serialize }.forEach { (propertyName, prop) ->

      val accessor = prop.accessor
          ?: throw AssertionError("No accessor for serializable property $propertyName " +
              "of class ${objectClass.qualifiedName}")

      val v = try {
        accessor.invoke(obj)
      } catch (e: Exception) {
        throw SerializationException("Reflection call failed while reading property $propertyName of object $obj", e)
      }

      if (v == null) {
        if (prop.isMandatory) {
          // Kotlin should not allow this, but there could be bugs in the introspection logic and/or non-Kotlin classes
          throw SerializationException("Null value in non-nullable property $propertyName in object $obj")
        }
        generator.writeNull(propertyName)
      } else {
        generator.writeKey(propertyName)
        prop.serializer.toJson(v, generator)
      }
    }
    generator.writeEnd()
  }
}

/**
 * Describes a property that is part of a JSON object schema,
 * and could be read and/or written by [StructuredObjectSerializer].
 */
interface ObjectProperty {
  val valueType: Type
  val deserialize: Boolean
  val serialize: Boolean
  val isMandatory: Boolean
  val accessor: ObjectPropertyValueAccessor?
}

typealias ObjectPropertyValueAccessor = (obj: Any) -> Any?

data class SyntheticObjectProperty(
    override val valueType: Type,
    override val accessor: ObjectPropertyValueAccessor? = null,
    override val serialize: Boolean = true,
    override val deserialize: Boolean = true,
    override val isMandatory: Boolean = false
) : ObjectProperty

data class KotlinObjectProperty<T: Any?>(
    val property: KProperty<T>,
    override val serialize: Boolean = true,
    override val deserialize: Boolean = true,
    override val isMandatory: Boolean = property.isMandatory()
) : ObjectProperty {

  override val valueType: Type = property.getter.javaMethod?.genericReturnType
      ?: throw AssertionError("No getter found for property ${property.name}")

  override val accessor: ObjectPropertyValueAccessor?
      get() = if (serialize) { obj -> property.getter.call(obj) } else null
}

private fun <R> KProperty<R>.isMandatory(): Boolean {
  return this.returnType.isMarkedNullable
}

internal val LocalTypeInformation.Composable.objectClass: KClass<*>
  get() = when (this.observedType) {
    is ParameterizedType -> ((this.observedType as ParameterizedType).rawType as Class<*>).kotlin
    is Class<*> -> (this.observedType as Class<*>).kotlin
    else -> throw AssertionError("Unexpected kind of observedType for composable ${this.prettyPrint()}")
  }

/**
 *
 */
abstract class AbstractClassSerializer<T: Any>(
    private val baseClass: KClass<T>,
    factory: SerializationFactory) : JsonSerializer<T> {

  abstract val subclassesMap: Map<String, SerializerKey>
  abstract val serialize: Boolean
  abstract val deserialize: Boolean

  private val subclassSerializers by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
    subclassesMap.mapValues { (_, key) ->
      factory.getSerializer(key)
    }
  }

  override fun fromJson(value: JsonValue): T {
    if (!deserialize) {
      throw UnsupportedOperationException("Cannot deserialize ${baseClass.qualifiedName} from JSON")
    }

    if (value.valueType != JsonValue.ValueType.OBJECT) {
      throw SerializationException("Expected JSON object, got ${value.valueType}")
    }

    val jsonObject = value as JsonObject
    val (discriminatorKey, serializer) = subclassSerializers
        .filterKeys { jsonObject.containsKey(it) }.entries.singleOrNull()
        ?: throw SerializationException("Unable to find a key for a subclass")

    val subclassObject = jsonObject.getJsonObject(discriminatorKey)!!

    @Suppress("UNCHECKED_CAST")
    return serializer.fromJson(subclassObject) as T
  }

  override fun toJson(obj: T, generator: JsonGenerator) {
    if (!serialize) {
      throw UnsupportedOperationException("Cannot serialize ${baseClass.qualifiedName} to JSON")
    }

    val entry = subclassesMap.entries.find { (_, key) ->
      key.rawType.isAssignableFrom(obj.javaClass) }
        ?: throw SerializationException("No matching entry for class ${obj.javaClass.canonicalName} " +
            "which is supposed subclass of ${baseClass.qualifiedName} -- missing entry in subclasses map?")

    val serializer = subclassSerializers[entry.key]
        ?: throw AssertionError("No initialized serializer for known subclass mapping $entry")

    generator.writeStartObject().writeKey(entry.key)
    serializer.toJson(obj, generator)
    generator.writeEnd()
  }

  override val schema: JsonObject by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
    JsonHome.createObjectBuilder()
        .add("type", "object")
        .add("properties", JsonHome.createObjectBuilder().also { builder ->
          for ((discriminatorKey, serializer) in subclassSerializers) {
            builder.add(discriminatorKey, serializer.schema)
          }
        })
        .build()
  }
}
