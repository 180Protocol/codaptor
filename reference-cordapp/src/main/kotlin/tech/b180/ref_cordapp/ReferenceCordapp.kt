package tech.b180.ref_cordapp

import net.corda.core.contracts.*
import net.corda.core.flows.*
import net.corda.core.identity.AbstractParty
import net.corda.core.identity.Party
import net.corda.core.transactions.LedgerTransaction
import net.corda.core.transactions.SignedTransaction
import net.corda.core.transactions.TransactionBuilder
import java.util.*

@BelongsToContract(TrivialContract::class)
data class SimpleLinearState(
    val externalId: String,
    val participant: Party) : LinearState {

  override val linearId = UniqueIdentifier(externalId)

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
@Suppress("UNUSED")
open class SimpleFlow(
    private val externalId: String
) : FlowLogic<SignedTransaction>() {

  override fun call() : SignedTransaction {
    val builder = TransactionBuilder()
    builder.addOutputState(SimpleLinearState(externalId, ourIdentity))
    builder.addCommand(TrivialContract.Commands.RecordState())

    val tx = serviceHub.signInitialTransaction(builder, ourIdentity.owningKey)

    subFlow(FinalityFlow(tx, emptySet<FlowSession>()))

    return tx
  }
}
