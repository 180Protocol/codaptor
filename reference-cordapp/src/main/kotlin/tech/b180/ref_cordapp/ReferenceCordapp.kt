package tech.b180.ref_cordapp

import co.paralleluniverse.fibers.Suspendable
import net.corda.core.contracts.*
import net.corda.core.flows.*
import net.corda.core.identity.AbstractParty
import net.corda.core.identity.Party
import net.corda.core.schemas.MappedSchema
import net.corda.core.schemas.PersistentState
import net.corda.core.schemas.QueryableState
import net.corda.core.serialization.CordaSerializable
import net.corda.core.transactions.LedgerTransaction
import net.corda.core.transactions.TransactionBuilder
import net.corda.core.utilities.ProgressTracker
import net.corda.core.utilities.loggerFor
import tech.b180.ref_cordapp.DelayedProgressFlow.Companion.SLEEPING
import tech.b180.ref_cordapp.DelayedProgressFlow.Companion.WORKING
import java.time.Duration
import java.util.*
import javax.persistence.*

@BelongsToContract(TrivialContract::class)
data class SimpleLinearState(
    val participant: Party,
    override val linearId: UniqueIdentifier) : LinearState {

  override val participants: List<AbstractParty>
    get() = listOf(participant)
}

object ComplexStateSchema

@Suppress("unused")
object ComplexStateSchemaV1 : MappedSchema(
    schemaFamily = ComplexStateSchema.javaClass,
    version = 1,
    mappedTypes = listOf(
        PersistentComplexState::class.java,
        PersistentComplexStateEntry::class.java
    )
) {

  @Entity
  @Table(name = "complex_states")
  class PersistentComplexState(
      @Column(name = "participant_name", nullable = false)
      val participant: Party,

      @Column(name = "string_value", nullable = false, length = 200)
      val string: String,

      @Column(name = "integer_value", nullable = false)
      val integer: Int,

      @Column(name = "amount_quantity", nullable = false)
      val quantity: Long,

      @OneToMany(targetEntity = PersistentComplexStateEntry::class, mappedBy = "parent", cascade = [CascadeType.REMOVE])
      val nestedEntries: MutableList<PersistentComplexStateEntry>
  ) : PersistentState() {
    constructor(state: ComplexState) : this(participant = state.participant, string = state.string,
        integer = state.integer, quantity = state.amount.quantity,
        nestedEntries = mutableListOf()) {

      state.nestedEntries.mapTo(nestedEntries) { PersistentComplexStateEntry(this, it) }
    }
  }

  @Entity
  @Table(name = "complex_state_entries")
  class PersistentComplexStateEntry(
      @ManyToOne(optional = false)
      val parent: PersistentComplexState,

      @Column(name = "string_value", nullable = false, length = 200)
      val string: String,

      @Column(name = "integer_value", nullable = false)
      val integer: Int,

      @Column(name = "amount_quantity", nullable = false)
      val quantity: Long

  ) : PersistentState() {
    constructor(parent: PersistentComplexState, entry: ComplexState.Entry) : this(
        parent = parent, string = entry.string, integer = entry.integer,
        quantity = entry.amount.quantity)
  }
}

@BelongsToContract(TrivialContract::class)
data class ComplexState(
    val participant: Party,
    val string: String,
    val integer: Int,
    val amount: Amount<Currency>,
    val nestedEntries: List<Entry> = emptyList()
) : QueryableState {

  override val participants: List<AbstractParty> get() = listOf(participant)

  override fun supportedSchemas(): Iterable<MappedSchema> = listOf(ComplexStateSchemaV1)

  override fun generateMappedObject(schema: MappedSchema) = ComplexStateSchemaV1.PersistentComplexState(this)

  data class Entry(
      val string: String,
      val integer: Int,
      val amount: Amount<Currency>
  )
}

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

// FIXME serializer cannot correctly generate flow result type schema for FlowLogic<StateAndRef<ComplexState>>
@CordaSerializable
data class ComplexFlowResult(
    val output: StateAndRef<SimpleLinearState>
)