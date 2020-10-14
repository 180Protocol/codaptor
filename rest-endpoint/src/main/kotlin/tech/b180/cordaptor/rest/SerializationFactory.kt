package tech.b180.cordaptor.rest

import net.corda.core.contracts.TransactionState
import net.corda.serialization.internal.AllWhitelist
import net.corda.serialization.internal.amqp.CachingCustomSerializerRegistry
import net.corda.serialization.internal.amqp.DefaultDescriptorBasedSerializerRegistry
import net.corda.serialization.internal.amqp.WhitelistBasedTypeModelConfiguration
import net.corda.serialization.internal.model.BaseLocalTypes
import net.corda.serialization.internal.model.ConfigurableLocalTypeModel
import net.corda.serialization.internal.model.LocalTypeInformation
import net.corda.serialization.internal.model.LocalTypeModelConfiguration
import org.glassfish.json.JsonProviderImpl
import java.io.PrintWriter
import java.io.Reader
import java.io.StringWriter
import java.lang.reflect.ParameterizedType
import java.lang.reflect.Type
import java.util.concurrent.ConcurrentHashMap
import javax.json.*
import javax.json.stream.JsonGenerator
import kotlin.reflect.KClass

class SerializationException(
    override val message: String,
    override val cause: Throwable? = null) : Exception()

@Suppress("UNCHECKED_CAST")
class SerializationFactory(
    private val lazySerializers: Lazy<List<CustomSerializer<Any>>>
) {
  private var lazyInitialization = false

  private val customSerializers by lazySerializers

  private val localTypeModel by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
    val customSerializerRegistry = CachingCustomSerializerRegistry(DefaultDescriptorBasedSerializerRegistry())
    val typeModelConfiguration = ExtendedTypeModelConfiguration(
        delegate = WhitelistBasedTypeModelConfiguration(AllWhitelist, customSerializerRegistry),
        // add all types with custom serializers to the opaque list, so that
        // Corda does not flag any transitive introspection problems
        additionalOpaqueTypes = customSerializers.map { it.appliedTo.java }.toSet()
    )
    ConfigurableLocalTypeModel(typeModelConfiguration)
  }

  private val objectSerializers by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
    if (lazyInitialization) {
      throw IllegalStateException("SerializationFactory initialization is invoked recursively " +
          "-- did you call getSerializer() in a serializer constructor?")
    }
    lazyInitialization = true
    val map = ConcurrentHashMap<SerializerKey, JsonSerializer<Any>>()

    map[SerializerKey(String::class.java)] = StringSerializer as JsonSerializer<Any>
    map[SerializerKey(Int::class.java)] = IntSerializer as JsonSerializer<Any>
    map[SerializerKey(Long::class.java)] = LongSerializer as JsonSerializer<Any>
    map[SerializerKey(KClass::class.java)] = KotlinClassSerializer as JsonSerializer<Any>

    // nullable values use Java versions of the primitive type wrappers
    map[SerializerKey(Integer::class.java)] = JavaIntegerSerializer as JsonSerializer<Any>
    map[SerializerKey(java.lang.Long::class.java)] = JavaLongSerializer as JsonSerializer<Any>
    map[SerializerKey(java.lang.Boolean::class.java)] = JavaBooleanSerializer as JsonSerializer<Any>
    map[SerializerKey(Class::class.java)] = JavaClassSerializer as JsonSerializer<Any>

    for (serializer in lazySerializers.value) {
      map[SerializerKey(serializer.appliedTo.javaObjectType)] = serializer
    }

    lazyInitialization = false
    map
  }

  /**
   * Delegates most of the functionality to a given delegate,
   * but accept additional set of types to tread as opaque
   */
  class ExtendedTypeModelConfiguration(
      private val delegate: LocalTypeModelConfiguration,
      private val additionalOpaqueTypes: Set<Type>,
      override val baseTypes: BaseLocalTypes = delegate.baseTypes
  ) : LocalTypeModelConfiguration {

    override fun isExcluded(type: Type): Boolean = delegate.isExcluded(type)

    override fun isOpaque(type: Type): Boolean {
      if (additionalOpaqueTypes.contains(type)) {
        return true
      }
      return delegate.isOpaque(type)
    }
  }

  /** Built-in serializer for atomic value of type [String] */
  object StringSerializer : JsonSerializer<String> {
    override val schema: JsonObject = mapOf("type" to "string").asJsonObject()

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
    override val schema = mapOf("type" to "number", "format" to "int32").asJsonObject()

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

  /** Built-in serializer for atomic value of type [Long] */
  object LongSerializer : JsonSerializer<Long> {
    override val schema = mapOf("type" to "number", "format" to "int64").asJsonObject()

    override fun fromJson(value: JsonValue): Long {
      return when (value.valueType) {
        // provide limited number of type conversions
        JsonValue.ValueType.NUMBER -> (value as JsonNumber).longValue()  // discard fractional part
        JsonValue.ValueType.STRING -> (value as JsonString).string.toLong()
        else -> throw AssertionError("Expected integer, got ${value.valueType} with value $value")
      }
    }

    override fun toJson(obj: Long, generator: JsonGenerator) {
      generator.write(obj)
    }
  }

  /** Built-in serializer for atomic value of type [Boolean] */
  object BooleanSerializer : JsonSerializer<Boolean> {
    override val schema = mapOf("type" to "boolean").asJsonObject()

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
      IntSerializer, Integer::toInt, { Integer(it) })
  object JavaLongSerializer : DelegatingSerializer<java.lang.Long, Long>(
      LongSerializer, java.lang.Long::toLong, { java.lang.Long(it) })
  object JavaBooleanSerializer : DelegatingSerializer<java.lang.Boolean, Boolean>(
      BooleanSerializer, java.lang.Boolean::booleanValue, { java.lang.Boolean(it) })

  object JavaClassSerializer : DelegatingSerializer<Class<*>, String>(
      StringSerializer, Class<*>::getCanonicalName, { Class.forName(it) })
  object KotlinClassSerializer : DelegatingSerializer<KClass<*>, String>(
      StringSerializer, { this.java.canonicalName }, { Class.forName(it).kotlin })

  abstract class DelegatingSerializer<MyType, DelegatedType>(
      private val delegate: JsonSerializer<DelegatedType>,
      private val my2delegate: MyType.() -> DelegatedType = { throw UnsupportedOperationException() },
      private val delegate2my: (DelegatedType) -> MyType = { throw UnsupportedOperationException() }
  ) : JsonSerializer<MyType> {

    override fun fromJson(value: JsonValue): MyType {
      return delegate2my(delegate.fromJson(value))
    }

    override fun toJson(obj: MyType, generator: JsonGenerator) {
      delegate.toJson(my2delegate(obj), generator)
    }

    override val schema: JsonObject
      get() = delegate.schema
  }

  /**
   * Looks up a serializer for a specified class available in a specific context
   * This method is most likely to be used within code calling serialization logic.
   */
  fun <T : Any> getSerializer(clazz: KClass<T>): JsonSerializer<T> {
    return getSerializer(SerializerKey(clazz.java)) as JsonSerializer<T>
  }

  fun getSerializer(typeInfo: LocalTypeInformation): JsonSerializer<Any> {
    return getSerializer(SerializerKey.forType(typeInfo.observedType))
  }

  /**
   * Looks up a serializer for a specified type, potentially parameterized.
   * This method is most likely to be used within generic serialization logic.
   */
  fun getSerializer(key: SerializerKey): JsonSerializer<Any> {
    // for some types like KClass we always force non-typed serializer
    // in order to prevent a large number of serializers to be created
    if (key.typeParameters.isNotEmpty() && mustUseRaw(key.rawType)) {
      return getSerializer(key.asRaw().rawType)
    }

    return objectSerializers.getOrPut(key, {
      createSerializer(key)
    })!!  // this map will never have null values
  }

  private fun mustUseRaw(type: Type): Boolean {
    return type in listOf(Class::class.java, KClass::class.java, TransactionState::class.java)
  }

  fun getSerializer(type: Type): JsonSerializer<Any> {
    return getSerializer(localTypeModel.inspect(type))
  }

  private fun createSerializer(key: SerializerKey): JsonSerializer<Any> {
    val type = localTypeModel.inspect(key.asType())
    return when (type) {
      is LocalTypeInformation.Composable -> ComposableTypeJsonSerializer(type, this)
//      is LocalTypeInformation.Abstract -> AbstractTypeJsonSerializer(type, this)
      is LocalTypeInformation.AnArray -> ListSerializer(type, this)
      is LocalTypeInformation.ACollection -> ListSerializer(type, this)
      is LocalTypeInformation.AnEnum -> EnumSerializer(type) as JsonSerializer<Any>
      is LocalTypeInformation.AMap -> MapSerializer(type, this) as JsonSerializer<Any>
      else -> throw AssertionError("Don't know how to create a serializer for " +
          "${type.observedType} (introspected as ${type.javaClass.canonicalName})")
    }
  }
}

/**
 * A wrapper for a type, which is potentially parameterized, alongside with a specific
 * set of parameters. This structure is used as a key to obtain a serializer from
 * the [SerializationFactory].
 */
data class SerializerKey(
    val rawType: Class<*>,
    val typeParameters: List<Type>) {

  constructor(type: ParameterizedType) : this(type.rawType as Class<*>, type.actualTypeArguments.asList())
  constructor(clazz: Class<*>, vararg typeParameters: Class<*>) : this(clazz, typeParameters.asList())
  constructor(klazz: KClass<*>, vararg typeParameters: KClass<*>) : this(klazz.java, typeParameters.asList().map { it.java })

  fun asRaw() = this.copy(typeParameters = emptyList())

  /**
   * Reconstitutes a parameterised type from the associated raw type and given type parameters
   */
  fun asType() = if (typeParameters.isEmpty()) {
    rawType
  } else {
    ReconstitutedParameterizedType(rawType, rawType.enclosingClass, typeParameters.toTypedArray())
  }

  private data class ReconstitutedParameterizedType(
      val _rawType: Type,
      val _ownerType: Type?,
      val _actualTypeArguments: Array<Type>
  ) : ParameterizedType {
    override fun getRawType() = _rawType
    override fun getOwnerType() = _ownerType
    override fun getActualTypeArguments() = _actualTypeArguments
  }

  companion object {
    fun forType(type: Type): SerializerKey {
      return when (type) {
        is ParameterizedType -> SerializerKey(type)
        is Class<*> -> SerializerKey(type)
        else -> throw SerializationException("Don't know how to find serializer for type $type")
      }
    }
  }
}

/**
 * Note this only supports map of primitive types
 *
 * @throws IllegalArgumentException non-primitive type was encountered
 */
fun Map<String, Any>.asJsonObject(): JsonObject {
  return JsonHome.createObjectBuilder(this).build()
}

/**
 * Note this only supports collections of primitive types.
 *
 * @throws IllegalArgumentException non-primitive type was encountered
 */
fun Collection<Any>.asJsonArray(): JsonArray {
  return JsonHome.createArrayBuilder(this).build()
}

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
 * Base interface for all serializers allowing JVM objects to be read from JSON structures
 * and written to JSON stream. Serializers also can describe the schema of JSON value
 * that it can read and write using a subset of JSON Schema allowed for OpenAPI specifications.
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

  /**
   * Returns JSON Schema structure describing the value type.
   * The output is an object containing 'type' property and other descriptive details.
   *
   * The object may need to be extended by the calling code, e.g. to add 'readOnly' flag
   * for a property of an atomic type
   */
  val schema: JsonObject
}

/**
 * Marker interface aiding in custom-coded serializer discovery.
 *
 * The implementations are expected to have definitions created
 * Note that the implementation must not use [SerializationFactory] in the constructor,
 * because a list of
 */
interface CustomSerializer<T> : JsonSerializer<T> {
  val appliedTo: KClass<*>
}

object JsonHome {
  /** Explicitly bind to a built-in provider to avoid a gamble with ServiceLoader */
  private val provider = JsonProviderImpl()

  fun createObjectBuilder(): JsonObjectBuilder = provider.createObjectBuilder()
  fun createObjectBuilder(map: Map<String, Any?>): JsonObjectBuilder = provider.createObjectBuilder(map)
  fun createObjectBuilder(jsonObject: JsonObject): JsonObjectBuilder = provider.createObjectBuilder(jsonObject)

  fun createArrayBuilder(): JsonArrayBuilder = provider.createArrayBuilder()
  fun createArrayBuilder(collection: Collection<Any?>): JsonArrayBuilder = provider.createArrayBuilder(collection)

  fun createGenerator(writer: StringWriter): JsonGenerator = provider.createGenerator(writer)
  fun createGenerator(writer: PrintWriter): JsonGenerator = provider.createGenerator(writer)

  fun createReader(reader: Reader): JsonReader = provider.createReader(reader)

  fun createValue(value: String) = provider.createValue(value)
}
