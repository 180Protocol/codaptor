package tech.b180.cordaptor.reference

import net.corda.core.contracts.*
import net.corda.core.flows.FinalityFlow
import net.corda.core.flows.FlowLogic
import net.corda.core.flows.InitiatingFlow
import net.corda.core.flows.StartableByRPC
import net.corda.core.identity.AbstractParty
import net.corda.core.node.StatesToRecord
import net.corda.core.transactions.LedgerTransaction
import net.corda.core.transactions.SignedTransaction
import net.corda.core.transactions.TransactionBuilder
import java.util.*

@BelongsToContract(SimpleContract::class)
data class SimpleState(
    val id : UUID,
    override val linearId: UniqueIdentifier,
    override val participants: List<AbstractParty>) : LinearState {

}

class SimpleContract : Contract {
  override fun verify(tx: LedgerTransaction) {
  }

  interface Commands : CommandData {
    class SimpleCommand : Commands {

    }
  }
}

@InitiatingFlow
@StartableByRPC
@Suppress("UNUSED")
open class SimpleFlow(
    private val initialState: SimpleState
) : FlowLogic<SignedTransaction>() {

  override fun call() : SignedTransaction {
    val builder = TransactionBuilder()
    builder.addOutputState(initialState)
    builder.addCommand(SimpleContract.Commands.SimpleCommand())

    val tx = serviceHub.signInitialTransaction(builder, ourIdentity.owningKey)
    serviceHub.recordTransactions(true, tx)

    return tx
  }
}
