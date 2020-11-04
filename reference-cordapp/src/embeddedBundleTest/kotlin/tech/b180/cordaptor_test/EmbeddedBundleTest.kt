package tech.b180.cordaptor_test

import com.typesafe.config.ConfigValueFactory
import net.corda.core.identity.CordaX500Name
import net.corda.core.utilities.getOrThrow
import net.corda.core.utilities.loggerFor
import net.corda.node.services.Permissions
import net.corda.testing.driver.*
import net.corda.testing.node.NotarySpec
import net.corda.testing.node.TestCordapp
import net.corda.testing.node.User
import kotlin.test.Test

const val NODE_NAME = "O=Bank, L=London, C=GB"

class EmbeddedBundleTest {

  companion object {
    private val logger = loggerFor<EmbeddedBundleTest>()
  }

  @Test
  fun testRestAPI() = withDriver {
    val handle = startNode(CordaX500Name.parse(NODE_NAME))

    logger.info("Started node $handle")

    val suite = CordaptorAPITestSuite(
        baseUrl = "http://127.0.0.1:8500",
        nodeName = NODE_NAME,
        localCacheEnabled = true
    )

    suite.runTests()
  }

  private fun DriverDSL.startNode(name: CordaX500Name): NodeHandle {
    return startNode(
        defaultParameters = NodeParameters(
            providedName = name,
            additionalCordapps = listOf(
                TestCordapp.findCordapp("tech.b180.cordaptor").withConfig(
                    mapOf(
                        // disabling snapshots cache for one flow class to test the eviction
                        "localCache" to mapOf(
                            "flowSnapshots" to mapOf(
                                "default" to mapOf(
                                    "enabled" to false
                                ),
                                "SimpleFlow" to mapOf(
                                    "enabled" to true
                                )
                            )
                        )
                    )
                ),
                TestCordapp.findCordapp("tech.b180.ref_cordapp")
            ),
            rpcUsers = listOf(
                User(
                    username = "client",
                    password = "test",
                    permissions = setOf(Permissions.all()))
            )
        )
    ).getOrThrow()
  }
}

private fun withDriver(test: DriverDSL.() -> Unit) = driver(
    DriverParameters(isDebug = true, startNodesInProcess = true)
        .withNotarySpecs(listOf(
            NotarySpec(
                validating = false,
                name = CordaX500Name.parse("O=Notary,L=London,C=GB"),
                rpcUsers = emptyList(),
                verifierType = VerifierType.InMemory)))
) {
  test()
}
