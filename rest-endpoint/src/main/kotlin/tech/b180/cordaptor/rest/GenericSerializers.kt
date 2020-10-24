package tech.b180.cordaptor.rest

import net.corda.serialization.internal.model.LocalPropertyInformation
import net.corda.serialization.internal.model.LocalTypeInformation
import tech.b180.cordaptor.kernel.loggerFor
import tech.b180.cordaptor.shaded.javax.json.*
import tech.b180.cordaptor.shaded.javax.json.stream.JsonGenerator
import java.lang.reflect.ParameterizedType


/**
 * Generates a round-trip initializer for a type described by [LocalTypeInformation.Composable]
 * instance obtained from Corda introspection logic to ensure compatibility
 * with evolution semantics.
 */
class ComposableTypeJsonSerializer<T: Any>(
    private val typeInfo: LocalTypeInformation.Composable,
    factory: SerializationFactory
) : StructuredObjectSerializer<T>(factory = factory, explicitValueType = SerializerKey.forType(typeInfo.observedType)) {

  companion object {
    val logger = loggerFor<ComposableTypeJsonSerializer<*>>()
  }

  data class IntrospectedProperty(
      override val accessor: ObjectPropertyValueAccessor?,
      val propInfo: LocalPropertyInformation
  ) : ObjectProperty {
    override val valueType = propInfo.type.observedType
    override val deserialize = !propInfo.isCalculated
    override val serialize = true
    override val isMandatory = propInfo.isMandatory
  }

  // In Corda composable objects always support round-trip serialization
  override val serialize = true
  override val deserialize = true

  override val properties: Map<String, IntrospectedProperty> =
      typeInfo.properties
          .filterValues { prop ->
            // skip properties that have getters market with java.beans.Transient annotation
            when (prop) {
              is LocalPropertyInformation.ConstructorPairedProperty ->
                prop.observedGetter.getAnnotation(java.beans.Transient::class.java)?.value?.not() ?: true
              is LocalPropertyInformation.GetterSetterProperty ->
                prop.observedGetter.getAnnotation(java.beans.Transient::class.java)?.value?.not() == null
              is LocalPropertyInformation.ReadOnlyProperty ->
                prop.observedGetter.getAnnotation(java.beans.Transient::class.java)?.value?.not() == null
              else -> true
            }
          }
          .mapValues { (name, prop) ->
            // if there is a custom serializer for this type, it will be an instance of Opaque
            if (prop.type is LocalTypeInformation.Top
                || prop.type is LocalTypeInformation.AnInterface
                || prop.type is LocalTypeInformation.Abstract) {

              // FIXME mention annotations when abstract class serializer supports them
              logger.info("Values for property $name in type $valueType will be serialized dynamically " +
                  "without a JSON Schema. Consider creating a custom abstract class serializer for it")
            }

            val accessor: ObjectPropertyValueAccessor? = when (prop) {
              is LocalPropertyInformation.ConstructorPairedProperty -> { obj: Any -> prop.observedGetter.invoke(obj) }
              is LocalPropertyInformation.PrivateConstructorPairedProperty -> {
                prop.observedField.apply {
                  if (!isAccessible) {
                    try {
                      isAccessible = true
                    } catch (e: Exception) {
                      throw SerializationException("Unable to make private field ${prop.observedField} accessible", e)
                    }
                  }
                };
                { obj: Any -> prop.observedField.get(obj) }
              }
              is LocalPropertyInformation.CalculatedProperty -> { obj: Any -> prop.observedGetter.invoke(obj) }
              is LocalPropertyInformation.GetterSetterProperty -> { obj: Any -> prop.observedGetter.invoke(obj) }
              is LocalPropertyInformation.ReadOnlyProperty -> { obj: Any -> prop.observedGetter.invoke(obj) }
              else -> null
            }
            IntrospectedProperty(accessor, prop)
          }

  override fun initializeInstance(values: Map<String, Any?>): T {
    val ctor = typeInfo.constructor
    val ctorArgs = MutableList<Any?>(ctor.parameters.size) { null }
    val fieldInitializers : ArrayList<(Any) -> Unit> = ArrayList()

    properties.forEach { (propertyName, prop) ->

      // by this point if it's null, we know it's legitimate
      val objValue = values[propertyName]
      when (prop.propInfo) {
        is LocalPropertyInformation.ConstructorPairedProperty ->
          ctorArgs[prop.propInfo.constructorSlot.parameterIndex] = objValue
        is LocalPropertyInformation.PrivateConstructorPairedProperty ->
          ctorArgs[prop.propInfo.constructorSlot.parameterIndex] = objValue
        is LocalPropertyInformation.GetterSetterProperty ->
          fieldInitializers.add(createFieldInitializer(prop.propInfo, objValue))
        is LocalPropertyInformation.ReadOnlyProperty -> {}
        is LocalPropertyInformation.CalculatedProperty -> {}
        else -> throw AssertionError("Unexpected kind of an object property: ${prop.javaClass}")
      }
    }

    val obj =
        try {
          ctor.observedMethod.newInstance(*ctorArgs.toTypedArray())
        } catch (e: Exception) {
          throw SerializationException("Reflection call failed for constructor ${ctor.observedMethod}", e)
        }

    // exceptions during reflection calls are handled within the initializers and then wrapped
    fieldInitializers.forEach { it.invoke(obj) }

    @Suppress("UNCHECKED_CAST")
    return obj as T
  }

  private fun createFieldInitializer(prop: LocalPropertyInformation.GetterSetterProperty, value: Any?): (Any) -> Unit {
    return { obj: Any ->
      try {
        prop.observedSetter.invoke(obj, value)
      } catch (e: Exception) {
        throw SerializationException("Reflection call failed for setter ${prop.observedSetter}", e)
      }
    }
  }
}


/**
 * Implements a logic for serializing lists and arrays to/from JSON strings.
 */
class ListSerializer private constructor(
    private val elementSerializer: JsonSerializer<Any>,
    private val instantiationFunction: (List<Any>) -> Any,
    private val iteratorFunction: (Any?) -> Iterator<Any?>
) : JsonSerializer<Any> {

  constructor(collectionType: LocalTypeInformation.ACollection, serializationFactory: SerializationFactory) : this(
      elementSerializer = serializationFactory.getSerializer(collectionType.elementType),
      instantiationFunction = if (collectionType.observedType is ParameterizedType) {
        val parameterizedType = collectionType.observedType as ParameterizedType
        // FIXME additional handling is required for non-ArrayLists e.g. LinkedList
        when (parameterizedType.rawType) {
          Collection::class.java -> ::newArrayList
          List::class.java -> ::newArrayList
          else -> throw AssertionError("Don't know how to make instances of ${parameterizedType.rawType}")
        }
      } else {
        throw AssertionError("Don't know how to make instances of ${collectionType.observedType}")
      },
      iteratorFunction = ::iteratorOfACollection
  )

  constructor(arrayType: LocalTypeInformation.AnArray, serializationFactory: SerializationFactory) : this(
      elementSerializer = serializationFactory.getSerializer(arrayType.componentType),
      iteratorFunction = ::iteratorOfAnArray,
      instantiationFunction = ::newArray
  )

  override val valueType = SerializerKey(List::class.java, elementSerializer.valueType.asType())

  companion object {
    fun newArrayList(items: List<*>) = ArrayList(items)
    // FIXME instantiate an array of a certain type via Java reflection
    fun newArray(items: List<*>): Array<Any> = TODO("Deserialization of arrays is not supported yet")

    fun iteratorOfAnArray(c: Any?) = c?.let { (c as Array<*>).iterator() }
        ?: throw AssertionError("Null instead of an array - unsafe code in the parent serializer")
    fun iteratorOfACollection(c: Any?) = c?.let { (c as Collection<*>).iterator() }
        ?: throw AssertionError("Null instead of a collection - unsafe code in the parent serializer")
  }

  override fun generateSchema(generator: JsonSchemaGenerator): JsonObject {
    return Json.createObjectBuilder()
        .add("type", "array")
        .add("items", generator.generateSchema(elementSerializer.valueType))
        .build()
  }

  override fun fromJson(value: JsonValue): Any {
    if (value.valueType != JsonValue.ValueType.ARRAY) {
      throw SerializationException("Expected an array, got ${value.valueType}")
    }

    val items = (value as JsonArray).map { itemValue ->
      elementSerializer.fromJson(itemValue)
    }

    return instantiationFunction(items)
  }

  override fun toJson(obj: Any, generator: JsonGenerator) {
    generator.writeStartArray()
    iteratorFunction(obj).forEach {
      if (it == null) {
        generator.writeNull()
      } else {
        elementSerializer.toJson(it,  generator)
      }
    }
    generator.writeEnd()
  }

}

/**
 * Serialization handler for map types
 */
class MapSerializer(
    mapType: LocalTypeInformation.AMap,
    serializationFactory: SerializationFactory
) : JsonSerializer<Map<Any?, Any?>> {

  private val valueSerializer = serializationFactory.getSerializer(mapType.valueType)
  private val keySerializer: JsonSerializer<Any>

  init {
    val keyType = mapType.keyType.observedType
    keySerializer = if (keyType == String::class.java) {
      serializationFactory.getSerializer(SerializerKey(String::class))
    } else {
      if (mapType.keyType is LocalTypeInformation.AnEnum) {
        serializationFactory.getSerializer(mapType.keyType.observedType)
      } else {
        throw SerializationException("Only string and enum keys are supported, got $keyType")
      }
    }
  }

  override val valueType = SerializerKey(Map::class.java, mapType.keyType.observedType, mapType.valueType.observedType)

  override fun generateSchema(generator: JsonSchemaGenerator): JsonObject {
    return Json.createObjectBuilder()
        .add("type", "array")
        .add("additionalProperties", generator.generateSchema(valueSerializer.valueType))
        .build()
  }

  override fun fromJson(value: JsonValue): Map<Any?, Any?> {
    if (value.valueType != JsonValue.ValueType.OBJECT) {
      throw SerializationException("Expected an object, got ${value.valueType}")
    }

    return value.asJsonObject().map { (key, jsonValue) ->
      // key serializer is passed an outright string value wrapped into JsonValue
      // in order to not duplicate the logic for dealing with serializable enums
      keySerializer.fromJson(Json.createValue(key)) to
          valueSerializer.fromJson(jsonValue)
    }.toMap()
  }

  override fun toJson(obj: Map<Any?, Any?>, generator: JsonGenerator) {
    generator.writeStartObject()
    obj.forEach { (key, value) ->
      if (key == null) {
        throw SerializationException("Null keys are not supported")
      }

      // cannot use keySerializer here, as generator API cannot write key value in a generic way
      val keyValue = (key as? String) ?: (key as? SerializableEnum)?.jsonValue ?: (key as? Enum<*>)?.name
          ?: throw AssertionError("Unable to convert key value [$key] to string")

      generator.writeKey(keyValue)
      if (value == null) {
        generator.writeNull()
      } else {
        valueSerializer.toJson(value, generator)
      }
    }
    generator.writeEnd()
  }
}

/** Use to override string values for enum constants serialized by [EnumSerializer] */
interface SerializableEnum {
  val jsonValue: String
}

/**
 * Generic serialization handler for all enum types
 * FIXME add enum evolution logic
 * FIXME consider support for enums serialized as non-string JSON values
 */
class EnumSerializer(
    private val enumClass: Class<Enum<*>>,
    override val valueType: SerializerKey
) : JsonSerializer<Enum<*>> {

  @Suppress("UNCHECKED_CAST")
  constructor(introspectedType: LocalTypeInformation.AnEnum) : this(
      enumClass = introspectedType.observedType as Class<Enum<*>>,
      valueType = SerializerKey.forType(introspectedType.observedType))

  private val members: Map<Enum<*>, /* value in JSON */ String> = enumClass.enumConstants.map {
    // use JsonValue provided by serializable enum if available, otherwise just constant's code name
    it to ((it as? SerializableEnum)?.jsonValue ?: it.name)
  }.toMap()

  private val schema: JsonObject = mapOf(
      "type" to "string",
      "enum" to members.values
  ).asJsonObject()

  override fun fromJson(value: JsonValue): Enum<*> {
    if (value.valueType != JsonValue.ValueType.STRING) {
      throw SerializationException("Expected a string, got ${value.valueType}")
    }

    val stringValue = (value as JsonString).string
    val entry = members.entries.find { (_, value) -> stringValue == value }
        ?: throw SerializationException("No such value $stringValue among enum constants ${members.values}")

    return entry.key
  }

  override fun toJson(obj: Enum<*>, generator: JsonGenerator) {
    val jsonValue = members[obj] ?: throw AssertionError(
        "Unknown member $obj for enum ${enumClass.canonicalName}")
    generator.write(jsonValue)
  }

  override fun generateSchema(generator: JsonSchemaGenerator): JsonObject = schema
}

class ThrowableSerializer(factory: SerializationFactory) : CustomStructuredObjectSerializer<Throwable>(
    explicitValueType = SerializerKey(Throwable::class),
    deserialize = false,
    factory = factory
) {

  override val properties: Map<String, ObjectProperty> = mapOf(
      "class" to SyntheticObjectProperty(valueType = Class::class.java, accessor = throwableClassAccessor),
      "message" to KotlinObjectProperty(property = Throwable::message),
      "cause" to SyntheticObjectProperty(valueType = Any::class.java, accessor = throwableCauseAccessor)
  ).toMap()

  @Suppress("UNCHECKED_CAST")
  companion object {
    val throwableClassAccessor = { it: Throwable -> it.javaClass } as ObjectPropertyValueAccessor
    val throwableCauseAccessor = { it: Throwable -> it.cause.toString() } as ObjectPropertyValueAccessor
  }
}

/**
 * Serializer that determines the type of the object in runtime and
 * attempts to serialize it using [SerializationFactory].
 * This serializer is unable to restore objects from JSON.
 *
 * FIXME consider alternative solution for ContractState and CommandData implementations
 * Perhaps discovering all implementations as part of CorDapp scanning and registering in a map
 */
class DynamicObjectSerializer(
    override val valueType: SerializerKey,
    private val factory: SerializationFactory) : CustomSerializer<Any> {

  companion object {
    val logger = loggerFor<DynamicObjectSerializer>()
  }

  override fun fromJson(value: JsonValue): Any {
    throw UnsupportedOperationException("Don't know not to restore an untyped object from JSON")
  }

  override fun toJson(obj: Any, generator: JsonGenerator) {
    val serializer = try {
       factory.getSerializer(obj.javaClass)
    } catch (e: Exception) {
      throw SerializationException("Unable to dynamically find a serializer for type ${obj.javaClass.canonicalName}", e)
    }

    serializer.toJson(obj, generator)
  }

  private val schema: JsonObject = mapOf(
      "type" to "object",
      "description" to "Dynamic container for subclasses of ${valueType.rawType.canonicalName}",
      "additionalProperties" to "true").asJsonObject()

  override fun generateSchema(generator: JsonSchemaGenerator): JsonObject = schema
}

/**
 * Pass-through serializer for [JsonObject]
 * This is intended to be used as part of OpenAPI specification generation.
 */
class JsonObjectSerializer : CustomSerializer<JsonObject> {

  override val valueType = SerializerKey(JsonObject::class.java)

  override fun fromJson(value: JsonValue): JsonObject {
    if (value.valueType != JsonValue.ValueType.OBJECT) {
      throw SerializationException("Expected an object, got ${value.valueType}")
    }
    return value as JsonObject
  }

  override fun toJson(obj: JsonObject, generator: JsonGenerator) {
    generator.write(obj)
  }

  private val schema: JsonObject = mapOf(
      "type" to "object",
      "description" to "JSON object generated dynamically by the application",
      "additionalProperties" to "true").asJsonObject()

  override fun generateSchema(generator: JsonSchemaGenerator): JsonObject = schema
}