package tech.b180.cordaptor.corda

import net.corda.core.contracts.ContractState
import net.corda.core.contracts.StateAndRef
import net.corda.core.identity.CordaX500Name
import net.corda.core.node.services.Vault
import net.corda.core.node.services.vault.*
import tech.b180.cordaptor.kernel.ModuleAPI
import java.math.BigDecimal
import java.time.Instant
import java.util.*
import kotlin.reflect.KClass


/**
 * All information necessary to query vault states or
 * subscribe for updates in the vault.
 */
@ModuleAPI(since = "0.1")
data class CordaVaultQuery<T: ContractState>(
    val contractStateClass: KClass<T>,

    /** Zero-based page number to return or 0 by default */
    val pageNumber: Int?,

    /** Number of states to return per results page, or Corda's default max page size */
    val pageSize: Int?,

    /** Top-level expression representing query criteria,
     * may not be used with any of the shortcuts below */
    val expression: Expression? = null,

    /** By default [Vault.StateStatus.UNCONSUMED] */
    val stateStatus: Vault.StateStatus? = null,

    /** By default [Vault.RelevancyStatus.ALL] */
    val relevancyStatus: Vault.RelevancyStatus? = null,

    val linearStateUUIDs: List<UUID>? = null,
    val linearStateExternalIds: List<String>? = null,
    val ownerNames: List<CordaX500Name>? = null,
    val participantNames: List<CordaX500Name>? = null,
    val notaryNames: List<CordaX500Name>? = null,

    /** Will take precedence over [consumedTimeIsAfter] if specified */
    val recordedTimeIsAfter: Instant? = null,

    /** Will be ignored if [recordedTimeIsAfter] if specified */
    val consumedTimeIsAfter: Instant? = null,

    /** Will return states in an unspecified order by default */
    val sortCriteria: List<SortColumn>? = null
) {

  /**
   * Wrapper for [Sort.SortColumn] class available in Corda Vault API.
   * It exists to simplify handling of sorting criteria in REST API.
   */
  data class SortColumn(
      val sortAttribute: String,
      val direction: Sort.Direction)

  /**
   * Wrapper for standard sort columns available in Corda Vault API.
   * These constants are not intended to be used directly, but looked up by [attributeName]
   * when resolving the value of [SortColumn.sortAttribute]
   */
  @Suppress("unused")
  enum class StandardSortAttributes(
      val attributeName: String,
      val attribute: Sort.Attribute
  ) {
    STATE_REF("stateRef", Sort.CommonStateAttribute.STATE_REF),
    STATE_REF_TXN_ID("stateRefTxId", Sort.CommonStateAttribute.STATE_REF_TXN_ID),
    STATE_REF_INDEX("stateRefIndex", Sort.CommonStateAttribute.STATE_REF_INDEX),

    NOTARY_NAME("notary", Sort.VaultStateAttribute.NOTARY_NAME),
    CONTRACT_STATE_TYPE("contractStateClassName", Sort.VaultStateAttribute.CONTRACT_STATE_TYPE),
    STATE_STATUS("stateStatus", Sort.VaultStateAttribute.STATE_STATUS),
    RECORDED_TIME("recordedTime", Sort.VaultStateAttribute.RECORDED_TIME),
    CONSUMED_TIME("consumedTime", Sort.VaultStateAttribute.CONSUMED_TIME),
    LOCK_ID("lockId", Sort.VaultStateAttribute.LOCK_ID),
    CONSTRAINT_TYPE("constraintType", Sort.VaultStateAttribute.CONSTRAINT_TYPE),

    UUID("uuid", Sort.LinearStateAttribute.UUID),
    EXTERNAL_ID("externalId", Sort.LinearStateAttribute.EXTERNAL_ID),

    QUANTITY("quantity", Sort.FungibleStateAttribute.QUANTITY),
    ISSUER_REF("issuerRef", Sort.FungibleStateAttribute.ISSUER_REF)
  }

  /**
   * Container class for various kinds of expressions supported by Corda vault query API.
   * This is a simplified wrapper for [CriteriaExpression] and [ColumnPredicate] API classes,
   * designed for the ease of deserialization from JSON.
   */
  interface Expression {

    /** Passes `this` into appropriate visitor method to allow typesafe API translation */
    fun visit(visitor: Visitor)

    /** Maps to 'not' */
    data class Negation(
        val arg: Expression
    ) : Expression {
      override fun visit(visitor: Visitor) = visitor.negation(this)
    }

    /** Maps to 'and', 'or' */
    data class LogicalComposition(
      val operator: BinaryLogicalOperator,
      val args: List<Expression>
    ) : Expression {
      override fun visit(visitor: Visitor) = visitor.logicalComparison(this)
    }

    /** Maps to 'equals', 'notEquals', 'equalsIgnoreCase', 'notEqualsIgnoreCase' */
    data class EqualityComparison(
        val operator: EqualityComparisonOperator,
        val attributeName: String,
        val value: LiteralValue
    ) : Expression {
      override fun visit(visitor: Visitor) = visitor.equalityComparison(this)
    }

    /** Maps to 'greaterThan', 'greaterThanOrEquals', 'lessThan', 'lessThanOrEquals' */
    data class BinaryComparison(
        val operator: BinaryComparisonOperator,
        val attributeName: String,
        val value: LiteralValue
    ) : Expression {
      override fun visit(visitor: Visitor) = visitor.binaryComparison(this)
    }

    /** Maps to 'like', 'notLike', 'likeIgnoreCase', 'notLikeIgnoreCase' */
    data class Likeness(
        val operator: LikenessOperator,
        val attributeName: String,
        val value: LiteralValue
    ) : Expression {
      override fun visit(visitor: Visitor) = visitor.likeness(this)
    }

    /** Maps to 'between' */
    data class Between(
        val attributeName: String,
        val from: LiteralValue,
        val to: LiteralValue
    ) : Expression {
      override fun visit(visitor: Visitor) = visitor.between(this)
    }

    /** Maps to 'isNull', 'isNotNull' */
    data class NullExpression(
        val operator: NullOperator,
        val attributeName: String
    ) : Expression {
      override fun visit(visitor: Visitor) = visitor.nullExpression(this)
    }

    /** Maps to 'in', 'notIn', 'inIgnoreCase', 'notInIgnoreCase'  */
    data class CollectionExpression(
        val operator: CollectionOperator,
        val attributeName: String,
        val values: List<LiteralValue>
    ) : Expression {
      override fun visit(visitor: Visitor) = visitor.collectionExpression(this)
    }
  }

  interface Visitor {
    fun negation(negation: Expression.Negation)
    fun logicalComparison(logicalComposition: Expression.LogicalComposition)
    fun equalityComparison(equalityComparison: Expression.EqualityComparison)
    fun binaryComparison(binaryComparison: Expression.BinaryComparison)
    fun likeness(likeness: Expression.Likeness)
    fun between(between: Expression.Between)
    fun nullExpression(nullExpression: Expression.NullExpression)
    fun collectionExpression(collectionExpression: Expression.CollectionExpression)
  }

  /**
   * Typesafe wrapper for a literal value passed in an API payload.
   * Implementation will be provided by the deserializer.
   */
  interface LiteralValue {

    fun asString(): String
    fun asInt(): Int
    fun asLong(): Long
    fun asBoolean(): Boolean
    fun asDouble(): Double
    fun asBigDecimal(): BigDecimal
    fun <E : Enum<*>> asEnum(enumClass: KClass<E>): E
  }
}

/**
 * Result for a paged vault query modelled after [Vault.Page]
 */
@ModuleAPI(since = "0.1")
data class CordaVaultPage<T: ContractState>(
    val states: List<StateAndRef<T>>,
    val statesMetadata: List<Vault.StateMetadata>,
    val totalStatesAvailable: Long,
    val stateTypes: Vault.StateStatus
)
