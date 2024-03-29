package tech.b180.cordaptor.rest

import io.undertow.server.handlers.form.FormData
import net.corda.core.utilities.base64ToByteArray
import net.corda.serialization.internal.AllWhitelist
import net.corda.serialization.internal.amqp.CachingCustomSerializerRegistry
import net.corda.serialization.internal.amqp.DefaultDescriptorBasedSerializerRegistry
import net.corda.serialization.internal.amqp.WhitelistBasedTypeModelConfiguration
import net.corda.serialization.internal.model.*
import tech.b180.cordaptor.kernel.ModuleAPI
import tech.b180.cordaptor.kernel.loggerFor
import tech.b180.cordaptor.shaded.javax.json.*
import tech.b180.cordaptor.shaded.javax.json.stream.JsonGenerator
import java.lang.reflect.ParameterizedType
import java.lang.reflect.Type
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import kotlin.reflect.KClass
import kotlin.reflect.KTypeParameter
import kotlin.reflect.full.allSupertypes

class SerializationException(
    override val message: String,
    override val cause: Throwable? = null) : Exception()

@Suppress("UNCHECKED_CAST")
class SerializationFactory(
    lazySerializers: Lazy<List<CustomSerializer<Any>>>,
    lazySerializerFactories: Lazy<List<CustomSerializerFactory<Any>>>
) {
  companion object {
    private val logger = loggerFor<SerializationFactory>()

    /**
     * Additional opaque types for serializers defined and registered
     * as part of [objectSerializers] initialization
     */
    private val staticOpaqueTypes = listOf(
        KClass::class.java,
        Class::class.java,
        URL::class.java,
        JsonObject::class.java)
  }

  private var lazyInitialization = false

  private val customSerializers by lazySerializers
  private val customSerializerFactories by lazySerializerFactories

  private val localTypeModel by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
    val customSerializerRegistry = CachingCustomSerializerRegistry(DefaultDescriptorBasedSerializerRegistry())
    val typeModelConfiguration = ExtendedTypeModelConfiguration(
        delegate = WhitelistBasedTypeModelConfiguration(AllWhitelist, customSerializerRegistry),
        // add all types with custom serializers to the opaque list, so that
        // Corda does not flag any transitive introspection problems
        additionalOpaqueTypes = customSerializers.map { it.valueType.localType }.toSet() + staticOpaqueTypes

    )
    ConfigurableLocalTypeModel(typeModelConfiguration)
  }

  private val objectSerializerFactories by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
    customSerializerFactories.map {
      it.rawType to it
    }.toMap()
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
    map[SerializerKey(Double::class.java)] = DoubleSerializer as JsonSerializer<Any>
    map[SerializerKey(Float::class.java)] = FloatSerializer as JsonSerializer<Any>
    map[SerializerKey(Boolean::class.java)] = BooleanSerializer as JsonSerializer<Any>
    map[SerializerKey(Unit::class.java)] = UnitSerializer as JsonSerializer<Any>
    map[SerializerKey(KClass::class.java)] = KotlinClassSerializer as JsonSerializer<Any>

    // nullable values use Java versions of the primitive type wrappers
    map[SerializerKey(Integer::class.java)] = JavaIntegerSerializer as JsonSerializer<Any>
    map[SerializerKey(java.lang.Long::class.java)] = JavaLongSerializer as JsonSerializer<Any>
    map[SerializerKey(java.lang.Boolean::class.java)] = JavaBooleanSerializer as JsonSerializer<Any>
    map[SerializerKey(Class::class.java)] = JavaClassSerializer as JsonSerializer<Any>

    map[SerializerKey(URL::class.java)] = URLSerializer as JsonSerializer<Any>

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
      val b = Json.createObjectBuilder().add("type", jsonType)
      if (jsonFormat != null) {
        b.add("format", jsonFormat)
      }
      schema = b.build()
    }

    override val valueType = SerializerKey.fromSuperclassTypeArgument(PrimitiveTypeSerializer::class, this::class)
    override fun generateSchema(generator: JsonSchemaGenerator) = schema

    abstract fun fromMultiPartFormValue(formValue: FormData.FormValue) : T
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

    override fun fromMultiPartFormValue(formValue: FormData.FormValue): String {
      return when(formValue.value.isNotEmpty()) {
        true -> formValue.value
        false -> ""
      }
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

    override fun fromMultiPartFormValue(formValue: FormData.FormValue): Int {
      return when(formValue.value.isNotEmpty()) {
        true -> Integer.parseInt(formValue.value)
        false -> 0
      }
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

    override fun fromMultiPartFormValue(formValue: FormData.FormValue): Long {
      return when(formValue.value.isNotEmpty()) {
        true -> formValue.value.toLong()
        false -> 0
      }
    }
  }

  /** Built-in serializer for atomic value of type [Double] */
  object DoubleSerializer : PrimitiveTypeSerializer<Double>("number", "double") {
    override fun fromJson(value: JsonValue): Double {
      return when (value.valueType) {
        // provide limited number of type conversions
        JsonValue.ValueType.NUMBER -> (value as JsonNumber).doubleValue()
        JsonValue.ValueType.STRING -> (value as JsonString).string.toDouble()
        else -> throw AssertionError("Expected number, got ${value.valueType} with value $value")
      }
    }

    override fun toJson(obj: Double, generator: JsonGenerator) {
      generator.write(obj)
    }

    override fun fromMultiPartFormValue(formValue: FormData.FormValue): Double {
      return when(formValue.value.isNotEmpty()) {
        true -> formValue.value.toDouble()
        false -> 0.0
      }
    }
  }

  /** Built-in serializer for atomic value of type [Float] */
  object FloatSerializer : PrimitiveTypeSerializer<Float>("number", "float") {
    override fun fromJson(value: JsonValue): Float {
      return when (value.valueType) {
        // provide limited number of type conversions
        JsonValue.ValueType.NUMBER -> (value as JsonNumber).doubleValue().toFloat()
        JsonValue.ValueType.STRING -> (value as JsonString).string.toFloat()
        else -> throw AssertionError("Expected number, got ${value.valueType} with value $value")
      }
    }

    override fun toJson(obj: Float, generator: JsonGenerator) {
      generator.write(obj.toDouble())
    }

    override fun fromMultiPartFormValue(formValue: FormData.FormValue): Float {
      return when(formValue.value.isNotEmpty()) {
        true -> formValue.value.toFloat()
        false -> 0.0.toFloat()
      }
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

    override fun fromMultiPartFormValue(formValue: FormData.FormValue): Boolean {
      return when (formValue.value.isNotEmpty()) {
        true -> formValue.value.toBoolean()
        else -> throw AssertionError("Expected , boolean got ${formValue.value}")
      }
    }
  }

  /** Serializer for Kotlin type [Unit] */
  object UnitSerializer : PrimitiveTypeSerializer<Unit>("null") {
    override fun fromJson(value: JsonValue): Unit {
      return when (value.valueType) {
        JsonValue.ValueType.NULL -> Unit
        else -> throw AssertionError("Expected unit, got ${value.valueType} with value $value")
      }
    }

    override fun toJson(obj: Unit, generator: JsonGenerator) {
      generator.write(obj.toString())
    }

    override fun fromMultiPartFormValue(formValue: FormData.FormValue) {
      return when (formValue.value.isNullOrEmpty()) {
        true -> Unit
        else -> throw AssertionError("Expected unit, got ${formValue.value}")
      }
    }
  }

  // FIXME use url format for the schema
  object URLSerializer : DelegatingSerializer<URL, String>(
      StringSerializer, URL::toString, { URL(it) })

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
    return getSerializer(SerializerKey(typeInfo.typeIdentifier))
  }

  /**
   * Looks up a serializer for a specified type, potentially parameterized.
   * This method is most likely to be used within generic serialization logic.
   */
  fun getSerializer(key: SerializerKey): JsonSerializer<Any> {

    if (key.isParameterized) {
      val factory = objectSerializerFactories[key.rawType]
      if (factory != null) {
        // some parameterized types need special handling depending on the type parameters,
        // which is encapsulated into the serializer factory
        return factory.createSerializer(key)
      }

      if (mustUseRaw(key.rawType)) {
        // for some types like KClass we always force non-typed serializer
        // in order to prevent a large number of serializers to be created
        return getSerializer(key.erased)
      }
    }

    return objectSerializers.getOrPut(key, {
      logger.debug("Creating new serializer for {}", key)
      createSerializer(key)
    })!!  // this map will never have null values
  }

  private fun mustUseRaw(type: Type): Boolean {
    return type in listOf(Class::class.java, KClass::class.java)
  }

  fun getSerializer(type: Type): JsonSerializer<Any> {
    return getSerializer(localTypeModel.inspect(type))
  }

  private fun createSerializer(key: SerializerKey): JsonSerializer<Any> {
    val type = localTypeModel.inspect(key.localType)
    if (logger.isTraceEnabled) {
      // this invokes significant overhead, so needs to be used as as last resort
      logger.trace("Introspected local type information for $key:\n${type.prettyPrint(true)}")
    }

    return when (type) {
      is LocalTypeInformation.Composable -> ComposableTypeJsonSerializer(type, this)
      is LocalTypeInformation.Abstract -> DynamicObjectSerializer(SerializerKey(type.typeIdentifier), this)
      is LocalTypeInformation.AnInterface -> DynamicObjectSerializer(SerializerKey(type.typeIdentifier), this)
      is LocalTypeInformation.AnArray -> ListSerializer(type, this)
      is LocalTypeInformation.ACollection -> ListSerializer(type, this)
      is LocalTypeInformation.AnEnum -> EnumSerializer(type) as JsonSerializer<Any>
      is LocalTypeInformation.AMap -> MapSerializer(type, this) as JsonSerializer<Any>
      is LocalTypeInformation.Top -> DynamicObjectSerializer(SerializerKey(type.typeIdentifier), this)
      is LocalTypeInformation.NonComposable ->
        throw SerializationException("Cannot create a serializer for type $key introspected as non-composable for " +
            "the following reason: ${type.reason}")
      else -> throw SerializationException("Don't know how to create a serializer for type " +
          "$key (introspected as ${type.javaClass.canonicalName})")
    }
  }

  /**
   * Exposes the underlying type introspection facility from Corda.
   * This method is intended to be used when implementing dynamic serializer factories
   * in some specific cases, e.g. [CordaFlowInstructionSerializerFactory]
   */
  fun inspectLocalType(clazz: Class<*>): LocalTypeInformation {
    return localTypeModel.inspect(clazz)
  }
}

/**
 * A wrapper for a type, which is potentially parameterized, alongside with a specific
 * set of parameters. This structure is used as a key to obtain a serializer from
 * the [SerializationFactory].
 *
 * The implementation is heavily reliant on Corda's [TypeIdentifier] hierarchy because it contains
 * necessary logic, but we wrap it into our own class to adapt the API to our needs.
 */
@ModuleAPI(since = "0.1")
data class SerializerKey(val typeIdentifier: TypeIdentifier) {

  constructor(rawType: Class<*>, typeParameters: List<SerializerKey>) : this(
      typeIdentifier = if (typeParameters.isEmpty())
        TypeIdentifier.forClass(rawType)
      else
        TypeIdentifier.Parameterised(rawType.canonicalName,
            rawType.enclosingClass?.let { TypeIdentifier.forClass(it) },
            typeParameters.map { it.typeIdentifier })
  )
  constructor(clazz: Class<*>, vararg typeParameters: Type) : this(clazz, typeParameters.map { forType(it) })
  constructor(klazz: KClass<*>, vararg typeParameters: KClass<*>)
      : this(klazz.java, typeParameters.map { forType(it.java) })


  // e.g. java.util.List<java.lang.String>
  override fun toString() = typeIdentifier.toString()

  /**
   * Reconstitutes a parameterised type from the associated raw type and given type parameters
   */
  val localType: Type get() {
    // for embedded deployment it's important to use classloader which has Cordaptor classes,
    // otherwise a system classloader will be used, which would only have Corda classes
    return typeIdentifier.getLocalType(javaClass.classLoader)
  }

  val erased: SerializerKey get() = SerializerKey(typeIdentifier.erased)

  val rawType: Class<*> get() {
    val l = localType
    return when (l) {
      is Class<*> -> l
      is ParameterizedType -> l.rawType as Class<*>
      else -> TODO()
    }
  }

  val typeParameters: List<SerializerKey> get() =
    when (typeIdentifier) {
      is TypeIdentifier.Parameterised -> typeIdentifier.parameters.map { SerializerKey(it) }
      else -> emptyList()
    }

  val isParameterized: Boolean = typeIdentifier is TypeIdentifier.Parameterised

  companion object {
    fun forType(type: Type, resolutionContext: Type? = null): SerializerKey {
      return SerializerKey(TypeIdentifier.forGenericType(type, resolutionContext ?: type))
    }

    /**
     * Determines value type for this serializer by analysing actual type parameter passed to a subclass.
     * Only the first type argument of the given base class classifier is analysed.
     */
    fun <B: Any, S: Any> fromSuperclassTypeArgument(
        baseClass: KClass<B>, subclass: KClass<S>, argumentIndex: Int = 0): SerializerKey {

      val baseType = subclass.allSupertypes.find { it.classifier == baseClass }
          ?: throw SerializationException("Cannot find ${baseClass.simpleName} among supertypes of $subclass")

      if (baseType.arguments.size <= argumentIndex) {
        throw SerializationException("Base type ${baseClass.simpleName} " +
            "does not have necessary number of arguments: ${argumentIndex + 1}")
      }

      val argumentType = baseType.arguments[argumentIndex].type
          ?: throw SerializationException("First type argument of $baseType is not a valid type")

      val argumentClassifier = argumentType.classifier
          ?: throw SerializationException("Argument $argumentType does not have a classifier")

      val argumentRawClass = when (argumentClassifier) {
        is KClass<*> -> argumentClassifier
        is KTypeParameter -> {
          if (argumentClassifier.upperBounds.size != 1) {
            throw SerializationException(
                "Type parameter $argumentType has ambiguous upper bound: ${argumentClassifier.upperBounds}")
          }
          argumentClassifier.upperBounds[0].classifier as? KClass<*>
              ?: throw SerializationException("Type parameter $argumentType does not have a valid classifier")
        }
        else -> throw SerializationException(
            "Classifier type for argument $argumentType is not recognized: $argumentClassifier")
      }

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
@ModuleAPI(since = "0.1")
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
@ModuleAPI(since = "0.1")
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
@ModuleAPI(since = "0.1")
interface CustomSerializer<T> : JsonSerializer<T>

interface MultiPartFormDataSerializer<T>: CustomSerializer<T>{
  fun fromMultiPartFormData(data: FormData) : T
}

interface MultiPartFormValueSerializer<T>: CustomSerializer<T>{
  fun fromMultiPartFormValue(formValue: FormData.FormValue) : T

  fun generateSchemaForMultiPart(generator: JsonSchemaGenerator): JsonObject
}

interface MultiPartFormTransformValueSerializer<T, R>: CustomSerializer<T>{
  fun transformValue(formValue: FormData.FormValue) : R
}

/**
 * Alternative to [CustomSerializer] when custom serializers need to be created for
 * parameterized types where parameters are determined at runtime and the schema
 * may be dependent on the actual parameters.
 */
@ModuleAPI(since = "0.1")
interface CustomSerializerFactory<T: Any> {
  val rawType: Class<*>

  fun createSerializer(key: SerializerKey): JsonSerializer<T> {
    if (!rawType.isAssignableFrom(key.rawType)) {
      throw SerializationException("Parameter's raw type ${key.rawType.canonicalName} " +
          "is not compatible with factory's ${rawType.canonicalName}")
    }
    return doCreateSerializer(key)
  }

  fun doCreateSerializer(key: SerializerKey): JsonSerializer<T>
}