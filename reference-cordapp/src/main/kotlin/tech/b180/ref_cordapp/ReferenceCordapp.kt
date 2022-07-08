package tech.b180.ref_cordapp

import co.paralleluniverse.fibers.Suspendable
import net.corda.core.contracts.*
import net.corda.core.flows.*
import net.corda.core.serialization.CordaSerializable
import net.corda.core.transactions.LedgerTransaction
import net.corda.core.transactions.TransactionBuilder
import net.corda.core.utilities.ProgressTracker
import net.corda.core.utilities.loggerFor
import tech.b180.ref_cordapp.DelayedProgressFlow.Companion.SLEEPING
import tech.b180.ref_cordapp.DelayedProgressFlow.Companion.WORKING
import tech.b180.ref_cordapp.state.ComplexState
import tech.b180.ref_cordapp.state.CompoundState
import tech.b180.ref_cordapp.state.SimpleLinearState
import java.io.File
import java.time.Duration
import java.util.*


/** Contract that accepts any transaction */
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
    return SimpleFlowResult(stx.tx.outRef(0),
        Amount.fromDecimal(10.toBigDecimal(), Currency.getInstance("USD")))
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
    return SimpleFlowResult(stx.tx.outRef(0),
        Amount.fromDecimal(10.toBigDecimal(), Currency.getInstance("USD")))
  }
}

@CordaSerializable
data class SimpleFlowResult(
    val output: StateAndRef<SimpleLinearState>,
    val feePaid: Amount<Currency>
)

@InitiatingFlow
@StartableByRPC
@StartableByService
@Suppress("UNUSED")
class IssueComplexStateFlow(
    private val state: ComplexState
) : FlowLogic<ComplexFlowResult>() {

  override fun call(): ComplexFlowResult {
    val builder = TransactionBuilder(notary = serviceHub.networkMapCache.notaryIdentities.first())
    builder.addOutputState(state)
    builder.addCommand(TrivialContract.Commands.RecordState(), ourIdentity.owningKey)

    val pstx = serviceHub.signInitialTransaction(builder, ourIdentity.owningKey)
    val fstx = subFlow(FinalityFlow(pstx, emptySet<FlowSession>()))

    return ComplexFlowResult(fstx.tx.outRef(0))
  }
}

@InitiatingFlow
@StartableByRPC
@StartableByService
@Suppress("UNUSED")
class CreateCompoundStateFlow(
    private val string: String,
    private val integer: Int
) : FlowLogic<CompoundFlowResult>() {

    override fun call(): CompoundFlowResult {
        val compoundState = CompoundState(
            ourIdentity,
            string,
            integer,
            Amount(100, Currency.getInstance("USD")),
            UniqueIdentifier()
        )

        val builder = TransactionBuilder(notary = serviceHub.networkMapCache.notaryIdentities.first())
        builder.addOutputState(compoundState)
        builder.addCommand(TrivialContract.Commands.RecordState(), ourIdentity.owningKey)

        val pstx = serviceHub.signInitialTransaction(builder, ourIdentity.owningKey)
        val fstx = subFlow(FinalityFlow(pstx, emptySet<FlowSession>()))

        return CompoundFlowResult(fstx.tx.outRef(0))
    }
}

// FIXME serializer cannot correctly generate flow result type schema for FlowLogic<StateAndRef<ComplexState>>
@CordaSerializable
data class ComplexFlowResult(
    val output: StateAndRef<SimpleLinearState>
)

// FIXME serializer cannot correctly generate flow result type schema for FlowLogic<StateAndRef<ComplexState>>
@CordaSerializable
data class CompoundFlowResult(
    val output: StateAndRef<CompoundState>
)

@CordaSerializable
data class TestFileFlowResult(
  val output: Boolean
)

@InitiatingFlow
@StartableByRPC
@StartableByService
@Suppress("UNUSED")
class TestFileFlow(
  private val file: ByteArray
) : FlowLogic<TestFileFlowResult>() {

  companion object {
    private val flowLogger = loggerFor<TestFileFlow>()
  }

  override fun call(): TestFileFlowResult {
    return TestFileFlowResult(file.isNotEmpty())
  }
}