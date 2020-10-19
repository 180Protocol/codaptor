package tech.b180.cordaptor.rest

import net.corda.serialization.internal.model.LocalPropertyInformation
import net.corda.serialization.internal.model.LocalTypeInformation
import tech.b180.cordaptor.kernel.loggerFor
import java.lang.reflect.ParameterizedType
import javax.json.JsonArray
import javax.json.JsonObject
import javax.json.JsonValue
import javax.json.stream.JsonGenerator


/**
 * Generates a round-trip initializer for a type described by [LocalTypeInformation.Composable]
 * instance obtained from Corda introspection logic to ensure compatibility
 * with evolution semantics.
 */
class ComposableTypeJsonSerializer<T: Any>(
    private val typeInfo: LocalTypeInformation.Composable,
    factory: SerializationFactory
) : StructuredObjectSerializer<T>(factory = factory, explicitValueType = SerializerKey.forType(typeInfo.observedType)) {

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
          .mapValues { (_, prop) ->
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
    return JsonHome.createObjectBuilder()
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

  init {
    if (mapType.observedType == String::class) {
      throw SerializationException("Non-string keys are not supported")
    }
  }

  override val valueType = SerializerKey(Map::class.java, mapType.valueType.observedType)

  override fun generateSchema(generator: JsonSchemaGenerator): JsonObject {
    return JsonHome.createObjectBuilder()
        .add("type", "array")
        .add("additionalProperties", generator.generateSchema(valueSerializer.valueType))
        .build()
  }

  override fun fromJson(value: JsonValue): Map<Any?, Any?> {
    if (value.valueType != JsonValue.ValueType.OBJECT) {
      throw SerializationException("Expected an object, got ${value.valueType}")
    }

    return value.asJsonObject().map { (key, jsonValue) ->
      key to valueSerializer.fromJson(jsonValue)
    }.toMap()
  }

  override fun toJson(obj: Map<Any?, Any?>, generator: JsonGenerator) {
    generator.writeStartObject()
    obj.forEach { (key, value) ->
      if (key == null) {
        throw SerializationException("Null keys are not supported")
      }

      if (key !is String) {
        throw AssertionError("Map local type has a string key, but actual value is't a string: $key")
      }

      generator.writeKey(key)
      if (value == null) {
        generator.writeNull()
      } else {
        valueSerializer.toJson(value, generator)
      }
    }
    generator.writeEnd()
  }
}

/**
 * Generic serialization handler for all enum types
 * FIXME add enum evolution logic
 */
class EnumSerializer(
    private val enumType: LocalTypeInformation.AnEnum) : SerializationFactory.DelegatingSerializer<Enum<*>, String>(
    delegate = SerializationFactory.StringSerializer,
    my2delegate = Enum<*>::name,
    delegate2my = string2value(enumType)
) {

  private val schema: JsonObject = mapOf(
      "type" to "string",
      "enum" to enumType.members
  ).asJsonObject()

  override fun generateSchema(generator: JsonSchemaGenerator): JsonObject = schema

  companion object {
    fun string2value(enumType: LocalTypeInformation.AnEnum): (String) -> Enum<*> {
      return { stringValue: String ->
        if (!enumType.members.contains(stringValue)) {
          throw SerializationException("No such enum value $stringValue among ${enumType.members}")
        }

        @Suppress("UNCHECKED_CAST")
        val enumClass = enumType.observedType as Class<Enum<*>>

        // string value was among the introspected options, so something must be wrong with introspection if not found
        enumClass.enumConstants.find { it.name == stringValue }
            ?: throw AssertionError("Could not find enum constant $stringValue " +
                "in class ${enumClass.canonicalName}")
      }
    }
  }
}

class ThrowableSerializer(factory: SerializationFactory) : CustomStructuredObjectSerializer<Throwable>(
    explicitValueType = SerializerKey(Throwable::class),
    deserialize = false,
    factory = factory
) {

  override val properties: Map<String, ObjectProperty> = mapOf(
      "class" to SyntheticObjectProperty(valueType = Class::class.java, accessor = throwableClassAccessor),
      "message" to KotlinObjectProperty(property = Throwable::message),
      "cause" to SyntheticObjectProperty(valueType = String::class.java, accessor = throwableCauseAccessor)
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

  init {
    // FIXME mention subclass annotations when AbstractClassSerializer supports them
    logger.info("Subclasses of $valueType will be serialized dynamically without a JSON Schema. " +
        "Consider creating a custom abstract class serializer for it")
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