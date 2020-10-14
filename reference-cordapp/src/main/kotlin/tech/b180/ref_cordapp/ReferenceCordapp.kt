package tech.b180.ref_cordapp

import net.corda.core.contracts.*
import net.corda.core.flows.*
import net.corda.core.identity.AbstractParty
import net.corda.core.identity.Party
import net.corda.core.transactions.LedgerTransaction
import net.corda.core.transactions.TransactionBuilder

@BelongsToContract(TrivialContract::class)
data class SimpleLinearState(
    val participant: Party,
    override val linearId: UniqueIdentifier) : LinearState {

  override val participants: List<AbstractParty>
    get() = listOf(participant)
}

class TrivialContract : Contract {
  override fun verify(tx: LedgerTransaction) {
  }

  interface Commands : CommandData {
    class RecordState : Commands
  }
}

@InitiatingFlow
@StartableByRPC
@StartableByService
@Suppress("UNUSED")
open class SimpleFlow(
    private val externalId: String
) : FlowLogic<SimpleFlowResult>() {

  override fun call() : SimpleFlowResult {
    val builder = TransactionBuilder(notary = serviceHub.networkMapCache.notaryIdentities.first())
    builder.addOutputState(SimpleLinearState(ourIdentity, UniqueIdentifier(externalId)))
    builder.addCommand(TrivialContract.Commands.RecordState(), ourIdentity.owningKey)

    val tx = serviceHub.signInitialTransaction(builder, ourIdentity.owningKey)

    val stx = subFlow(FinalityFlow(tx, emptySet<FlowSession>()))

    return SimpleFlowResult(stx.tx.outRef<SimpleLinearState>(0))
  }
}

data class SimpleFlowResult(
    val output: StateAndRef<SimpleLinearState>
)