package tech.b180.ref_cordapp

import net.corda.core.contracts.*
import net.corda.core.identity.CordaX500Name
import net.corda.core.internal.declaredField
import net.corda.core.node.services.vault.CriteriaExpression
import net.corda.core.node.services.vault.QueryCriteria
import net.corda.core.node.services.vault.builder
import net.corda.core.schemas.*
import net.corda.finance.USD
import net.corda.node.services.api.SchemaService
import net.corda.node.services.vault.VaultSchemaV1
import net.corda.testing.node.MockNetwork
import net.corda.testing.node.MockNetworkParameters
import net.corda.testing.node.MockNodeParameters
import net.corda.testing.node.TestCordapp
import org.junit.After
import org.junit.Before
import org.junit.Test
import kotlin.reflect.KProperty1
import kotlin.reflect.full.declaredMemberProperties
import kotlin.test.assertEquals

class SchemaServiceTest {
    private val network = MockNetwork(
        MockNetworkParameters(
            cordappsForAllNodes = listOf(
                TestCordapp.findCordapp("tech.b180.ref_cordapp")
            )
        )
    )

    private val node = network.createUnstartedNode(MockNodeParameters(legalName = CordaX500Name.parse("O=Org,L=London,C=GB")))

    @Before
    fun startNetwork() {
        node.start()

        network.runNetwork()
    }

    @After
    fun stopNetwork() {
        network.stopNodes()
    }


    @Test
    fun `test NodeSchemaService`() {
        val simpleState = SimpleLinearState(
            node.started.services.myInfo.identityFromX500Name(CordaX500Name.parse("O=Org,L=London,C=GB")),
            UniqueIdentifier()
        )

        val x = NewSchemaService().selectSchemas(simpleState)
        x.forEach { mappedSchema ->
            println("MappedSchema Name: ${mappedSchema.name}")
            println(mappedSchema.mappedTypes.forEach{ clazz ->
                println("Class name: ${clazz.name}")
                println("Constructors")
                clazz.constructors.forEach { constructor ->
                    println("Constructor Name: ${constructor.name}")
                    println("Parameters")
                    constructor.parameters.forEach {
                        println("Parameter name: ${it.name} Parameter type: ${it.type}")
                    }
                }
            })
            println("\n")
        }
    }

    @Test
    fun `test NodeSchemaService QueryableState`() {
        val complexState = ComplexState(
            node.started.services.myInfo.identityFromX500Name(CordaX500Name.parse("O=Org,L=London,C=GB")),
            "ABC",
            123,
            Amount(100, USD)
        )

        val x = NewSchemaService().selectSchemas(complexState)
        x.forEach { mappedSchema ->
            println(mappedSchema.name)
            println(mappedSchema.mappedTypes.forEach{ clazz ->
                println(clazz.name)
                println("Constructors")
                clazz.constructors.forEach { constructor ->
                    println(constructor.name)
                    println("Parameters")
                    constructor.parameters.forEach {
                        println("Parameter name: ${it.name} Parameter type: ${it.type}")
                    }
                }
            })
        }
    }

    @Test
    fun `test NodeSchemaService CompoundState`() {
        val compoundState = CompoundState(
            node.started.services.myInfo.identityFromX500Name(CordaX500Name.parse("O=Org,L=London,C=GB")),
            "ABC",
            123,
            Amount(100, USD),
            UniqueIdentifier()
        )

        val x = NewSchemaService().selectSchemas(compoundState)
        x.forEach { mappedSchema ->
            println(mappedSchema.name)
            println(mappedSchema.mappedTypes.forEach{ clazz ->
                println(clazz.name)
                println("Constructors")
                clazz.constructors.forEach { constructor ->
                    println(constructor.name)
                    println("Parameters")
                    constructor.parameters.forEach {
                        println("Parameter name: ${it.name} Parameter type: ${it.type}")
                    }
                }
            })
        }
    }

    @Test
    fun `test MappedSchema introspection`(){
        val column = "PersistentComplexState.string"

        val persistentStateName = column.split(".").first()
        val persistentStateColumnName = column.split(".").last()

        val persistentStateClass = (ComplexStateSchemaV1.javaClass.kotlin.nestedClasses as List).first {
            it.simpleName.equals(persistentStateName)
        }

        try {
            val columnType =
                persistentStateClass.declaredMemberProperties.map { it as KProperty1<out PersistentState, *> }.first {
                    it.name == persistentStateColumnName
                }

            val participant = ComplexStateSchemaV1.PersistentComplexState::string

            assertEquals(participant, columnType)

            //construct a vault custom query criteria using the above columnType
            val equal = builder { columnType.equal("test") }
            val equalCriteria = QueryCriteria.VaultCustomQueryCriteria(equal)

            val equalWStatic = builder { participant.equal("test") }
            val equalStaticCriteria = QueryCriteria.VaultCustomQueryCriteria(equalWStatic)

            //comparing the column class types for column predicate expression constructed by the builder
            assertEquals((equalCriteria.expression as CriteriaExpression.ColumnPredicateExpression<*, *>).column.declaringClass,
                (equalStaticCriteria.expression as CriteriaExpression.ColumnPredicateExpression<*, *>).column.declaringClass)

        }
        catch (e: ClassCastException){
            println("Column Type cannot be retrieved from Persistent State :" + e.printStackTrace())
        }
    }
}

class NewSchemaService: SchemaService {
    override val schemas: Set<MappedSchema>
        get() = TODO("Not yet implemented")

    override fun generateMappedObject(state: ContractState, schema: MappedSchema): PersistentState {
        TODO("Not yet implemented")
    }

    override fun selectSchemas(state: ContractState): Iterable<MappedSchema> {
        val schemas = mutableSetOf<MappedSchema>()
        if (state is QueryableState)
            schemas += state.supportedSchemas()
        if (state is LinearState)
            schemas += VaultSchemaV1   // VaultLinearStates
        if (state is FungibleAsset<*>)
            schemas += VaultSchemaV1   // VaultFungibleAssets
        if (state is FungibleState<*>)
            schemas += VaultSchemaV1   // VaultFungibleStates

        return schemas
    }
}

