package tech.b180.cordaptor.corda

import net.corda.core.node.services.vault.ColumnPredicate
import net.corda.core.node.services.vault.QueryCriteria
import net.corda.core.node.services.vault.builder
import net.corda.core.schemas.MappedSchema
import net.corda.core.schemas.PersistentState
import java.util.*
import kotlin.reflect.KProperty1
import kotlin.reflect.full.declaredMemberProperties
import kotlin.reflect.jvm.javaType

class CreateCordaQuery() : CordaVaultQuery.Visitor<QueryCriteria> {

    private fun splitColumn(column: String) : Pair<String, String> {
        val columnList = column.split(".")
        if (columnList.size != 2)
            throw IllegalArgumentException("The column $column must only have 2 string separated by a .")
        return Pair(columnList.first(), columnList.last())
    }

    private fun getColumnKProperty(attributeName: String, mappedSchema: MappedSchema): KProperty1<out PersistentState, *>? {
        val (persistentName, persistentStateColumnName) = splitColumn(attributeName)

        val persistentStateClass = (mappedSchema.javaClass.kotlin.nestedClasses as List).find {
            it.simpleName!!.equals(persistentName)
        }

        try {
            val columnKProperty =
                persistentStateClass!!.declaredMemberProperties.map { it as KProperty1<out PersistentState, *> }.find {
                    it.name == persistentStateColumnName
                }

            return columnKProperty
        } catch (e: ClassCastException) {
            throw ClassCastException("Column Type cannot be retrieved from Persistent State" + e.printStackTrace())
        }
    }

    private fun castOperatorLiteralValue(columnType: KProperty1<out PersistentState, *>?, value: CordaVaultQuery.LiteralValue): Any {
        return with(columnType?.returnType?.javaType?.typeName!!) {
            when {
                contains("string", ignoreCase = true) -> value.asString()
                contains("int", ignoreCase = true) -> value.asInt()
                contains("long", ignoreCase = true) -> value.asLong()
                contains("UUID", ignoreCase = true) -> UUID.fromString(value.asString())
                else -> throw AssertionError("Expected type not found")
            }
        }
    }

    override fun negation(negation: CordaVaultQuery.Expression.Negation) =
        TODO("Not yet implemented")

    override fun logicalComparison(logicalComposition: CordaVaultQuery.Expression.LogicalComposition) =
        TODO("Not yet implemented")

    override fun equalityComparison(equalityComparison: CordaVaultQuery.Expression.EqualityComparison): QueryCriteria {
        try {
            val columnKProperty = getColumnKProperty(equalityComparison.attributeName, equalityComparison.mappedSchema)
            val equal = builder { columnKProperty!!.equal(castOperatorLiteralValue(columnKProperty, equalityComparison.value)) }
            return QueryCriteria.VaultCustomQueryCriteria(equal)
        } catch (e: ClassCastException) {
            throw ClassCastException("Column Type cannot be retrieved from Persistent State" + e.printStackTrace())
        }
    }

    override fun binaryComparison(binaryComparison: CordaVaultQuery.Expression.BinaryComparison): =
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
