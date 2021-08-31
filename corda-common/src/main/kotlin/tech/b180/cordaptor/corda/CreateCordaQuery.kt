package tech.b180.cordaptor.corda

import net.corda.core.contracts.ContractState
import net.corda.core.contracts.FungibleState
import net.corda.core.contracts.LinearState
import net.corda.core.node.services.vault.ColumnPredicate
import net.corda.core.node.services.vault.QueryCriteria

class CreateCordaQuery(private val contractStateType: Class<*>) : CordaVaultQuery.Visitor<QueryCriteria> {

    fun checkType() {
        when(contractStateType) {
            is LinearState -> "LinearState"
            is FungibleState<*> -> "FungibleState"
            else -> "ContractState"
        }
    }

    override fun negation(negation: CordaVaultQuery.Expression.Negation) =
        TODO("Not yet implemented")

    override fun logicalComparison(logicalComposition: CordaVaultQuery.Expression.LogicalComposition) =
        TODO("Not yet implemented")

    override fun equalityComparison(equalityComparison: CordaVaultQuery.Expression.EqualityComparison): QueryCriteria {
        checkType()
        TODO("Not yet implemented")
    }

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