package tech.b180.cordaptor.rest

import net.corda.core.contracts.ContractState
import net.corda.core.contracts.TransactionState
import net.corda.core.crypto.SecureHash
import net.corda.core.crypto.TransactionSignature
import net.corda.core.identity.CordaX500Name
import net.corda.core.identity.Party
import net.corda.core.identity.PartyAndCertificate
import net.corda.core.node.NodeInfo
import net.corda.core.transactions.CoreTransaction
import net.corda.core.transactions.SignedTransaction
import net.corda.core.transactions.WireTransaction
import tech.b180.cordaptor.corda.CordaNodeState
import java.security.PublicKey
import java.security.cert.X509Certificate
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.*
import javax.json.JsonObject

/**
 * Serializer for [CordaX500Name] converting to/from a string value.
 */
class CordaX500NameSerializer : CustomSerializer<CordaX500Name>,
    SerializationFactory.DelegatingSerializer<CordaX500Name, String>(
    delegate = SerializationFactory.StringSerializer,
    delegate2my = { CordaX500Name.parse(it) },
    my2delegate = CordaX500Name::toString
)

/**
 * Serializer for [SecureHash] converting to/from a string value.
 */
class CordaSecureHashSerializer : CustomSerializer<SecureHash>,
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
 * Serializer for a [Party] representing it as a JSON object containing its X.500 name.
 *
 * When reading from JSON, it attempts to resolve the party by calling
 * [CordaNodeState.wellKnownPartyFromX500Name] method
 */
class CordaPartySerializer(
    factory: SerializationFactory,
    private val nodeState: CordaNodeState
) : CustomStructuredObjectSerializer<Party>(factory) {

  override val properties: Map<String, ObjectProperty> = mapOf(
      "name" to KotlinObjectProperty(Party::name, isMandatory = true)
  )

  override fun initializeInstance(values: Map<String, Any?>): Party {
    val nameValue = values["name"]
    assert(nameValue is CordaX500Name) { "Expected X500 name, got $nameValue" }

    val name = nameValue as CordaX500Name

    return nodeState.wellKnownPartyFromX500Name(name)
        ?: throw SerializationException("Party with name $name is not known")
  }
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
  override val subclassesMap: Map<String, SerializerKey> = mapOf(
      "wireTransaction" to SerializerKey(WireTransaction::class)
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
      "inputs" to KotlinObjectProperty(WireTransaction::inputs),
      "outputs" to KotlinObjectProperty(WireTransaction::outputs),
      "inputs" to KotlinObjectProperty(WireTransaction::commands),
      "references" to KotlinObjectProperty(WireTransaction::references)
  )
}

/**
 * Serializer for a [PublicKey] representing it as a JSON object.
 * This object is most commonly serialized as part of a [SignedTransaction].
 * There is no support for restoring instances of [WireTransaction] from JSON structures.
 */
class CordaPublicKeySerializer(
    factory: SerializationFactory,
    nodeState: CordaNodeState
) : CustomStructuredObjectSerializer<PublicKey>(factory, deserialize = false) {

  override val properties = mapOf(
      "fingerprint" to SyntheticObjectProperty(valueType = String::class.java,
          deserialize = false, isMandatory = false, accessor = { "not implemented" }),
      "knownParty" to SyntheticObjectProperty(valueType = Party::class.java,
          deserialize = false, isMandatory = false, accessor = makeKnownPartyAccessor(nodeState))
  )

  companion object {
    @Suppress("UNCHECKED_CAST")
    fun makeKnownPartyAccessor(nodeState: CordaNodeState) =
        { key: PublicKey -> nodeState.partyFromKey(key) } as ObjectPropertyValueAccessor
  }
}

/**
 * Serializer for a [TransactionState] representing it as a JSON object.
 * This object is most commonly serialized as part of a [SignedTransaction].
 * There is no support for restoring instances of [TransactionState] from JSON structures.
 */
class CordaTransactionStateSerializer(
    factory: SerializationFactory
) : CustomStructuredObjectSerializer<TransactionState<*>>(factory, deserialize = false) {

  override val properties = mapOf(
      "contract" to KotlinObjectProperty(TransactionState<*>::contract),
      "encumbrance" to KotlinObjectProperty(TransactionState<*>::encumbrance, isMandatory = false),
      "notary" to KotlinObjectProperty(TransactionState<*>::notary),
      "data" to SyntheticObjectProperty(valueType = ContractState::class.java,
          isMandatory = true, accessor = contractStateAccessor)
  )

  companion object {
    @Suppress("UNCHECKED_CAST")
    val contractStateAccessor = { s: TransactionState<ContractState> -> s.data } as ObjectPropertyValueAccessor
  }
}