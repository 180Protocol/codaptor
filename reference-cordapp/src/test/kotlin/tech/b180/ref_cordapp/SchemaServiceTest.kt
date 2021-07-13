package tech.b180.ref_cordapp

import net.corda.core.contracts.*
import net.corda.core.identity.CordaX500Name
import net.corda.core.schemas.MappedSchema
import net.corda.core.schemas.PersistentState
import net.corda.core.schemas.QueryableState
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
            emptyList(),
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