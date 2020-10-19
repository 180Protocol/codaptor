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
import tech.b180.cordaptor.kernel.loggerFor
import java.io.PrintWriter
import java.io.Reader
import java.io.StringWriter
import java.lang.reflect.ParameterizedType
import java.lang.reflect.Type
import java.lang.reflect.TypeVariable
import java.lang.reflect.WildcardType
import java.util.concurrent.ConcurrentHashMap
import javax.json.*
import javax.json.stream.JsonGenerator
import kotlin.reflect.KClass
import kotlin.reflect.full.allSupertypes

class SerializationException(
    override val message: String,
    override val cause: Throwable? = null) : Exception()

@Suppress("UNCHECKED_CAST")
class SerializationFactory(
    private val lazySerializers: Lazy<List<CustomSerializer<Any>>>
) {
  companion object {
    private val logger = loggerFor<SerializationFactory>()
  }

  private var lazyInitialization = false

  private val customSerializers by lazySerializers

  private val localTypeModel by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
    val customSerializerRegistry = CachingCustomSerializerRegistry(DefaultDescriptorBasedSerializerRegistry())
    val typeModelConfiguration = ExtendedTypeModelConfiguration(
        delegate = WhitelistBasedTypeModelConfiguration(AllWhitelist, customSerializerRegistry),
        // add all types with custom serializers to the opaque list, so that
        // Corda does not flag any transitive introspection problems
        additionalOpaqueTypes = customSerializers.map { it.valueType.asType() }.toSet()
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
      map[serializer.valueType] = serializer
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

  /** Base implementation taking care of some boilerplate */
  abstract class PrimitiveTypeSerializer<T: Any>(
      jsonType: String,
      jsonFormat: String? = null
  ) : JsonSerializer<T> {

    private val schema: JsonObject

    init {
      val b = JsonHome.createObjectBuilder().add("type", jsonType)
      if (jsonFormat != null) {
        b.add("format", jsonFormat)
      }
      schema = b.build()
    }

    override val valueType = SerializerKey.fromSuperclassTypeArgument(PrimitiveTypeSerializer::class, this::class)
    override fun generateSchema(generator: JsonSchemaGenerator) = schema
  }

  /** Built-in serializer for atomic value of type [String] */
  object StringSerializer : PrimitiveTypeSerializer<String>("string") {
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
  object IntSerializer : PrimitiveTypeSerializer<Int>("number", "int32") {
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
  object LongSerializer : PrimitiveTypeSerializer<Long>("number", "int64") {
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
  object BooleanSerializer : PrimitiveTypeSerializer<Boolean>("boolean") {
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
  @Suppress("PLATFORM_CLASS_MAPPED_TO_KOTLIN", "RemoveRedundantQualifierName")
  object JavaIntegerSerializer : DelegatingSerializer<java.lang.Integer, Int>(
      IntSerializer, Integer::toInt, { Integer(it) })
  @Suppress("PLATFORM_CLASS_MAPPED_TO_KOTLIN", "RemoveRedundantQualifierName")
  object JavaLongSerializer : DelegatingSerializer<java.lang.Long, Long>(
      LongSerializer, java.lang.Long::toLong, { java.lang.Long(it) })
  @Suppress("PLATFORM_CLASS_MAPPED_TO_KOTLIN", "RemoveRedundantQualifierName")
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

    override val valueType = SerializerKey.fromSuperclassTypeArgument(DelegatingSerializer::class, this::class)

    override fun generateSchema(generator: JsonSchemaGenerator) = delegate.generateSchema(generator)
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
      logger.debug("Creating new serializer for {}", key)
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
      is LocalTypeInformation.Abstract -> DynamicObjectSerializer(SerializerKey.forType(type.observedType), this)
      is LocalTypeInformation.AnInterface -> DynamicObjectSerializer(SerializerKey.forType(type.observedType), this)
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
 * Shorthand for creating a reconstituted parameterised type using
 * a raw type and a collection of type parameters
 */
fun <T: Any> KClass<T>.asParameterizedType(vararg params: KClass<*>): Type =
    SerializerKey(this.java, params.map { SerializerKey.forType(it.java) }).asType()

/**
 * A wrapper for a type, which is potentially parameterized, alongside with a specific
 * set of parameters. This structure is used as a key to obtain a serializer from
 * the [SerializationFactory].
 */
data class SerializerKey(
    val rawType: Class<*>,
    val typeParameters: List<SerializerKey>) {

  constructor(clazz: Class<*>, vararg typeParameters: Type) : this(clazz, typeParameters.map { forType(it) })
  constructor(klazz: KClass<*>, vararg typeParameters: KClass<*>)
      : this(klazz.java, typeParameters.map { forType(it.java) })

  fun asRaw() = this.copy(typeParameters = emptyList())

  // e.g. java.util.List<java.lang.String>
  override fun toString(): String = if (typeParameters.isEmpty())
    rawType.canonicalName
  else
      """${rawType.canonicalName}<${typeParameters.joinToString(",")}>"""

  /**
   * Reconstitutes a parameterised type from the associated raw type and given type parameters
   */
  fun asType(): Type = if (typeParameters.isEmpty()) {
    rawType
  } else {
    ReconstitutedParameterizedType(this)
  }

  private data class ReconstitutedParameterizedType(
      val key: SerializerKey,
      val _rawType: Type = key.rawType,
      val _ownerType: Type? = key.rawType.enclosingClass,
      val _actualTypeArguments: Array<Type> = key.typeParameters.map { it.asType() }.toTypedArray()
  ) : ParameterizedType {
    override fun getRawType() = _rawType
    override fun getOwnerType() = _ownerType
    override fun getActualTypeArguments() = _actualTypeArguments
  }

  companion object {
    fun forType(type: Type): SerializerKey {
      return when (type) {
        is ReconstitutedParameterizedType -> type.key // shortcut for our own representation
        is TypeVariable<*> -> if (type.bounds.size == 1)
          forType(type.bounds[0])
        else
          throw AssertionError("Cannot differentiate between type bounds in $type")
        is ParameterizedType -> forParameterizedType(type)
        is Class<*> -> SerializerKey(type)
        else -> throw SerializationException("Don't know how to deconstruct type ${type::class.simpleName}")
      }
    }

    private fun forParameterizedType(type: ParameterizedType): SerializerKey {
      val args = type.actualTypeArguments.mapNotNull {
        when (it) {
          is ReconstitutedParameterizedType -> it.key // shortcut for our own representation
          is Class<*> -> SerializerKey(it)
          is ParameterizedType -> forType(it)
          is WildcardType -> null
          else -> null
        }
      }
      if (args.isNotEmpty() && args.size != type.actualTypeArguments.size) {
        throw SerializationException("Type $type has a mixture of wildcard and actual arguments")
      }
      return SerializerKey(type.rawType as Class<*>, args)
    }

    /**
     * Determines value type for this serializer by analysing actual type parameter passed to a subclass.
     * Only the first type argument of the given base class classifier is analysed.
     */
    fun <B: Any, S: Any> fromSuperclassTypeArgument(baseClass: KClass<B>, subclass: KClass<S>): SerializerKey {
      val baseType = subclass.allSupertypes.find { it.classifier == baseClass }
          ?: throw AssertionError("Cannot find ${baseClass.simpleName} among supertypes of $subclass")

      if (baseType.arguments.isEmpty()) {
        throw AssertionError("Base type ${baseClass.simpleName} is not parameterized")
      }

      val argumentType = baseType.arguments[0].type
          ?: throw AssertionError("First type argument of $baseType is not a valid type")

      val argumentRawClass = argumentType.classifier as? KClass<*>
          ?: throw AssertionError("Type $argumentType does not correspond to a valid classifier")

      // Kotlin's supertypes don't make Java equivalent available, so we cannot simply pass it to forType(Class)
      val args = argumentType.arguments.mapNotNull {
        when {
          (it.type?.classifier is KClass<*>) -> SerializerKey(it.javaClass)
          (it.type == null) -> null
          else -> throw SerializationException("Don't know how to deconstruct argument $it of type $argumentType")
        }
      }
      if (args.isNotEmpty() && args.size != argumentType.arguments.size) {
        throw SerializationException("Type $argumentType has a mixture of wildcard and actual arguments")
      }
      return SerializerKey(argumentRawClass.java, args)
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
   * Returns value type information that this instance is able to read and/or write.
   */
  val valueType: SerializerKey

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
   * Creates a JSON object describing the structure of the value type according to JSON Schema specification.
   * The output is an object containing 'type' property and other descriptive details.
   *
   * Implementations normally are not expected to invoke this method directly for nested types
   * such as object properties or array elements. Instead, they need to rely on [JsonSchemaGenerator]
   * to create such nested schemas. This will make sure that commonly used and important types
   * are declared in the components section of JSON Schema and correctly referenced.
   *
   * The object may need to be extended by the calling code, e.g. to add 'readOnly' flag
   * for a property of an atomic type
   */
  fun generateSchema(generator: JsonSchemaGenerator): JsonObject
}

/**
 * Entry point for generating a JSON schema for an value type identified by the given key.
 * Depending on the circumstances this may output full object or a reference to relevant schema
 * defined in another part of the document.
 */
interface JsonSchemaGenerator {

  /**
   * @see JsonSerializer.generateSchema
   */
  fun generateSchema(key: SerializerKey): JsonObject
}

/**
 * Marker interface aiding in custom-coded serializer discovery.
 *
 * The implementations are expected to have definitions created
 * Note that the implementation must not use [SerializationFactory] in the constructor,
 * because a list of
 */
interface CustomSerializer<T> : JsonSerializer<T> {
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
