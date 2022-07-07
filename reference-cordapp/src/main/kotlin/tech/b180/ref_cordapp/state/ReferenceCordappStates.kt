package tech.b180.ref_cordapp.state

import net.corda.core.contracts.Amount
import net.corda.core.contracts.BelongsToContract
import net.corda.core.contracts.LinearState
import net.corda.core.contracts.UniqueIdentifier
import net.corda.core.identity.AbstractParty
import net.corda.core.identity.Party
import net.corda.core.schemas.MappedSchema
import net.corda.core.schemas.PersistentState
import net.corda.core.schemas.QueryableState
import tech.b180.ref_cordapp.TrivialContract
import java.util.*
import javax.persistence.*

@BelongsToContract(TrivialContract::class)
data class SimpleLinearState(
  val participant: Party,
  override val linearId: UniqueIdentifier
) : LinearState {

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


@BelongsToContract(TrivialContract::class)
class CompoundState(
  val participant: Party,
  val string: String,
  val integer: Int,
  val amount: Amount<Currency>,
  override val linearId: UniqueIdentifier
) : LinearState, QueryableState {
  override val participants = listOf(participant)

  override fun supportedSchemas(): Iterable<MappedSchema> = listOf(CompoundStateSchemaV1)

  override fun generateMappedObject(schema: MappedSchema) = ComplexStateSchemaV1.PersistentComplexState(ComplexState(participant, string, integer, amount))
}

object CompoundStateSchema

@Suppress("unused")
object CompoundStateSchemaV1 : MappedSchema(
  schemaFamily = CompoundStateSchema.javaClass,
  version = 1,
  mappedTypes = listOf(
    PersistentCompoundState::class.java
  )
) {
  @Entity
  @Table(name = "compound_states")
  class PersistentCompoundState(
    @Column(name = "participant_name", nullable = false)
    val participant: Party,

    @Column(name = "string_value", nullable = false, length = 200)
    val string: String,

    @Column(name = "integer_value", nullable = false)
    val integer: Int,

    @Column(name = "amount_quantity", nullable = false)
    val quantity: Long

  ) : PersistentState() {
    constructor(state: CompoundState) : this(participant = state.participant, string = state.string,
      integer = state.integer, quantity = state.amount.quantity)
  }
}
