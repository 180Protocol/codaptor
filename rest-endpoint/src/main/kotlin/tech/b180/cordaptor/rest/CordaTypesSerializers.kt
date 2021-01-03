package tech.b180.cordaptor.rest

import net.corda.core.contracts.*
import net.corda.core.crypto.SecureHash
import net.corda.core.crypto.TransactionSignature
import net.corda.core.flows.FlowLogic
import net.corda.core.identity.AbstractParty
import net.corda.core.identity.CordaX500Name
import net.corda.core.identity.Party
import net.corda.core.identity.PartyAndCertificate
import net.corda.core.node.NodeInfo
import net.corda.core.transactions.CoreTransaction
import net.corda.core.transactions.SignedTransaction
import net.corda.core.transactions.WireTransaction
import net.corda.core.utilities.OpaqueBytes
import net.corda.core.utilities.toBase58
import net.corda.core.utilities.toSHA256Bytes
import net.corda.serialization.internal.model.LocalTypeInformation
import tech.b180.cordaptor.corda.CordaFlowInstruction
import tech.b180.cordaptor.corda.CordaNodeState
import tech.b180.cordaptor.shaded.javax.json.JsonNumber
import tech.b180.cordaptor.shaded.javax.json.JsonObject
import tech.b180.cordaptor.shaded.javax.json.JsonString
import tech.b180.cordaptor.shaded.javax.json.JsonValue
import tech.b180.cordaptor.shaded.javax.json.stream.JsonGenerator
import java.math.BigDecimal
import java.math.RoundingMode
import java.security.PublicKey
import java.security.cert.X509Certificate
import java.time.Duration
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.*
import kotlin.reflect.KClass

/**
 * Builds a serializer for a specific parameterized type based on [Amount] class.
 *
 * The rationale for using a factory is that we want to have separate amount types
 * in JSON schema based on the underlying token, for which we need to pass
 * explicit value type into [CustomStructuredObjectSerializer] constructor.
 *
 * We use display value from the Amount when serializing to JSON, as it's more intuitive
 * for the API users.
 */
class CordaAmountSerializerFactory(private val factory: SerializationFactory) : CustomSerializerFactory<Amount<*>> {

  override val rawType = Amount::class.java

  override fun doCreateSerializer(key: SerializerKey): JsonSerializer<Amount<*>> {
    return object : CustomStructuredObjectSerializer<Amount<*>>(factory, explicitValueType = key) {
      override val properties: Map<String, ObjectProperty>
        get() = mapOf(
            "quantity" to SyntheticObjectProperty(
                valueType = SerializerKey.forType(BigDecimal::class.java),
                isMandatory = true,
                accessor = { (it as Amount<*>).toDecimal() }
            ),
            "of" to SyntheticObjectProperty(
                valueType = key.typeParameters[0],
                isMandatory = true,
                accessor = { (it as Amount<*>).token }
            )
        )

      override fun initializeInstance(values: Map<String, Any?>): Amount<*> {
        val quantity = (values["quantity"] as? BigDecimal)
            ?: throw AssertionError("Unexpected value in mandatory field 'quantity': ${values["quantity"]}")

        val token = values["of"] ?: throw AssertionError("Missing value in mandatory field 'of'")

        return Amount.fromDecimal(displayQuantity = quantity, token = token, rounding = RoundingMode.UNNECESSARY)
      }
    }
  }
}

/**
 * Serializer for [BigDecimal], which is a standard Kotlin type, but needs to
 * have a custom serializer in order to be treated as opaque by Corda introspection
 * to avoid non-composable object exceptions.
 */
class BigDecimalSerializer
  : CustomSerializer<BigDecimal>, SerializationFactory.PrimitiveTypeSerializer<BigDecimal>("number") {

  override fun fromJson(value: JsonValue): BigDecimal {
    return when (value.valueType) {
      // provide limited number of type conversions
      JsonValue.ValueType.NUMBER -> (value as JsonNumber).bigDecimalValue()
      JsonValue.ValueType.STRING -> (value as JsonString).string.toBigDecimal()
      else -> throw AssertionError("Expected number, got ${value.valueType} with value $value")
    }
  }

  override fun toJson(obj: BigDecimal, generator: JsonGenerator) {
    generator.write(obj)
  }
}

/**
 * Serializes a [Currency] as a JSON string representing its ISO code.
 * Mainly used as part of the implementation for serializer of [Amount], but
 * currencies may also be specified on contract states.
 */
class CurrencySerializer
  : CustomSerializer<Currency>,
    StandaloneTypeSerializer,
    SerializationFactory.DelegatingSerializer<Currency, String>(
        delegate = SerializationFactory.StringSerializer,
        my2delegate = Currency::toString,
        delegate2my = { Currency.getInstance(it) }
    ) {

  override val schemaTypeName = "ISOCurrencyCode"
}

/**
 * Serializer for [CordaX500Name] converting to/from a string value.
 */
class CordaX500NameSerializer : CustomSerializer<CordaX500Name>, StandaloneTypeSerializer,
    SerializationFactory.DelegatingSerializer<CordaX500Name, String>(
    delegate = SerializationFactory.StringSerializer,
    delegate2my = { CordaX500Name.parse(it) },
    my2delegate = CordaX500Name::toString
) {
  override val schemaTypeName: String
    get() = "CordaX500Name"
}

/**
 * Serializer for [SecureHash] converting to/from a string value.
 */
class CordaSecureHashSerializer : CustomSerializer<SecureHash>, StandaloneTypeSerializer,
    SerializationFactory.DelegatingSerializer<SecureHash, String>(
    delegate = SerializationFactory.StringSerializer,
    delegate2my = { SecureHash.parse(it) },
    my2delegate = SecureHash::toString
) {
  override fun generateSchema(generator: JsonSchemaGenerator): JsonObject {
    return mapOf(
        "type" to "string",
        "minLength" to 64,
        "maxLength" to 64,
        "pattern" to "^[A-Z0-9]{64}"
    ).asJsonObject()
  }

  override val schemaTypeName: String
    get() = "CordaSecureHash"
}

/**
 * Serializer for a [UUID] converting to/from a string value.
 *
 * Technically it is not a Corda class, but it is commonly used in Corda API.
 */
class CordaUUIDSerializer : CustomSerializer<UUID>,
    SerializationFactory.DelegatingSerializer<UUID, String>(
    delegate = SerializationFactory.StringSerializer,
    my2delegate = UUID::toString,
    delegate2my = UUID::fromString
) {
  override fun generateSchema(generator: JsonSchemaGenerator): JsonObject {
    return mapOf(
        "type" to "string",
        "format" to "uuid"
    ).asJsonObject()
  }
}

/**
 * Serializer for a [OpaqueBytes] converting to/from a string value containing underlying bytes in base64 encoding.
 */
class CordaOpaqueBytesSerializer : CustomSerializer<OpaqueBytes>,
    SerializationFactory.DelegatingSerializer<OpaqueBytes, String>(
        delegate = SerializationFactory.StringSerializer,
        my2delegate = {
          Base64.getEncoder().encodeToString(bytes)
        },
        delegate2my = {
          OpaqueBytes(Base64.getDecoder().decode(it))
        }
    ) {
  override fun generateSchema(generator: JsonSchemaGenerator): JsonObject {
    return mapOf(
        "type" to "string",
        "format" to "base64"
    ).asJsonObject()
  }
}

/**
 * Serializer for an [Instant] representing it as a JSON string value formatted as an ISO-8601 timestamp.
 *
 * @see DateTimeFormatter.ISO_INSTANT
 */
class JavaInstantSerializer : CustomSerializer<Instant>,
    SerializationFactory.DelegatingSerializer<Instant, String>(
        delegate = SerializationFactory.StringSerializer,
        my2delegate = { DateTimeFormatter.ISO_INSTANT.format(this) },
        delegate2my = { Instant.parse(it) }
    ) {

  override fun generateSchema(generator: JsonSchemaGenerator): JsonObject {
    return mapOf(
        "type" to "string",
        "format" to "date-time"
    ).asJsonObject()
  }
}

/**
 * Serializer for an [Duration] representing it as a JSON string value formatted as an ISO-8601 timestamp.
 *
 * @see DateTimeFormatter.ISO_INSTANT
 */
class JavaDurationSerializer : CustomSerializer<Duration>,
        SerializationFactory.DelegatingSerializer<Duration, String>(
                delegate = SerializationFactory.StringSerializer,
                my2delegate = { this.toString() },
                delegate2my = { Duration.parse(it) }
        ) {

  override fun generateSchema(generator: JsonSchemaGenerator): JsonObject {
    return mapOf(
            "type" to "string",
            "format" to "date-time"
    ).asJsonObject()
  }
}


/**
 * Serializer for an [AbstractParty] representing it as a JSON object containing its X.500 name.
 *
 * It supports both anonymous and well-known parties. If a party is anonymous,
 * but it's identity is known to the underlying Corda node, it's name will be written to JSON,
 * otherwise only its owning key hash will be written.
 *
 * When reading parties from JSON, it only supports well-known parties identified by X.500 name,
 * for which a resolution will be attempted.
 */
class CordaAbstractPartySerializer(
    factory: SerializationFactory,
    private val nodeState: CordaNodeState
) : CustomStructuredObjectSerializer<AbstractParty>(factory), StandaloneTypeSerializer {

  override val schemaTypeName: String
    get() = "CordaParty"

  @Suppress("UNCHECKED_CAST")
  override val properties: Map<String, ObjectProperty> = mapOf(
      "owningKey" to KotlinObjectProperty(AbstractParty::owningKey, isMandatory = false),
      "name" to SyntheticObjectProperty(SerializerKey.forType(CordaX500Name::class.java),
          accessor = makePartyNameAccessor(nodeState) as ObjectPropertyValueAccessor)
  )

  override fun initializeInstance(values: Map<String, Any?>): Party {
    val nameValue = values["name"]
        ?: throw SerializationException("Corda parties can only be resolved if an X.500 name is provided")

    assert(nameValue is CordaX500Name) { "Expected X500 name, got $nameValue" }

    val name = nameValue as CordaX500Name

    return nodeState.wellKnownPartyFromX500Name(name)
        ?: throw SerializationException("Party with name $name is not known")
  }

  companion object {
    fun makePartyNameAccessor(nodeState: CordaNodeState) =
        { party: AbstractParty -> nodeState.partyFromKey(party.owningKey)?.name }
  }
}

/**
 * Serializer for a [Party] delegating to [CordaAbstractPartySerializer].
 *
 * The rationale is that sometimes CorDapp developers disallow use of anonymous parties
 * by specifying [Party] type explicitly.
 */
class CordaPartySerializer(
    abstractPartySerializer: CordaAbstractPartySerializer
) : CustomSerializer<Party>,
    SerializationFactory.DelegatingSerializer<Party, AbstractParty>(
    delegate = abstractPartySerializer,
    my2delegate = { this },
    delegate2my = { it as? Party ?: throw AssertionError("Only resolved parties can be received") }
), StandaloneTypeSerializer {

  // this is deliberately name-clashing with CordaAbstractPartySerializer, so because the schemas are identical
  override val schemaTypeName: String
    get() = "CordaParty"
}

/**
 * Serializer for a [SignedTransaction] representing it as a JSON object.
 * There is no support for restoring instances of [SignedTransaction] from JSON structures.
 */
class CordaSignedTransactionSerializer(
    factory: SerializationFactory
) : CustomStructuredObjectSerializer<SignedTransaction>(factory, deserialize = false) {

  override val properties: Map<String, ObjectProperty> = mapOf(
      "id" to KotlinObjectProperty(SignedTransaction::id),
      "content" to KotlinObjectProperty(SignedTransaction::coreTransaction),
      "sigs" to KotlinObjectProperty(SignedTransaction::sigs)
  )
}

/**
 * Serializer for a [TransactionSignature] representing it as a JSON object.
 * This object is most commonly serialized as part of a [SignedTransaction].
 * There is no support for restoring instances of [TransactionSignature] from JSON structures.
 */
class CordaTransactionSignatureSerializer(
    factory: SerializationFactory
) : CustomStructuredObjectSerializer<TransactionSignature>(factory, deserialize = false) {

  override val properties: Map<String, ObjectProperty> = mapOf(
      "by" to KotlinObjectProperty(TransactionSignature::by),
      "metadata" to KotlinObjectProperty(TransactionSignature::signatureMetadata, deserialize = false)
  )
}

/**
 * Serializer for a [PartyAndCertificate] representing it as a JSON object.
 * This object is most commonly serialized as part of a [NodeInfo].
 * There is no support for restoring instances of [PartyAndCertificate] from JSON structures.
 *
 * FIXME implement serialization logic for instances of [X509Certificate] abstract class
 */
class CordaPartyAndCertificateSerializer(factory: SerializationFactory)
  : CustomStructuredObjectSerializer<PartyAndCertificate>(factory, deserialize = false) {

  override val properties = mapOf(
      "party" to KotlinObjectProperty(PartyAndCertificate::party)
  )
}

/**
 * Serializer for known subclasses of [CoreTransaction] able to represent them as JSON objects.
 * This object is most commonly serialized as part of a [SignedTransaction].
 * There is no support for restoring instances of [CoreTransaction] from JSON structures.
 */
class CordaCoreTransactionSerializer(factory: SerializationFactory)
  : CustomAbstractClassSerializer<CoreTransaction>(factory, deserialize = false) {

  // FIXME add support for notary change and contract upgrade transactions
  override val subclassesMap = mapOf(
      "wireTransaction" to WireTransaction::class
  )
}

/**
 * Serializer for a [WireTransaction] representing it as a JSON object.
 * This object is most commonly serialized as part of a [SignedTransaction].
 * There is no support for restoring instances of [WireTransaction] from JSON structures.
 */
class CordaWireTransactionSerializer(factory: SerializationFactory)
  : CustomStructuredObjectSerializer<WireTransaction>(factory, deserialize = false) {

  override val properties = mapOf(
      "id" to KotlinObjectProperty(WireTransaction::id),
      "inputs" to KotlinObjectProperty(WireTransaction::inputs),
      "outputs" to KotlinObjectProperty(WireTransaction::outputs),
      "commands" to KotlinObjectProperty(WireTransaction::commands),
      "references" to KotlinObjectProperty(WireTransaction::references),
      "notary" to KotlinObjectProperty(WireTransaction::notary),
      "timeWindow" to KotlinObjectProperty(WireTransaction::timeWindow),
      "attachments" to KotlinObjectProperty(WireTransaction::attachments),
      "networkParametersHash" to KotlinObjectProperty(WireTransaction::networkParametersHash)
  )
}

/**
 * Serializer for a [PublicKey] representing it as a JSON object containing a base58-encoded hash of it.
 * This object is most commonly serialized as part of a [SignedTransaction].
 *
 * There is no support for restoring instances of [PublicKey] from JSON.
 */
class CordaPublicKeySerializer(
    factory: SerializationFactory
) : CustomStructuredObjectSerializer<PublicKey>(factory, deserialize = false) {

  override val properties = mapOf(
      "hash" to SyntheticObjectProperty(valueType = SerializerKey(String::class),
          deserialize = false, isMandatory = true, accessor = makePublicKeyHashAccessor())
  )

  companion object {
    @Suppress("UNCHECKED_CAST")
    fun makePublicKeyHashAccessor() =
        { key: PublicKey -> key.toSHA256Bytes().toBase58() } as ObjectPropertyValueAccessor
  }
}

/**
 * Builds a serializer for a specific parameterized type based on [CordaFlowInstruction] class.
 * The purpose is to make it appear in JSON Schema as an instance of the underlying [FlowLogic] class,
 * whereby class name is implicit, and constructor-bound properties are represented inline.
 *
 * This mimicry is required to offer more user-friendly JSON Schema when initiating Corda flows without
 * actually instantiating respective subclass of [FlowLogic] where it is not required, e.g. when initiating
 * a flow via Corda RPC.
 */
class CordaFlowInstructionSerializerFactory(
    private val factory: SerializationFactory
) : CustomSerializerFactory<CordaFlowInstruction<*>> {

  companion object {
    const val OPTIONS_PROPERTY_NAME = "options"
  }

  override val rawType = CordaFlowInstruction::class.java

  override fun doCreateSerializer(key: SerializerKey): JsonSerializer<CordaFlowInstruction<*>> {
    // flow instructions are not meant to be sent to client, so explicitly forbidding serialization
    return object : CustomStructuredObjectSerializer<CordaFlowInstruction<*>>(
        factory, serialize = false, explicitValueType = key
    ) {
      private val flowClass: Class<*> = valueType.typeParameters[0].rawType

      init {
        if (!FlowLogic::class.java.isAssignableFrom((flowClass))) {
          throw SerializationException("The first parameter of $key type is not " +
              "recognized as a Corda flow class: ${flowClass.canonicalName}")
        }
      }

      override val properties: Map<String, ObjectProperty>
        get() {
          val typeInfo = factory.inspectLocalType(flowClass)
          val constructor = (typeInfo as? LocalTypeInformation.Composable)?.constructor
              ?: throw SerializationException("Flow type identified as $key was not introspected as composable.\n" +
                  "Introspection details: ${typeInfo.prettyPrint()})")

          // expose trackProgress flag explicitly among the properties
          // FIXME watch out for property name clashes
          val properties: MutableList<Pair<String, ObjectProperty>> = mutableListOf(OPTIONS_PROPERTY_NAME
              to KotlinObjectProperty(property = CordaFlowInstruction<*>::options))

          constructor.parameters.mapTo(properties) {
            val prop = typeInfo.properties[it.name]
                ?: throw SerializationException("Could not find property ${it.name} for type type $key, " +
                    "despite it being referenced in constructor parameters")

            it.name to ComposableTypeJsonSerializer.createIntrospectedProperty(it.name, prop)
          }

          return properties.toMap()
        }

      override fun initializeInstance(values: Map<String, Any?>): CordaFlowInstruction<*> {
        val options = values[OPTIONS_PROPERTY_NAME] as? CordaFlowInstruction.Options

        @Suppress("UNCHECKED_CAST")
        val flowClass = flowClass.kotlin as KClass<FlowLogic<Any>>

        @Suppress("UNCHECKED_CAST")
        val arguments = values.filter { it.key != OPTIONS_PROPERTY_NAME && it.value != null } as Map<String, Any>

        return CordaFlowInstruction(flowClass, arguments, options)
      }
    }
  }
}

/**
 * Serializer for various subtypes of [AttachmentConstraint], some of which are singletons.
 */
class CordaAttachmentConstraintSerializer(factory: SerializationFactory)
  : CustomAbstractClassSerializer<AttachmentConstraint>(factory, deserialize = false) {

  override val subclassesMap = mapOf(
      "alwaysAccept" to AlwaysAcceptAttachmentConstraint::class,
      "hash" to HashAttachmentConstraint::class,
      "signature" to SignatureAttachmentConstraint::class,
      "whitelistedByZone" to WhitelistedByZoneAttachmentConstraint::class
  )
}

/**
 * Serializer for various subtypes of [TimeWindow]
 */
class CordaTimeWindowSerializer(factory: SerializationFactory)
  : CustomAbstractClassSerializer<TimeWindow>(factory, deserialize = false) {

  @Suppress("UNCHECKED_CAST")
  override val subclassesMap = mapOf(
      // concrete subclasses are unhelpfully marked as private,
      // so we use reflection to get references to them
      "from" to Class.forName("${TimeWindow::class.qualifiedName}\$From").kotlin as KClass<out TimeWindow>,
      "until" to Class.forName("${TimeWindow::class.qualifiedName}\$Until").kotlin as KClass<out TimeWindow>,
      "between" to Class.forName("${TimeWindow::class.qualifiedName}\$Between").kotlin as KClass<out TimeWindow>
  )
}