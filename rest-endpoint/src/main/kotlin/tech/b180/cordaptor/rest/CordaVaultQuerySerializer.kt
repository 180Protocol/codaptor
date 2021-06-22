package tech.b180.cordaptor.rest

import net.corda.core.node.services.vault.*
import tech.b180.cordaptor.corda.CordaVaultQuery
import tech.b180.cordaptor.shaded.javax.json.JsonNumber
import tech.b180.cordaptor.shaded.javax.json.JsonObject
import tech.b180.cordaptor.shaded.javax.json.JsonString
import tech.b180.cordaptor.shaded.javax.json.JsonValue
import tech.b180.cordaptor.shaded.javax.json.stream.JsonGenerator
import java.math.BigDecimal
import java.time.Instant
import kotlin.reflect.KClass

/**
 * Bespoke implementation for JSON deserialization logic for
 * [CordaVaultQuery.Expression] class hierarchy.
 */
class CordaVaultQueryExpressionSerializer : CustomSerializer<CordaVaultQuery.Expression>, StandaloneTypeSerializer {

  override val valueType: SerializerKey = SerializerKey.forType(CordaVaultQuery.Expression::class.java)

  override fun fromJson(value: JsonValue): CordaVaultQuery.Expression {

    val jsonValue = value.asJsonObject()

    return when (jsonValue.getString("type")) {
      "not" -> TODO()
      "and", "or" -> TODO()
      "equals", "notEquals", "equalsIgnoreCase", "notEqualsIgnoreCase" -> CordaVaultQuery.Expression.EqualityComparison(getEqualityOperator(jsonValue.getString("type")), jsonValue.getString("column"), JsonValueLiteral(jsonValue["value"]!!))
      "greaterThan", "greaterThanOrEquals", "lessThan", "lessThanOrEquals" -> CordaVaultQuery.Expression.BinaryComparison(getBinaryOperator(jsonValue.getString("type")), jsonValue.getString("column"), JsonValueLiteral(jsonValue["value"]!!))
      "like", "notLike", "likeIgnoreCase", "notLikeIgnoreCase" -> CordaVaultQuery.Expression.Likeness(getLikenessOperator(jsonValue.getString("type")), jsonValue.getString("column"), JsonValueLiteral(jsonValue["value"]!!))
      "between" -> CordaVaultQuery.Expression.Between(jsonValue.getString("column"), JsonValueLiteral(jsonValue["from"]!!), JsonValueLiteral(jsonValue["to"]!!))
      "isNull", "isNotNull" -> CordaVaultQuery.Expression.NullExpression(getNullOperator(jsonValue.getString("type")), jsonValue.getString("column"))
      "in", "notIn", "inIgnoreCase", "notInIgnoreCase" -> CordaVaultQuery.Expression.CollectionExpression(getCollectionOperator(jsonValue.getString("type")), jsonValue.getString("column"), JsonValueLiteral(jsonValue["values"]!!).asList())
      else -> throw Exception("placeholder")
    }
  }

  override fun toJson(obj: CordaVaultQuery.Expression, generator: JsonGenerator) {
    TODO("Not yet implemented")
  }

  override fun generateSchema(generator: JsonSchemaGenerator): JsonObject {
    return mapOf(
      "type" to "string",
      "from" to "LiteralValue",
      "to" to "LiteralValue"
    ).asJsonObject()
  }

  private fun getEqualityOperator(operator: String): EqualityComparisonOperator {
    return when(operator) {
      "equals" ->  EqualityComparisonOperator.EQUAL
      "notEquals" ->  EqualityComparisonOperator.NOT_EQUAL
      "equalsIgnoreCase" -> EqualityComparisonOperator.EQUAL_IGNORE_CASE
      "notEqualsIgnoreCase" -> EqualityComparisonOperator.NOT_EQUAL_IGNORE_CASE
      else -> throw Exception("Unknown operator. $operator was given.")
    }
  }

  private fun getBinaryOperator(operator: String): BinaryComparisonOperator {
    return when(operator) {
      "greaterThan" -> BinaryComparisonOperator.GREATER_THAN
      "greaterThanOrEquals" -> BinaryComparisonOperator.GREATER_THAN_OR_EQUAL
      "lessThan" -> BinaryComparisonOperator.LESS_THAN
      "lessThanOrEquals" -> BinaryComparisonOperator.LESS_THAN_OR_EQUAL
      else -> throw Exception("Unknown operator. $operator was given.")
    }
  }

  private fun getLikenessOperator(operator: String): LikenessOperator {
    return when(operator) {
      "like" -> LikenessOperator.LIKE
      "notLike" -> LikenessOperator.NOT_LIKE
      "likeIgnoreCase" -> LikenessOperator.LIKE_IGNORE_CASE
      "notLikeIgnoreCase" -> LikenessOperator.NOT_LIKE_IGNORE_CASE
      else -> throw Exception("Unknown operator. $operator was given.")
    }
  }

  private fun getNullOperator(operator: String): NullOperator {
    return when(operator) {
      "isNull" -> NullOperator.IS_NULL
      "isNotNull" -> NullOperator.NOT_NULL
      else -> throw Exception("Unknown operator. $operator was given.")
    }
  }

  private fun getCollectionOperator(operator: String): CollectionOperator {
    return when(operator) {
      "in" -> CollectionOperator.IN
      "notIn" -> CollectionOperator.NOT_IN
      "inIgnoreCase" -> CollectionOperator.IN_IGNORE_CASE
      "notInIgnoreCase" -> CollectionOperator.NOT_IN_IGNORE_CASE
      else -> throw Exception("Unknown operator. $operator was given.")
    }
  }
}

data class CreateCordaQuery(val expression: CordaVaultQuery.Expression) : CordaVaultQuery.Visitor<QueryCriteria> {

  override fun negation(negation: CordaVaultQuery.Expression.Negation) =
    TODO("Not yet implemented")

  override fun logicalComparison(logicalComposition: CordaVaultQuery.Expression.LogicalComposition) =
    TODO("Not yet implemented")

  override fun equalityComparison(equalityComparison: CordaVaultQuery.Expression.EqualityComparison) =
    TODO("Not yet implemented")

  override fun binaryComparison(binaryComparison: CordaVaultQuery.Expression.BinaryComparison) =
    TODO("Not yet implemented")

  override fun likeness(likeness: CordaVaultQuery.Expression.Likeness) =
    TODO("Not yet implemented")

  override fun between(between: CordaVaultQuery.Expression.Between): QueryCriteria {
    val recordedBetweenExpression = QueryCriteria.TimeCondition(
      QueryCriteria.TimeInstantType.RECORDED,
      ColumnPredicate.Between(between.from.asInstant(), between.to.asInstant())
    )
    return QueryCriteria.VaultQueryCriteria(timeCondition = recordedBetweenExpression)
  }

  override fun nullExpression(nullExpression: CordaVaultQuery.Expression.NullExpression) =
    TODO("Not yet implemented")

  override fun collectionExpression(collectionExpression: CordaVaultQuery.Expression.CollectionExpression) =
    TODO("Not yet implemented")
}

data class JsonValueLiteral(private val value: JsonValue) : CordaVaultQuery.LiteralValue {

  override fun asString(): String {
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

  override fun asInt(): Int {
    return when (value.valueType) {
      // provide limited number of type conversions
      JsonValue.ValueType.NUMBER -> (value as JsonNumber).intValue()  // discard fractional part
      JsonValue.ValueType.STRING -> Integer.parseInt((value as JsonString).string)
      else -> throw AssertionError("Expected integer, got ${value.valueType} with value $value")
    }
  }

  override fun asLong(): Long {
    return when (value.valueType) {
      // provide limited number of type conversions
      JsonValue.ValueType.NUMBER -> (value as JsonNumber).longValue()  // discard fractional part
      JsonValue.ValueType.STRING -> (value as JsonString).string.toLong()
      else -> throw AssertionError("Expected integer, got ${value.valueType} with value $value")
    }
  }

  override fun asBoolean(): Boolean {
    return when (value.valueType) {
      // provide limited number of type conversions
      JsonValue.ValueType.TRUE -> true  // discard fractional part
      JsonValue.ValueType.FALSE -> false
      else -> throw AssertionError("Expected boolean, got ${value.valueType} with value $value")
    }
  }

  override fun asDouble(): Double {
    return when (value.valueType) {
      // provide limited number of type conversions
      JsonValue.ValueType.NUMBER -> (value as JsonNumber).doubleValue()
      JsonValue.ValueType.STRING -> (value as JsonString).string.toDouble()
      else -> throw AssertionError("Expected number, got ${value.valueType} with value $value")
    }
  }

  override fun asBigDecimal(): BigDecimal {
    return when (value.valueType) {
      // provide limited number of type conversions
      JsonValue.ValueType.NUMBER -> (value as JsonNumber).bigDecimalValue()
      JsonValue.ValueType.STRING -> (value as JsonString).string.toBigDecimal()
      else -> throw AssertionError("Expected number, got ${value.valueType} with value $value")
    }
  }

  override fun <E : Enum<*>> asEnum(enumClass: KClass<E>): E {
    TODO("Not yet implemented")
  }

  override fun asInstant(): Instant {
    return when (value.valueType) {
      JsonValue.ValueType.STRING -> Instant.parse((value as JsonString).string + "T00:00:00Z")
      else -> throw AssertionError("Expected date, got ${value.valueType} with value $value")
    }
  }

  override fun asList(): List<CordaVaultQuery.LiteralValue> {
    val list = mutableListOf<CordaVaultQuery.LiteralValue>()
    when (value.valueType) {
      JsonValue.ValueType.ARRAY -> TODO()
      JsonValue.ValueType.OBJECT -> {
        (value.asJsonObject()["values"] as List<JsonValue>).forEach {
          list.add(JsonValueLiteral(it))
        }
      }
      else -> throw AssertionError("Expected object or array, got ${value.valueType} with value $value")
    }
    return list
  }
}