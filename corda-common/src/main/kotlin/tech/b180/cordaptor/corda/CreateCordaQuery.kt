package tech.b180.cordaptor.corda

import net.corda.core.contracts.ContractState
import net.corda.core.node.services.vault.ColumnPredicate
import net.corda.core.node.services.vault.QueryCriteria
import net.corda.core.node.services.vault.builder
import net.corda.core.schemas.MappedSchema
import net.corda.core.schemas.PersistentState
import net.corda.node.services.schema.NodeSchemaService

class CreateCordaQuery(private val contractStateType: Class<out ContractState>) : CordaVaultQuery.Visitor<QueryCriteria> {

    private fun constructDummyContractState() : Pair<MappedSchema, PersistentState>{
        val contractState = contractStateType.newInstance()
        val mappedSchemas = NodeSchemaService().selectSchemas(contractState).first()
        val persistentState = NodeSchemaService().generateMappedObject(contractState, mappedSchemas)
        return Pair(mappedSchemas, persistentState)
    }

    override fun negation(negation: CordaVaultQuery.Expression.Negation) =
        TODO("Not yet implemented")

    override fun logicalComparison(logicalComposition: CordaVaultQuery.Expression.LogicalComposition) =
        TODO("Not yet implemented")

    override fun equalityComparison(equalityComparison: CordaVaultQuery.Expression.EqualityComparison): QueryCriteria {
        val (mappedSchema, persistentState) = constructDummyContractState()
        val criteria = builder {

        }
        TODO("Current Blocker")
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