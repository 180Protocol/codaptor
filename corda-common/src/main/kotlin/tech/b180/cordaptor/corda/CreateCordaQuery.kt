package tech.b180.cordaptor.corda

import net.corda.core.contracts.ContractState
import net.corda.core.node.services.vault.ColumnPredicate
import net.corda.core.node.services.vault.QueryCriteria
import net.corda.core.node.services.vault.builder
import net.corda.core.schemas.PersistentState
import kotlin.reflect.KProperty1
import kotlin.reflect.full.declaredMemberProperties

class CreateCordaQuery(private val contractStateType: Class<out ContractState>) : CordaVaultQuery.Visitor<QueryCriteria> {

    private fun splitColumn(column: String) : Pair<String, String> {
        val columnList = column.split(".")
        if (columnList.size != 2)
            throw IllegalArgumentException("The column $column must only have 2 string separated by a .")
        return Pair(columnList.first(), columnList.last())
    }

    override fun negation(negation: CordaVaultQuery.Expression.Negation) =
        TODO("Not yet implemented")

    override fun logicalComparison(logicalComposition: CordaVaultQuery.Expression.LogicalComposition) =
        TODO("Not yet implemented")

    override fun equalityComparison(equalityComparison: CordaVaultQuery.Expression.EqualityComparison): QueryCriteria {
        // wrap into a single function ( remove the persistentState )
        val (persistentName, persistentStateColumnName) = splitColumn(equalityComparison.attributeName)

        val persistentStateClass = (equalityComparison.mappedSchema.javaClass.kotlin.nestedClasses as List).find {
            it.simpleName!!.equals(persistentName)
        }
        // up to here

        try {
            // try to create a common function for getting the columnType
            val columnType =
                persistentStateClass!!.declaredMemberProperties.map { it as KProperty1<out PersistentState, *> }.find {
                    it.name == persistentStateColumnName
                }
            // up to here
            val equal = builder { columnType!!.equal(equalityComparison.value) }
            return QueryCriteria.VaultCustomQueryCriteria(equal)
        } catch (e: ClassCastException) {
            throw ClassCastException("Column Type cannot be retrieved from Persistent State" + e.printStackTrace())
        }
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