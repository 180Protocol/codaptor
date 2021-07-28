package tech.b180.cordaptor.rest

import net.corda.core.node.services.vault.*

/* Utility class for converting string into proper Operator. */
class GetOperatorFromJsonImpl: GetOperatorFromJson {

    override fun getEqualityOperator(operator: String): EqualityComparisonOperator {
        return when (operator) {
            "equals" -> EqualityComparisonOperator.EQUAL
            "notEquals" -> EqualityComparisonOperator.NOT_EQUAL
            "equalsIgnoreCase" -> EqualityComparisonOperator.EQUAL_IGNORE_CASE
            "notEqualsIgnoreCase" -> EqualityComparisonOperator.NOT_EQUAL_IGNORE_CASE
            else -> throw Exception("Unknown operator. $operator was given.")
        }
    }

    override fun getBinaryOperator(operator: String): BinaryComparisonOperator {
        return when (operator) {
            "greaterThan" -> BinaryComparisonOperator.GREATER_THAN
            "greaterThanOrEquals" -> BinaryComparisonOperator.GREATER_THAN_OR_EQUAL
            "lessThan" -> BinaryComparisonOperator.LESS_THAN
            "lessThanOrEquals" -> BinaryComparisonOperator.LESS_THAN_OR_EQUAL
            else -> throw Exception("Unknown operator. $operator was given.")
        }
    }

    override fun getLikenessOperator(operator: String): LikenessOperator {
        return when (operator) {
            "like" -> LikenessOperator.LIKE
            "notLike" -> LikenessOperator.NOT_LIKE
            "likeIgnoreCase" -> LikenessOperator.LIKE_IGNORE_CASE
            "notLikeIgnoreCase" -> LikenessOperator.NOT_LIKE_IGNORE_CASE
            else -> throw Exception("Unknown operator. $operator was given.")
        }
    }

    override fun getNullOperator(operator: String): NullOperator {
        return when (operator) {
            "isNull" -> NullOperator.IS_NULL
            "isNotNull" -> NullOperator.NOT_NULL
            else -> throw Exception("Unknown operator. $operator was given.")
        }
    }

    override fun getCollectionOperator(operator: String): CollectionOperator {
        return when (operator) {
            "in" -> CollectionOperator.IN
            "notIn" -> CollectionOperator.NOT_IN
            "inIgnoreCase" -> CollectionOperator.IN_IGNORE_CASE
            "notInIgnoreCase" -> CollectionOperator.NOT_IN_IGNORE_CASE
            else -> throw Exception("Unknown operator. $operator was given.")
        }
    }
}

interface GetOperatorFromJson {
    fun getEqualityOperator(operator: String): EqualityComparisonOperator
    fun getBinaryOperator(operator: String): BinaryComparisonOperator
    fun getLikenessOperator(operator: String): LikenessOperator
    fun getNullOperator(operator: String): NullOperator
    fun getCollectionOperator(operator: String): CollectionOperator
}