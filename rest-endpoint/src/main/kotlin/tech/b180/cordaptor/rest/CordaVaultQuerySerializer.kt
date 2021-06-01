package tech.b180.cordaptor.rest

import tech.b180.cordaptor.corda.CordaVaultQuery
import tech.b180.cordaptor.shaded.javax.json.JsonNumber
import tech.b180.cordaptor.shaded.javax.json.JsonObject
import tech.b180.cordaptor.shaded.javax.json.JsonString
import tech.b180.cordaptor.shaded.javax.json.JsonValue
import tech.b180.cordaptor.shaded.javax.json.stream.JsonGenerator
import java.math.BigDecimal
import kotlin.reflect.KClass

/**
 * Bespoke implementation for JSON deserialization logic for
 * [CordaVaultQuery.Expression] class hierarchy.
 */
class CordaVaultQueryExpressionSerializer : CustomSerializer<CordaVaultQuery.Expression>, StandaloneTypeSerializer {

  override val valueType: SerializerKey
    get() = TODO("Not yet implemented")

  override fun fromJson(value: JsonValue): CordaVaultQuery.Expression {

    val jsonValue = value.asJsonObject()

    return when (jsonValue["type"].toString()) {
      "not" -> TODO()
      "to", "or" -> TODO()
      "equals", "notEquals", "equalsIgnoreCase", "notEqualsIgnoreCase" -> TODO()
      "greaterThan", "greaterThanOrEquals", "lessThan", "lessThanOrEquals" -> TODO()
      "like", "notLike", "likeIgnoreCase", "notLikeIgnoreCase" -> TODO()
      "between" -> CordaVaultQuery.Expression.Between(jsonValue["column"].toString(), JsonValueLiteral(jsonValue["from"]!!), JsonValueLiteral(jsonValue["to"]!!))
      "isNull", "isNotNull" -> TODO()
      "in", "notIn", "inIgnoreCase", "notInIgnoreCase" -> TODO()
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
    TODO()
  }
}

class JsonValueLiteral(private val value: JsonValue) : CordaVaultQuery.LiteralValue {

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
}