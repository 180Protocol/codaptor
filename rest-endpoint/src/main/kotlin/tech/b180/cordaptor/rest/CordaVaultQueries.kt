package tech.b180.cordaptor.rest

import tech.b180.cordaptor.corda.CordaVaultQuery
import tech.b180.cordaptor.shaded.javax.json.JsonObject
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
    TODO("Not yet implemented")
  }

  override fun toJson(obj: CordaVaultQuery.Expression, generator: JsonGenerator) {
    TODO("Not yet implemented")
  }

  override fun generateSchema(generator: JsonSchemaGenerator): JsonObject {
    TODO("Not yet implemented")
  }

}

class JsonValueLiteral(private val value: JsonValue) : CordaVaultQuery.LiteralValue {

  override fun asString(): String {
    TODO("Not yet implemented")
  }

  override fun asInt(): Int {
    TODO("Not yet implemented")
  }

  override fun asLong(): Long {
    TODO("Not yet implemented")
  }

  override fun asBoolean(): Boolean {
    TODO("Not yet implemented")
  }

  override fun asDouble(): Double {
    TODO("Not yet implemented")
  }

  override fun asBigDecimal(): BigDecimal {
    TODO("Not yet implemented")
  }

  override fun <E : Enum<*>> asEnum(enumClass: KClass<E>): E {
    TODO("Not yet implemented")
  }

}