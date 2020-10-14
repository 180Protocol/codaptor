package tech.b180.cordaptor.rest

import net.corda.core.contracts.ContractState
import net.corda.core.contracts.TransactionState
import net.corda.core.crypto.SecureHash
import net.corda.core.crypto.TransactionSignature
import net.corda.core.identity.CordaX500Name
import net.corda.core.identity.Party
import net.corda.core.identity.PartyAndCertificate
import net.corda.core.node.NodeInfo
import net.corda.core.node.services.IdentityService
import net.corda.core.node.services.TransactionStorage
import net.corda.core.transactions.*
import java.security.PublicKey
import java.security.cert.X509Certificate
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.*
import javax.json.JsonObject
import kotlin.reflect.KClass

/**
 * Serializer for [CordaX500Name] converting to/from a string value.
 */
class CordaX500NameSerializer : CustomSerializer<CordaX500Name>,
    SerializationFactory.DelegatingSerializer<CordaX500Name, String>(
    delegate = SerializationFactory.StringSerializer,
    delegate2my = { CordaX500Name.parse(it) },
    my2delegate = CordaX500Name::toString
) {
  override val appliedTo = CordaX500Name::class
}

/**
 * Serializer for [SecureHash] converting to/from a string value.
 */
class CordaSecureHashSerializer : CustomSerializer<SecureHash>,
    SerializationFactory.DelegatingSerializer<SecureHash, String>(
    delegate = SerializationFactory.StringSerializer,
    delegate2my = { SecureHash.parse(it) },
    my2delegate = SecureHash::toString
) {
  override val schema: JsonObject = mapOf(
      "type" to "string",
      "minLength" to 64,
      "maxLength" to 64,
      "pattern" to "^[A-Z0-9]{64}"
  ).asJsonObject()

  override val appliedTo = SecureHash::class
}

/**
 * Serializer for [UUID] converting to/from a string value.
 *
 * Technically it is not a Corda class, but it is commonly used in Corda API.
 */
class CordaUUIDSerializer : CustomSerializer<UUID>,
    SerializationFactory.DelegatingSerializer<UUID, String>(
    delegate = SerializationFactory.StringSerializer,
    my2delegate = UUID::toString,
    delegate2my = UUID::fromString
) {
  override val schema: JsonObject = mapOf(
      "type" to "string",
      "format" to "uuid"
  ).asJsonObject()

  override val appliedTo = UUID::class
}

class JavaInstantSerializer : CustomSerializer<Instant>,
    SerializationFactory.DelegatingSerializer<Instant, String>(
        delegate = SerializationFactory.StringSerializer,
        my2delegate = { DateTimeFormatter.ISO_INSTANT.format(this) },
        delegate2my = { Instant.parse(it) }
    ) {

  override val schema: JsonObject = mapOf(
      "type" to "string",
      "format" to "date-time"
  ).asJsonObject()

  override val appliedTo: KClass<*> = Instant::class
}

/**
 * Serializer for [Party] representing it as an object with X500 name.
 *
 * When reading from JSON, it attempts to resolve the party by calling
 * [IdentityService.wellKnownPartyFromX500Name] method
 */
class CordaPartySerializer(
    factory: SerializationFactory,
    private val identityService: IdentityService
) : CustomStructuredObjectSerializer<Party>(Party::class, factory) {

  override val properties: Map<String, ObjectProperty> = mapOf(
      "name" to KotlinObjectProperty(Party::name, isMandatory = true)
  )

  override fun initializeInstance(values: Map<String, Any?>): Party {
    val nameValue = values["name"]
    assert(nameValue is CordaX500Name) { "Expected X500 name, got $nameValue" }

    val name = nameValue as CordaX500Name

    return identityService.wellKnownPartyFromX500Name(name)
        ?: throw SerializationException("Party with name $name is not known")
  }
}

/**
 * Serializer for [SignedTransaction] representing it as JSON value.
 *
 * All transaction details are includes only in the output. For the input only transaction hash
 * is accepted, and then is used to resolve
 */
class CordaSignedTransactionSerializer(
    factory: SerializationFactory,
    private val transactionStorage: TransactionStorage
) : CustomStructuredObjectSerializer<SignedTransaction>(SignedTransaction::class, factory) {

  override val properties: Map<String, ObjectProperty> = mapOf(
      "id" to KotlinObjectProperty(SignedTransaction::id),
      "core" to KotlinObjectProperty(SignedTransaction::coreTransaction, deserialize = false),
      "sigs" to KotlinObjectProperty(SignedTransaction::sigs, deserialize = false)
  )

  override fun initializeInstance(values: Map<String, Any?>): SignedTransaction {
    val hashValue = values["id"]
    assert(hashValue is SecureHash) { "Expected hash, got $hashValue" }

    val hash = hashValue as SecureHash

    return transactionStorage.getTransaction(hash)
        ?: throw SerializationException("Transaction with hash $hash is not known")
  }
}

/**
 * Serializer for [TransactionSignature] representing it as JSON value.
 * This object is most commonly used as part of a [SignedTransaction] structure.
 */
class CordaTransactionSignatureSerializer(
    factory: SerializationFactory
) : CustomStructuredObjectSerializer<TransactionSignature>(TransactionSignature::class, factory, deserialize = false) {

  override val properties: Map<String, ObjectProperty> = mapOf(
      "by" to KotlinObjectProperty(TransactionSignature::by),
      "metadata" to KotlinObjectProperty(TransactionSignature::signatureMetadata, deserialize = false)
  )
}

/**
 * Serializer for [PartyAndCertificate] representing it as JSON value.
 * This object is most commonly used as part of a [NodeInfo] structure.
 *
 * FIXME implement serialization logic for instances of [X509Certificate] abstract class
 */
class CordaPartyAndCertificateSerializer(factory: SerializationFactory)
  : CustomStructuredObjectSerializer<PartyAndCertificate>(PartyAndCertificate::class, factory, deserialize = false) {

  override val properties = mapOf(
      "party" to KotlinObjectProperty(PartyAndCertificate::party)
  )
}

/**
 * Serializer for known subclasses of [WireTransaction] able to represent them as JSON objects.
 * This will most often be used in the context of [CordaSignedTransactionSerializer]
 *
 * The serializer does not allow reading wire transaction data from JSON.
 */
class CordaCoreTransactionSerializer(factory: SerializationFactory) : CustomAbstractClassSerializer<CoreTransaction>(
    CoreTransaction::class, factory, deserialize = false) {

  override val subclassesMap: Map<String, SerializerKey> = mapOf(
      "wireTransaction" to SerializerKey(WireTransaction::class)
  )
}

class CordaWireTransactionSerializer(factory: SerializationFactory)
  : CustomStructuredObjectSerializer<WireTransaction>(WireTransaction::class, factory, deserialize = false) {

  override val properties = mapOf(
      "inputs" to KotlinObjectProperty(WireTransaction::inputs),
      "outputs" to KotlinObjectProperty(WireTransaction::outputs),
      "inputs" to KotlinObjectProperty(WireTransaction::commands),
      "references" to KotlinObjectProperty(WireTransaction::references)
  )
}

class CordaPublicKeySerializer(
    factory: SerializationFactory,
    identityService: IdentityService
) : CustomStructuredObjectSerializer<PublicKey>(PublicKey::class, factory, deserialize = false) {

  override val properties = mapOf(
      "fingerprint" to SyntheticObjectProperty(valueType = String::class.java,
          deserialize = false, isMandatory = false, accessor = { "not implemented" }),
      "knownParty" to SyntheticObjectProperty(valueType = Party::class.java,
          deserialize = false, isMandatory = false, accessor = makeKnownPartyAccessor(identityService))
  )

  companion object {
    @Suppress("UNCHECKED_CAST")
    fun makeKnownPartyAccessor(identityService: IdentityService) =
        { key: PublicKey -> identityService.partyFromKey(key) } as ObjectPropertyValueAccessor
  }
}

class CordaTransactionStateSerializer(
    factory: SerializationFactory
) : CustomStructuredObjectSerializer<TransactionState<*>>(TransactionState::class, factory, deserialize = false) {

  override val properties = mapOf(
      "contract" to KotlinObjectProperty(TransactionState<*>::contract),
      "encumbrance" to KotlinObjectProperty(TransactionState<*>::encumbrance, isMandatory = false),
      "notary" to KotlinObjectProperty(TransactionState<*>::notary),
      "data" to SyntheticObjectProperty(valueType = ContractState::class.java,
          deserialize = false, isMandatory = true, accessor = contractStateAccessor)
      )

  companion object {
    @Suppress("UNCHECKED_CAST")
    val contractStateAccessor = { s: TransactionState<ContractState> -> s.data } as ObjectPropertyValueAccessor
  }
}