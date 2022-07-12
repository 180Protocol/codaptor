package tech.b180.cordaptor.rest

import io.undertow.server.handlers.form.FormData
import tech.b180.cordaptor.kernel.ModuleAPI
import tech.b180.cordaptor.shaded.javax.json.Json
import tech.b180.cordaptor.shaded.javax.json.JsonObject
import tech.b180.cordaptor.shaded.javax.json.JsonString
import tech.b180.cordaptor.shaded.javax.json.JsonValue
import tech.b180.cordaptor.shaded.javax.json.stream.JsonGenerator
import kotlin.reflect.KClass
import kotlin.reflect.KProperty
import kotlin.reflect.jvm.javaMethod

/**
 * Used by JSON Schema generator to determine if serialized type needs to be extracted
 * into a shared components section and what type name to use for it.
 */
@ModuleAPI(since = "0.1")
interface StandaloneTypeSerializer {
  val valueType: SerializerKey

  /** Default implementation uses [valueType] that is also declared in [JsonSerializer] */
  val schemaTypeName: String
    get() {
      val baseName = generateSchemaTypeBaseName(valueType.rawType)
      return if (!valueType.isParameterized) {
        baseName
      } else {
        "${baseName}_${valueType.typeParameters.map { it.rawType.simpleName }.joinToString("_")}"
      }
    }
}

fun generateSchemaTypeBaseName(clazz: Class<*>): String =
    (if (clazz.canonicalName.startsWith("net.corda")) "Corda" else "") +
        (clazz.enclosingClass?.simpleName ?: "") + clazz.simpleName


/**
 * Base class for creating serializers of structured JSON object,
 * represented in JSON schema as 'object' type with a fixed set of properties.
 * Subclasses are responsible for providing details of the properties,
 * as well as a way to obtain property values from an existing object and/or
 * means initialize a new instance from a set of property values.
 */
@ModuleAPI(since = "0.1")
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
@ModuleAPI(since = "0.1")
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
) : MultiPartFormDataSerializer<T>, JsonSerializer<T>, StandaloneTypeSerializer {

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

  override fun fromMultiPartFormData(data: FormData): T {
    if (!deserialize) {
      throw UnsupportedOperationException("Cannot deserialize $valueType from JSON")
    }

    // this logic driven from the introspection, so any unknown properties are silently ignored
    val values = structure.filterNot { (data.get(it.key) == null && !it.value.isMandatory) || !it.value.deserialize }
      .mapValues { (propertyName, prop) ->

        val propValue = data.get(propertyName)

        // absence of value or explicit null are treated the same way
        if (!data.contains(propertyName) || propValue == null ) {
          if (prop.isMandatory) {
            throw SerializationException("Received null value for mandatory property $propertyName " +
                    "of type $valueType")
          }
        }

        // by this point if it's null, we know it's legitimate
        propValue?.let {
          when (prop.serializer) {
            is MultiPartFormValueSerializer -> {
              prop.serializer.fromMultiPartFormValue(it.first)
            }
            is SerializationFactory.PrimitiveTypeSerializer -> {
              prop.serializer.fromMultiPartFormValue(it.first)
            }
            is MultiPartFormTransformValueSerializer<*,*> -> {
              prop.serializer.transformValue(it.first)
            }
            else -> throw SerializationException("${prop.serializer} is not of type MultiPartFormValueSerializer or MultiPartFormTransformValueSerializer and cannot handle multipart/form-data")
          }
        }
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
@ModuleAPI(since = "0.1")
interface ObjectProperty {
  val valueType: SerializerKey
  val deserialize: Boolean
  val serialize: Boolean
  val isMandatory: Boolean
  val accessor: ObjectPropertyValueAccessor?
}

typealias ObjectPropertyValueAccessor = (obj: Any) -> Any?

@ModuleAPI(since = "0.1")
data class SyntheticObjectProperty(
    override val valueType: SerializerKey,
    override val accessor: ObjectPropertyValueAccessor? = null,
    override val serialize: Boolean = true,
    override val deserialize: Boolean = true,
    override val isMandatory: Boolean = false
) : ObjectProperty

@ModuleAPI(since = "0.1")
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
    explicitValueType: SerializerKey? = null,

    /** Must be selected not to clash with any of the properties in subclasses */
    private val discriminatorKey: String = DEFAULT_DISCRIMINATOR_KEY
) : JsonSerializer<T>, StandaloneTypeSerializer {

  companion object {
    const val DEFAULT_DISCRIMINATOR_KEY = "type"
  }

  abstract val subclassesMap: Map<String, KClass<out T>>
  abstract val serialize: Boolean
  abstract val deserialize: Boolean

  private val subclassSerializers by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
    subclassesMap.mapValues { (_, subclass) ->
      // null value is the special case for singletons (Kotlin 'object' declarations),
      // which are only signposted with discriminator value, but not actually serialized
      if (subclass.objectInstance == null) {
        @Suppress("UNCHECKED_CAST")
        factory.getSerializer(subclass) as JsonSerializer<T>
      } else {
        null
      }
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
    if (!jsonObject.containsKey(discriminatorKey)) {
      throw SerializationException("No value for mandatory discriminator property $discriminatorKey " +
          "when attempting to deserialize an instance of $valueType")
    }
    val discriminatorJsonValue = jsonObject.getValue("/$discriminatorKey")
    val discriminatorValue = (discriminatorJsonValue as? JsonString)?.string
        ?: throw SerializationException("Invalid discriminator property value [$discriminatorJsonValue] " +
            "when attempting to deserialize an instance of $valueType")

    if (!subclassSerializers.containsKey(discriminatorValue)) {
      throw SerializationException("Unknown value $discriminatorValue for discriminator property $discriminatorKey " +
          "when attempting to deserialize an instance of $valueType")
    }

    return subclassSerializers[discriminatorValue]?.fromJson(jsonObject)
        // handle special case of a singleton subclass
        ?: subclassesMap[discriminatorValue]?.objectInstance
        ?: throw SerializationException("No available deserialization strategy for subclass $discriminatorValue " +
            "when attempting to deserialize an instance of $valueType")
  }

  override fun toJson(obj: T, generator: JsonGenerator) {
    if (!serialize) {
      throw UnsupportedOperationException("Cannot serialize $valueType to JSON")
    }

    val entry = subclassesMap.entries.find { it.value.isInstance(obj) }
        ?: throw SerializationException("No matching entry for class ${obj.javaClass.canonicalName} " +
            "which is supposed subclass of $valueType -- missing entry in subclasses map?")

    val discriminatorValue = entry.key
    val serializer = subclassSerializers[entry.key]

    generator.writeStartObject()
    generator.write(discriminatorKey, discriminatorValue)

    serializer?.toJson(obj, InterceptingJsonGenerator(generator))
        ?: generator.writeEnd() // the serializer will invoke writeEnd, so only need to do so if there isn't one
  }

  override fun generateSchema(generator: JsonSchemaGenerator): JsonObject {
    return Json.createObjectBuilder()
        .addObjectForEach("oneOf", subclassSerializers.entries) { (discriminatorValue, serializer) ->
          if (serializer != null) {
            // defined serializer means it's a nested object, so actual schema is a union type
            // between proper object schema and its discriminator property
            addArray("allOf") {
              add(generator.generateSchema(serializer.valueType))
              addObject {
                add("type", "object")
                addObject("properties") {
                  addObject(discriminatorKey) {
                    add("type", "string")
                    addArray("enum") {
                      add(discriminatorValue)
                    }
                  }
                }
                addArray("required") {
                  add("type")
                }
              }
            }
          } else {
            // no serializer means it's a singleton, for which only type property is expected
            add("type", "object")
            addObject("properties") {
              addObject(discriminatorKey) {
                add("type", "string")
                addArray("enum") {
                  add(discriminatorValue)
                }
              }
            }
            addArray("required") {
              add("type")
            }
          }
        }.addObject("discriminator") {
          add("propertyName", discriminatorKey)
        }.build()
  }

  // This is dangerous as most methods return this, which means it will be the delegate and not the interceptor
  // however, all serializers writing JSON objects will call writeStartObject() as a first action,
  // after which it should not matter whether it is still a delegate or not. Still, it's a potential source of bugs.
  class InterceptingJsonGenerator(private val delegate: JsonGenerator) : JsonGenerator by delegate {
    private var intercepted = false

    override fun writeStartObject(): JsonGenerator {
      if (intercepted)
        return delegate.writeStartObject()

      intercepted = true
      return this
    }
  }
}
