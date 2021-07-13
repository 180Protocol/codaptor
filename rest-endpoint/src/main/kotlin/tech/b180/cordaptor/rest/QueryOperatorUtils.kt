package tech.b180.cordaptor.rest

import net.corda.core.node.services.vault.*
import tech.b180.cordaptor.corda.CordaVaultQuery

fun getEqualityOperator(operator: String): EqualityComparisonOperator {
    return when(operator) {
        "equals" ->  EqualityComparisonOperator.EQUAL
        "notEquals" ->  EqualityComparisonOperator.NOT_EQUAL
        "equalsIgnoreCase" -> EqualityComparisonOperator.EQUAL_IGNORE_CASE
        "notEqualsIgnoreCase" -> EqualityComparisonOperator.NOT_EQUAL_IGNORE_CASE
        else -> throw Exception("Unknown operator. $operator was given.")
    }
}

fun getBinaryOperator(operator: String): BinaryComparisonOperator {
    return when(operator) {
        "greaterThan" -> BinaryComparisonOperator.GREATER_THAN
        "greaterThanOrEquals" -> BinaryComparisonOperator.GREATER_THAN_OR_EQUAL
        "lessThan" -> BinaryComparisonOperator.LESS_THAN
        "lessThanOrEquals" -> BinaryComparisonOperator.LESS_THAN_OR_EQUAL
        else -> throw Exception("Unknown operator. $operator was given.")
    }
}

fun getLikenessOperator(operator: String): LikenessOperator {
    return when(operator) {
        "like" -> LikenessOperator.LIKE
        "notLike" -> LikenessOperator.NOT_LIKE
        "likeIgnoreCase" -> LikenessOperator.LIKE_IGNORE_CASE
        "notLikeIgnoreCase" -> LikenessOperator.NOT_LIKE_IGNORE_CASE
        else -> throw Exception("Unknown operator. $operator was given.")
    }
}

fun getNullOperator(operator: String): NullOperator {
    return when(operator) {
        "isNull" -> NullOperator.IS_NULL
        "isNotNull" -> NullOperator.NOT_NULL
        else -> throw Exception("Unknown operator. $operator was given.")
    }
}

fun getCollectionOperator(operator: String): CollectionOperator {
    return when(operator) {
        "in" -> CollectionOperator.IN
        "notIn" -> CollectionOperator.NOT_IN
        "inIgnoreCase" -> CollectionOperator.IN_IGNORE_CASE
        "notInIgnoreCase" -> CollectionOperator.NOT_IN_IGNORE_CASE
        else -> throw Exception("Unknown operator. $operator was given.")
    }
}

fun queryType(column: String, value: CordaVaultQuery.LiteralValue) : QueryCriteria {
    return when(column.substringBefore(".")){
        "VaultLinearStates" -> linearStateQuery(column, value)
        "VaultFungibleAsset" -> fungibleAssetQuery(column, value)
        "CustomSchema" -> TODO()
        else -> vaultQuery(column, value)
    }
}

fun vaultQuery(column: String, value: CordaVaultQuery.LiteralValue) : QueryCriteria {
    return when(column) {
        "status" -> TODO()
        "contractStateStateTypes" -> TODO()
        "stateRefs" -> TODO()
        "notary" -> TODO()
        "softLockingCondition" -> TODO()
        "timeCondition" -> TODO()
        "relevancyStatus" -> TODO()
        "constraintTypes" -> TODO()
        "constraints" -> TODO()
        "participants" -> TODO()
        "externalId" -> QueryCriteria.LinearStateQueryCriteria(externalId = listOf(value.asString()))
        "exactParticipants" -> TODO()
        else -> throw AssertionError("Column with the value $column does not match any of the columns")
    }
}

fun linearStateQuery(column: String, value: CordaVaultQuery.LiteralValue) : QueryCriteria {
    return when(column) {
        "participants" -> TODO()
        "uuid" -> TODO()
        "externalId" -> QueryCriteria.LinearStateQueryCriteria(externalId = listOf(value.asString()))
        "status" -> TODO()
        "contractStateTypes" -> TODO()
        "relevancyStatus" -> TODO()
        "exactParticipants" -> TODO()
        else -> throw AssertionError("Column with the value $column does not match any of the columns")
    }
}

fun fungibleAssetQuery(column: String, value: CordaVaultQuery.LiteralValue) : QueryCriteria {
    return when(column) {
        "participants" -> TODO()
        "owner" -> TODO()
        "quantity" -> TODO()
        "issuer" -> TODO()
        "issueRef" -> TODO()
        "status" -> TODO()
        "contractStateTypes" -> TODO()
        "relevancyStatus" -> TODO()
        "exactParticipants" -> TODO()
        else -> throw AssertionError("Column with the value $column does not match any of the columns")
    }
}

fun commonCriteria(column: String, value: CordaVaultQuery.LiteralValue) : QueryCriteria {
    return when(column) {
        "status" -> TODO()
        "relevancyStatus" -> TODO()
        "constraintTypes" -> TODO()
        "constraints" -> TODO()
        "participants" -> TODO()
        "contractStateTypes" -> TODO()
        "externalIds" -> TODO()
        "exactParticipants" -> TODO()
        else -> throw AssertionError("Column with the value $column does not match any of the columns")
    }
}