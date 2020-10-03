package tech.b180.cordaptor.rest

import net.corda.serialization.internal.model.LocalPropertyInformation
import net.corda.serialization.internal.model.LocalTypeInformation
import net.corda.serialization.internal.model.LocalTypeModel
import java.lang.reflect.ParameterizedType
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import javax.json.*
import javax.json.stream.JsonGenerator
import kotlin.collections.ArrayList
import kotlin.reflect.KClass

class SerializationException(
    override val message: String,
    override val cause: Throwable? = null) : Exception()

@Suppress("UNCHECKED_CAST")
class SerializationFactory(
    private val localTypeModel: LocalTypeModel
) {

  private val objectSerializers = ConcurrentHashMap<LocalTypeInformation, JsonSerializer<Any>>()

  init {
    objectSerializers[localTypeModel.inspect(String::class.java)] = StringSerializer as JsonSerializer<Any>
    objectSerializers[localTypeModel.inspect(Int::class.java)] = IntSerializer as JsonSerializer<Any>

    // nullable values use Java versions of the primitive type wrappers
    objectSerializers[localTypeModel.inspect(Integer::class.java)] = JavaIntegerSerializer as JsonSerializer<Any>
    objectSerializers[localTypeModel.inspect(java.lang.Boolean::class.java)] = JavaBooleanSerializer as JsonSerializer<Any>
  }

  /** Built-in serializer for atomic value of type [String] */
  object StringSerializer : JsonSerializer<String> {
    override fun fromJson(value: JsonValue): String {
      return when (value.valueType) {
        // provide limited number of type conversions
        JsonValue.ValueType.TRUE -> true.toString()
        JsonValue.ValueType.FALSE -> false.toString()
        JsonValue.ValueType.NULL -> ""
        JsonValue.ValueType.NUMBER -> value.toString()
        JsonValue.ValueType.STRING -> (value as JsonString).string
        else -> throw AssertionError("Expected string, got ${value.valueType} with value $value")
      }
    }

    override fun toJson(obj: String, generator: JsonGenerator) {
      generator.write(obj)
    }
  }

  /** Built-in serializer for atomic value of type [Int] */
  object IntSerializer : JsonSerializer<Int> {
    override fun fromJson(value: JsonValue): Int {
      return when (value.valueType) {
        // provide limited number of type conversions
        JsonValue.ValueType.NUMBER -> (value as JsonNumber).intValue()  // discard fractional part
        JsonValue.ValueType.STRING -> Integer.parseInt((value as JsonString).string)
        else -> throw AssertionError("Expected integer, got ${value.valueType} with value $value")
      }
    }

    override fun toJson(obj: Int, generator: JsonGenerator) {
      generator.write(obj)
    }
  }

  /** Built-in serializer for atomic value of type [Boolean] */
  object BooleanSerializer : JsonSerializer<Boolean> {
    override fun fromJson(value: JsonValue): Boolean {
      return when (value.valueType) {
        // provide limited number of type conversions
        JsonValue.ValueType.TRUE -> true  // discard fractional part
        JsonValue.ValueType.FALSE -> false
        else -> throw AssertionError("Expected boolean, got ${value.valueType} with value $value")
      }
    }

    override fun toJson(obj: Boolean, generator: JsonGenerator) {
      generator.write(obj)
    }
  }

  // Delegate serialization logic for Java wrapper types to Kotlin handlers
  object JavaIntegerSerializer : DelegatingSerializer<java.lang.Integer, Int>(
      IntSerializer, Integer::toInt, { Integer(this) })
  object JavaBooleanSerializer : DelegatingSerializer<java.lang.Boolean, Boolean>(
      BooleanSerializer, java.lang.Boolean::booleanValue, { java.lang.Boolean(this) })

  abstract class DelegatingSerializer<MyType, DelegatedType>(
      private val delegate: JsonSerializer<DelegatedType>,
      private val my2delegate: MyType.() -> DelegatedType,
      private val delegate2my: DelegatedType.() -> MyType
  ) : JsonSerializer<MyType> {
    override fun fromJson(value: JsonValue): MyType {
      return delegate2my(delegate.fromJson(value))
    }

    override fun toJson(obj: MyType, generator: JsonGenerator) {
      delegate.toJson(my2delegate(obj), generator)
    }
  }

//  init {
//    getAll<CustomSerializer<*>>().forEach {
//      objectSerializers[it.objectClass] = it
//    }
//  }

  /**
   * Looks up a serializer for a specified class available in a specific context
   * This method is most likely to be used within code calling serialization logic.
   */
  fun <T : Any> getSerializer(clazz: KClass<T>): JsonSerializer<T> {
    return getSerializer(localTypeModel.inspect(clazz.java)) as JsonSerializer<T>
  }

  /**
   * Looks up a serializer for a specified type created by Corda introspection.
   * This method is most likely to be used within generic serialization logic.
   */
  fun getSerializer(type: LocalTypeInformation): JsonSerializer<Any> {
    return objectSerializers.getOrPut(type, {
      createSerializer(type)
    })!!  // this map will never has null values
  }

  private fun createSerializer(type: LocalTypeInformation): JsonSerializer<Any> {
    return when (type) {
      is LocalTypeInformation.Composable -> ComposableTypeJsonSerializer(type, this)
      is LocalTypeInformation.AnArray -> ListSerializer(type, this)
      is LocalTypeInformation.ACollection -> ListSerializer(type, this)
      is LocalTypeInformation.AnEnum -> EnumSerializer(type) as JsonSerializer<Any>
      is LocalTypeInformation.AMap -> MapSerializer(type, this) as JsonSerializer<Any>
      else -> throw AssertionError("Don't know how to create a serializer for $type")
    }
  }
}

//class PartySerializer : CustomSerializer<Party>, KoinComponent {
//  override val objectClass = Party::class.java
//
//  private val x500NameSerializer: JsonSerializer<CordaX500Name>
//      by inject { parametersOf(CordaX500Name::class) }
//
//  override fun fromJson(reader: JsonReader): Party {
//    TODO("Not yet implemented")
//  }
//
//  override fun toJson(obj: Party, generator: JsonGenerator) {
//    generator
//        .writeStartObject()
//        .writeSerializedObject("name", x500NameSerializer, obj.name)
//        .writeEnd()
//  }
//
//}
//
//class CordaSignedTransactionSerializer : CustomSerializer<SignedTransaction>, KoinComponent {
//
//  override val objectClass = SignedTransaction::class.java
//
//  private val stateRefSerializer: JsonSerializer<StateRef>
//      by inject { parametersOf(StateRef::class) }
//  private val txStateSerializer: JsonSerializer<TransactionState<*>>
//      by inject { parametersOf(TransactionState::class) }
//  private val txSignatureSerializer: JsonSerializer<TransactionSignature>
//      by inject { parametersOf(TransactionSignature::class) }
//  private val publicKeySerializer: JsonSerializer<PublicKey> by inject { parametersOf(PublicKey::class) }
//  private val partySerializer: JsonSerializer<Party> by inject { parametersOf(Party::class) }
//
//  override fun toJson(tx: SignedTransaction, generator: JsonGenerator) {
//    generator
//        .writeStartObject()
//        .write("hash", tx.id.toString())
//        .write("type", tx.coreTransaction::class.simpleName)
//        .writeSerializedObjectOrNull("notary", partySerializer, tx.notary)
//        .writeSerializedArray("inputs", stateRefSerializer, tx.inputs)
//        .writeSerializedArray("references", stateRefSerializer, tx.references, true)
//        .writeSerializedArray("outputs", txStateSerializer, tx.coreTransaction.outputs)
//        .writeSerializedArray("requiredSigningKeys", publicKeySerializer, tx.requiredSigningKeys)
//        .writeSerializedArray("sigs", txSignatureSerializer, tx.sigs)
//
//    if (tx.coreTransaction is WireTransaction) {
//      generator.writeStartArray("attachments")
//      tx.tx.attachments.forEach { generator.write(it.toString()) }
//      generator.writeEnd()
//    } else if (tx.coreTransaction is NotaryChangeWireTransaction) {
//      val nctx = tx.coreTransaction as NotaryChangeWireTransaction
//      generator.writeSerializedObject("newNotary", partySerializer, nctx.newNotary)
//    }
//
//    generator.writeEnd()
//  }
//
//  override fun fromJson(reader: JsonReader): SignedTransaction {
//    throw AssertionError("Not required")
//  }
//}

/**
 * Helper method allowing [JsonSerializer] to be used in a fluent way.
 * Use to generate a field called [name] in the object context.
 */
fun <T: Any> JsonGenerator.writeSerializedArray(
    name: String,
    serializer: JsonSerializer<T>,
    items: Collection<T>,
    omitIfEmpty: Boolean = false): JsonGenerator {

  if (omitIfEmpty && items.isEmpty()) {
    return this;
  }
  this.writeStartArray(name)
  items.forEach { serializer.toJson(it, this) }
  this.writeEnd()
  return this
}

/**
 * Helper method allowing [JsonSerializer] to be used in a fluent way.
 * Use to generate just an array in the current context.
 */
fun <T: Any> JsonGenerator.writeSerializedArray(
    serializer: JsonSerializer<T>,
    items: List<T>): JsonGenerator {

  this.writeStartArray()
  items.forEach { serializer.toJson(it, this) }
  this.writeEnd()
  return this
}

/**
 * Helper method allowing [JsonSerializer] to be used in a fluent way
 */
fun <T: Any> JsonGenerator.writeSerializedObject(
    name: String,
    serializer: JsonSerializer<T>,
    obj: T): JsonGenerator {

  this.writeKey(name)
  serializer.toJson(obj, this)
  return this
}

/**
 * Helper method allowing [JsonSerializer] to be used in a fluent way
 */
fun <T: Any> JsonGenerator.writeSerializedObject(
    serializer: JsonSerializer<T>,
    obj: T): JsonGenerator {

  serializer.toJson(obj, this)
  return this
}

/**
 * Helper method allowing [JsonSerializer] to be used in a fluent way
 */
fun <T: Any> JsonGenerator.writeSerializedObjectOrNull(
    name: String,
    serializer: JsonSerializer<T>,
    obj: T?): JsonGenerator {

  if (obj == null) {
    this.writeNull()
  } else {
    serializer.toJson(obj, this)
  }
  return this
}

/**
 * Base interface for all serializers.
 *
 * For deserialization the whole JSON structure is expected to be read into [JsonObject]
 * ahead of time to make parsing logic easier.
 *
 * For serialization lower level [JsonGenerator] API is used to gain efficiency
 * for larger data sets. It is assumed that outbound data volumes will largely exceed inbound.
 */
interface JsonSerializer<T> {

  /**
   * The value is passed in as-is without any validation, so the implementation
   * must guard against just input and fail with a meaningful error message.
   *
   * The implementation is expected to be liberal in what is can accept as a value,
   * e.g. if a string is passed in when a number is expected, an attempt to parse the string must be made
   */
  fun fromJson(value: JsonValue) : T

  /**
   * Passed generator can be in a root, array or field context, so the implementation needs
   * to manage its context accordingly, e.g. start an object or an array as appropriate.
   */
  fun toJson(obj: T, generator: JsonGenerator)
}

/**
 * Marker interface aiding in custom-coded serializer discovery
 */
interface CustomSerializer<T> : JsonSerializer<T>

/**
 * Generates a round-trip initializer for a composable type
 * using Corda-generated introspection logic to ensure compatibility
 * with evolution semantics.
 */
class ComposableTypeJsonSerializer(
    private val type: LocalTypeInformation.Composable,
    private val factory: SerializationFactory
) : JsonSerializer<Any> {

  data class PropertyAndSerializer(
      val property: LocalPropertyInformation,
      val serializer: JsonSerializer<Any>
  )

  private val properties = lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
    type.properties.map { (name, property) ->
      // unlock private fields during the initialization
      if (property is LocalPropertyInformation.PrivateConstructorPairedProperty) {
        property.observedField.isAccessible = true
      }

      name to PropertyAndSerializer(property, factory.getSerializer(property.type))
    }.toMap()
  }

  override fun fromJson(value: JsonValue): Any {
    if (value.valueType != JsonValue.ValueType.OBJECT) {
      throw SerializationException("Expected object of type $type, got ${value.valueType}")
    }

    val jsonObject = value as JsonObject

    val ctor = type.constructor
    val ctorArgs = MutableList<Any?>(ctor.parameters.size) { null }
    val fieldInitializers : ArrayList<(Any) -> Unit> = ArrayList()

    // this logic driven from the introspection, so any unknown properties are silently ignored
    properties.value.forEach { (propertyName, propInfo) ->
      val (prop, serializer) = propInfo

      val propValue = jsonObject[propertyName]

      // absence of value or explicit null are treated the same way
      if (propValue == null || propValue.valueType == JsonValue.ValueType.NULL) {
        if (prop.isMandatory) {
          throw SerializationException("Received null value for mandatory property $prop")
        }
      }

      // by this point if it's null, we know it's legitimate
      val objValue = propValue?.let { serializer.fromJson(it) }
      when (prop) {
        is LocalPropertyInformation.ConstructorPairedProperty ->
          ctorArgs[prop.constructorSlot.parameterIndex] = objValue
        is LocalPropertyInformation.PrivateConstructorPairedProperty ->
          ctorArgs[prop.constructorSlot.parameterIndex] = objValue
        is LocalPropertyInformation.GetterSetterProperty ->
          fieldInitializers.add(createFieldInitializer(prop, objValue))
        else -> throw AssertionError("Don't know how to deserialize value for property $prop")
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

    return obj;
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

  override fun toJson(obj: Any, generator: JsonGenerator) {
    generator.writeStartObject()
    properties.value.forEach { (propertyName, propInfo) ->
      val (prop, serializer) = propInfo

      val v = try {
        when (prop) {
          is LocalPropertyInformation.ConstructorPairedProperty -> {
            prop.observedGetter.invoke(obj)
          }
          is LocalPropertyInformation.PrivateConstructorPairedProperty -> {
            prop.observedField.get(obj)
          }
          is LocalPropertyInformation.GetterSetterProperty -> {
            prop.observedGetter.invoke(obj)
          }
          else -> throw AssertionError("Don't know how to obtain value for property $prop")
        }
      } catch (e: AssertionError) {
        throw e
      } catch (e: Exception) {
        throw SerializationException("Reflection call failed while reading property $prop", e)
      }

      if (v == null) {
        if (prop.isMandatory) {
          // Kotlin should not allow this, but there could be bugs in the introspection logic and/or non-Kotlin classes
          throw SerializationException("Null value in non-nullable property $prop in object $obj")
        }
        generator.writeNull(propertyName)
      } else {
        generator.writeKey(propertyName)
        serializer.toJson(v, generator)
      }
    }

    generator.writeEnd()
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

  companion object {
    fun newArrayList(items: List<*>) = ArrayList(items)
    // FIXME instantiate an array of a certain type via Java reflection
    fun newArray(items: List<*>): Array<Any> = TODO("Deserialization of arrays is not supported yet")

    fun iteratorOfAnArray(c: Any?) = c?.let { (c as Array<*>).iterator() }
        ?: throw AssertionError("Null instead of an array - unsafe code in the parent serializer")
    fun iteratorOfACollection(c: Any?) = c?.let { (c as Collection<*>).iterator() }
        ?: throw AssertionError("Null instead of a collection - unsafe code in the parent serializer")
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
    private val mapType: LocalTypeInformation.AMap,
    serializationFactory: SerializationFactory
) : JsonSerializer<Map<Any?, Any?>> {

  private val valueSerializer = serializationFactory.getSerializer(mapType.valueType)

  init {
    if (mapType.observedType == String::class) {
      throw SerializationException("Non-string keys are not supported")
    }
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
    private val enumType: LocalTypeInformation.AnEnum) : JsonSerializer<Enum<*>> {

  @Suppress("UNCHECKED_CAST")
  private val enumClass = enumType.observedType as Class<Enum<*>>

  override fun fromJson(value: JsonValue): Enum<*> {
    if (value.valueType != JsonValue.ValueType.STRING) {
      throw SerializationException("Expected a string, got ${value.valueType}")
    }

    val stringValue = (value as JsonString).string
    if (!enumType.members.contains(stringValue)) {
      throw SerializationException("No such enum value $stringValue among ${enumType.members}")
    }

    // string value was among the introspected options, so something must be wrong with introspection if not found
    return enumClass.enumConstants.find { it.name == stringValue }
        ?: throw AssertionError("Could not find enum constant $stringValue in class $enumClass")
  }

  override fun toJson(obj: Enum<*>, generator: JsonGenerator) {
    generator.write(obj.name)
  }
}