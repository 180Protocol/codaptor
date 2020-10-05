package tech.b180.cordaptor.rest

import net.corda.core.crypto.SecureHash
import net.corda.core.identity.CordaX500Name
import net.corda.core.identity.Party
import net.corda.core.identity.PartyAndCertificate
import net.corda.core.node.NodeInfo
import net.corda.core.node.services.IdentityService
import net.corda.core.node.services.TransactionStorage
import net.corda.core.transactions.SignedTransaction
import net.corda.serialization.internal.model.LocalTypeInformation
import net.corda.serialization.internal.model.LocalTypeModel
import net.corda.serialization.internal.model.PropertyName
import java.security.cert.X509Certificate
import java.util.*
import javax.json.Json
import javax.json.JsonObject
import javax.json.JsonValue
import javax.json.stream.JsonGenerator
import kotlin.reflect.KClass
import kotlin.reflect.KProperty

/**
 * Serializer for [CordaX500Name] converting to/from a string value.
 */
class CordaX500NameSerializer : CustomSerializer<CordaX500Name>,
    SerializationFactory.DelegatingSerializer<CordaX500Name, String>(
    delegate = SerializationFactory.StringSerializer,
    delegate2my = { CordaX500Name.parse(this) },
    my2delegate = { this.toString() }
) {
  override val appliedTo: KClass<*>
    get() = CordaX500Name::class
}

/**
 * Serializer for [SecureHash] converting to/from a string value.
 */
class CordaSecureHashSerializer : CustomSerializer<SecureHash>,
    SerializationFactory.DelegatingSerializer<SecureHash, String>(
    delegate = SerializationFactory.StringSerializer,
    delegate2my = { SecureHash.parse(this) },
    my2delegate = { this.toString() }
) {
  override val schema: JsonObject = mapOf(
      "type" to "string",
      "minLength" to 64,
      "maxLength" to 64,
      "pattern" to "^[A-Z0-9]{64}"
  ).asJsonObject()

  override val appliedTo: KClass<*>
    get() = SecureHash::class
}

/**
 * Serializer for [UUID] converting to/from a string value.
 *
 * Technically it is not a Corda class, but it is commonly used in Corda API.
 */
class CordaUUIDSerializer : CustomSerializer<UUID>,
    SerializationFactory.DelegatingSerializer<UUID, String>(
    delegate = SerializationFactory.StringSerializer,
    my2delegate = { this.toString() },
    delegate2my = { UUID.fromString(this ) }
) {
  override val schema: JsonObject = mapOf(
      "type" to "string",
      "format" to "uuid"
  ).asJsonObject()

  override val appliedTo: KClass<*>
    get() = UUID::class
}

/**
 * Serializer for [Party] representing it as an object with X500 name.
 *
 * When reading from JSON, it attempts to resolve the party by calling
 * [IdentityService.wellKnownPartyFromX500Name] method
 */
class CordaPartySerializer(
    localTypeModel: LocalTypeModel,
    factory: SerializationFactory,
    private val identityService: IdentityService
) : CustomSerializer<Party>, ComposableTypeJsonSerializer<Party>(
    type = localTypeModel.inspect(Party::class.java) as LocalTypeInformation.Composable,
    factory = factory
) {

  override val serializedClassProperties = listOf(Party::name)

  override fun initializeInstance(values: Map<PropertyName, Any?>): Party {
    val nameValue = values["name"]
    assert(nameValue is CordaX500Name) { "Expected X500 name, got $nameValue" }

    val name = nameValue as CordaX500Name

    return identityService.wellKnownPartyFromX500Name(name)
        ?: throw SerializationException("Party with name $name is not known")
  }

  override val appliedTo: KClass<*>
    get() = Party::class
}

/**
 * Serializer for [SignedTransaction] representing it as JSON value.
 *
 * All transaction details are includes only in the output. For the input only transaction hash
 * is accepted, and then is used to resolve
 */
class CordaSignedTransactionSerializer(
    localTypeModel: LocalTypeModel,
    factory: SerializationFactory,
    private val transactionStorage: TransactionStorage
) : CustomSerializer<SignedTransaction>, ComposableTypeJsonSerializer<SignedTransaction>(
    type = localTypeModel.inspect(Party::class.java) as LocalTypeInformation.Composable,
    factory = factory
) {

  override val serializedClassProperties = listOf(SignedTransaction::id, SignedTransaction::coreTransaction)
  override val deserializedClassProperties = listOf(SignedTransaction::id)

  override fun initializeInstance(values: Map<PropertyName, Any?>): SignedTransaction {
    val hashValue = values["id"]
    assert(hashValue is SecureHash) { "Expected hash, got $hashValue" }

    val hash = hashValue as SecureHash

    return transactionStorage.getTransaction(hash)
        ?: throw SerializationException("Transaction with hash $hash is not known")
  }

  override val appliedTo: KClass<*>
    get() = SignedTransaction::class
}

/**
 * Serializer for [PartyAndCertificate] representing it as JSON value.
 * This object is most commonly used as part of a [NodeInfo] structure.
 *
 * FIXME implement serialization logic for instances of [X509Certificate] abstract class
 * FIXME actually write contents of the class
 */
class CordaPartyAndCertificateSerializer : CustomSerializer<PartyAndCertificate> {

  override fun fromJson(value: JsonValue): PartyAndCertificate {
    throw SerializationException("Instances of this class cannot be deserialized from JSON")
  }

  override fun toJson(obj: PartyAndCertificate, generator: JsonGenerator) {
    generator.writeStartObject().writeEnd()
  }

  override val schema: JsonObject
    get() = Json.createObjectBuilder().add("type", "object").build()

  override val appliedTo: KClass<*>
    get() = PartyAndCertificate::class
}