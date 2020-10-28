package tech.b180.cordaptor.rest

import tech.b180.cordaptor.shaded.javax.json.Json
import tech.b180.cordaptor.shaded.javax.json.JsonObject
import tech.b180.cordaptor.shaded.javax.json.JsonValue
import tech.b180.cordaptor.shaded.javax.json.stream.JsonGenerator
import kotlin.reflect.KProperty
import kotlin.reflect.jvm.javaMethod

/**
 * Used by JSON Schema generator to determine if serialized type needs to be extracted
 * into a shared components section and what type name to use for it.
 */
interface StandaloneTypeSerializer {
  val schemaTypeName: String
}

/**
 * Base class for creating serializers of structured JSON object,
 * represented in JSON schema as 'object' type with a fixed set of properties.
 * Subclasses are responsible for providing details of the properties,
 * as well as a way to obtain property values from an existing object and/or
 * means initialize a new instance from a set of property values.
 */
abstract class CustomStructuredObjectSerializer<T: Any>(
    factory: SerializationFactory,

    /**
     * Indicates whether objects can be represented as JSON structures.
     * Note that if this flag is set to false, settings on individual properties are ignored.
     */
    override val serialize: Boolean = true,

    /**
     * Indicates whether objects can be restored from JSON structures.
     * Note that if this flag is set to false, settings on individual properties are ignored.
     */
    override val deserialize: Boolean = true,

    /** @see StructuredObjectSerializer */
    explicitValueType: SerializerKey? = null

) : StructuredObjectSerializer<T>(factory, explicitValueType), CustomSerializer<T>

/**
 * Base class for creating serializers of JSON objects which represent one of a number
 * of subclasses of the the base class identified by [valueType] property.
 *
 * Subclasses are responsible for providing details of the mapping.
 */
abstract class CustomAbstractClassSerializer<T: Any>(
    factory: SerializationFactory,

    /**
     * Indicates whether objects can be represented as JSON structures.
     * Note that if this flag is set to false, settings on individual properties are ignored.
     */
    override val serialize: Boolean = true,

    /**
     * Indicates whether objects can be restored from JSON structures.
     * Note that if this flag is set to false, settings on individual properties are ignored.
     */
    override val deserialize: Boolean = true,

    /** @see AbstractClassSerializer */
    explicitValueType: SerializerKey? = null

) : AbstractClassSerializer<T>(factory, explicitValueType), CustomSerializer<T>

/**
 * Base implementation for JSON serializer that knows how to
 * read and write list of properties
 */
abstract class StructuredObjectSerializer<T: Any>(
    factory: SerializationFactory,

    /** If null, it will be inferred from the type parameter passed in by the superclass */
    explicitValueType: SerializerKey? = null
) : JsonSerializer<T>, StandaloneTypeSerializer {

  data class PropertyWithSerializer(
      private val property: ObjectProperty,
      val serializer: JsonSerializer<Any>
  ) : ObjectProperty by property

  override val valueType = explicitValueType
      ?: SerializerKey.fromSuperclassTypeArgument(StructuredObjectSerializer::class, this::class)

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
        throw SerializationException("Error finding serializer for property $name of object $valueType", e)
      }
    }
  }

  override val schemaTypeName: String
    get() {
      val baseName = generateSchemaTypeBaseName(valueType.rawType)
      return if (!valueType.isParameterized) {
        baseName
      } else {
        "${baseName}_${valueType.typeParameters.map { it.rawType.simpleName }.joinToString("_")}"
      }
    }

  override fun generateSchema(generator: JsonSchemaGenerator): JsonObject {
    return Json.createObjectBuilder()
        .add("type", "object")
        .addObject("properties") {
          structure.forEach { (name, prop) ->
            addModifiedObject(name, generator.generateSchema(prop.serializer.valueType)) {
              if (!prop.serialize) {
                add("writeOnly", true)
              } else if (!prop.deserialize) {
                add("readOnly", true)
              }
            }
          }
        }
        .add("required", structure.filterValues { it.isMandatory }.keys.asJsonArray())
        .build()
  }

  override fun fromJson(value: JsonValue): T {
    if (!deserialize) {
      throw UnsupportedOperationException("Cannot deserialize $valueType from JSON")
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
                  "of type $valueType")
            }
          }

          // by this point if it's null, we know it's legitimate
          propValue?.let { prop.serializer.fromJson(it) }
        }

    return initializeInstance(values)
  }

  open fun initializeInstance(values: Map<String, Any?>): T {
    throw AssertionError("JSON Serialization is not allowed for class $valueType, " +
        "  initializeInstance() method is not implemented in ${this::class}")
  }

  override fun toJson(obj: T, generator: JsonGenerator) {
    if (!serialize) {
      throw UnsupportedOperationException("Cannot serialize $valueType to JSON")
    }

    generator.writeStartObject()
    structure.filterValues { it.serialize }.forEach { (propertyName, prop) ->

      val accessor = prop.accessor
          ?: throw AssertionError("No accessor for serializable property $propertyName " +
              "of class $valueType")

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
        // not writing null value
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
  val valueType: SerializerKey
  val deserialize: Boolean
  val serialize: Boolean
  val isMandatory: Boolean
  val accessor: ObjectPropertyValueAccessor?
}

typealias ObjectPropertyValueAccessor = (obj: Any) -> Any?

data class SyntheticObjectProperty(
    override val valueType: SerializerKey,
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

  override val valueType = SerializerKey.forType(property.getter.javaMethod?.genericReturnType
      ?: throw AssertionError("No getter found for property ${property.name}"))

  override val accessor: ObjectPropertyValueAccessor?
      get() = if (serialize) { obj -> property.getter.call(obj) } else null
}

private fun <R> KProperty<R>.isMandatory(): Boolean {
  return !this.returnType.isMarkedNullable
}

/**
 * Serializer for an abstract class with a known set of concrete subclasses.
 * This serializer is expected to be subclassed to create custom serializers.
 *
 * FIXME implement logic for automatically generating correct subclass mapping from annotations
 */
abstract class AbstractClassSerializer<T: Any>(
    factory: SerializationFactory,

    /** If null, it will be inferred from the type parameter passed in by the superclass */
    explicitValueType: SerializerKey? = null
) : JsonSerializer<T> {

  abstract val subclassesMap: Map<String, SerializerKey>
  abstract val serialize: Boolean
  abstract val deserialize: Boolean

  private val subclassSerializers by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
    subclassesMap.mapValues { (_, key) ->
      factory.getSerializer(key)
    }
  }

  override val valueType = explicitValueType
      ?: SerializerKey.fromSuperclassTypeArgument(AbstractClassSerializer::class, this::class)

  override fun fromJson(value: JsonValue): T {
    if (!deserialize) {
      throw UnsupportedOperationException("Cannot deserialize $valueType from JSON")
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
      throw UnsupportedOperationException("Cannot serialize $valueType to JSON")
    }

    val entry = subclassesMap.entries.find { (_, key) ->
      key.rawType.isAssignableFrom(obj.javaClass) }
        ?: throw SerializationException("No matching entry for class ${obj.javaClass.canonicalName} " +
            "which is supposed subclass of $valueType -- missing entry in subclasses map?")

    val serializer = subclassSerializers[entry.key]
        ?: throw AssertionError("No initialized serializer for known subclass mapping $entry")

    generator.writeStartObject().writeKey(entry.key)
    serializer.toJson(obj, generator)
    generator.writeEnd()
  }

  override fun generateSchema(generator: JsonSchemaGenerator): JsonObject {
    return Json.createObjectBuilder()
        .add("type", "object")
        .add("properties", Json.createObjectBuilder().also { builder ->
          for ((discriminatorKey, serializer) in subclassSerializers) {
            builder.add(discriminatorKey, generator.generateSchema(serializer.valueType))
          }
        })
        .build()
  }
}
