package tech.b180.ref_cordapp

import co.paralleluniverse.fibers.Suspendable
import net.corda.core.contracts.*
import net.corda.core.flows.*
import net.corda.core.identity.AbstractParty
import net.corda.core.identity.Party
import net.corda.core.transactions.LedgerTransaction
import net.corda.core.transactions.TransactionBuilder
import net.corda.core.utilities.ProgressTracker
import net.corda.core.utilities.loggerFor
import tech.b180.ref_cordapp.DelayedProgressFlow.Companion.SLEEPING
import tech.b180.ref_cordapp.DelayedProgressFlow.Companion.WORKING
import java.time.Duration

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

  companion object {
    private val flowLogger = loggerFor<SimpleFlow>()
  }

  override fun call() : SimpleFlowResult {
    flowLogger.debug("Run id={}: Starting with externalId={}", runId, externalId)

    val builder = TransactionBuilder(notary = serviceHub.networkMapCache.notaryIdentities.first())
    builder.addOutputState(SimpleLinearState(ourIdentity, UniqueIdentifier(externalId)))
    builder.addCommand(TrivialContract.Commands.RecordState(), ourIdentity.owningKey)

    val tx = serviceHub.signInitialTransaction(builder, ourIdentity.owningKey)

    val stx = subFlow(FinalityFlow(tx, emptySet<FlowSession>()))

    flowLogger.debug("Run id={}: Finished", runId)
    return SimpleFlowResult(stx.tx.outRef(0))
  }
}

@InitiatingFlow
@StartableByRPC
@StartableByService
@Suppress("UNUSED")
open class DelayedProgressFlow(
    private val externalId: String,

    /** The flow will spend this number of seconds in [SLEEPING] state
     * before proceeding to [WORKING] state */
    private val delay: Long
) : FlowLogic<SimpleFlowResult>() {

  companion object {
    private val flowLogger = loggerFor<DelayedProgressFlow>()

    private object SLEEPING : ProgressTracker.Step("Sleeping")
    private object WORKING : ProgressTracker.Step("Working")
    private object COMPLETING : ProgressTracker.Step("Completing") {
      override fun childProgressTracker() = FinalityFlow.tracker()
    }

    private fun tracker() = ProgressTracker(SLEEPING, WORKING, COMPLETING)
  }

  override val progressTracker = tracker()

  @Suspendable
  override fun call() : SimpleFlowResult {
    flowLogger.info("Run id={}: Starting with externalId={}, delay={}", runId, externalId, delay)
    progressTracker.currentStep = SLEEPING
    sleep(Duration.ofSeconds(delay))
    flowLogger.info("Run id={}: Delay completed", runId)

    progressTracker.currentStep = WORKING
    val builder = TransactionBuilder(notary = serviceHub.networkMapCache.notaryIdentities.first())
    builder.addOutputState(SimpleLinearState(ourIdentity, UniqueIdentifier(externalId)))
    builder.addCommand(TrivialContract.Commands.RecordState(), ourIdentity.owningKey)

    val tx = serviceHub.signInitialTransaction(builder, ourIdentity.owningKey)

    flowLogger.info("Run id={}: Starting finality flow", runId)
    progressTracker.currentStep = COMPLETING
    val stx = subFlow(FinalityFlow(tx, emptySet<FlowSession>(), progressTracker = COMPLETING.childProgressTracker()))

    flowLogger.info("Run id={}: Completed", runId)
    progressTracker.currentStep = ProgressTracker.DONE
    return SimpleFlowResult(stx.tx.outRef(0))
  }
}

data class SimpleFlowResult(
    val output: StateAndRef<SimpleLinearState>
)