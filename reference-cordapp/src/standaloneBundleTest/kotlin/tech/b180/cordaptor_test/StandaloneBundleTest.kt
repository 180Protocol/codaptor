package tech.b180.cordaptor_test

import net.corda.core.identity.CordaX500Name
import net.corda.core.utilities.getOrThrow
import net.corda.core.utilities.loggerFor
import net.corda.node.services.Permissions
import net.corda.testing.driver.*
import net.corda.testing.node.NotarySpec
import net.corda.testing.node.TestCordapp
import net.corda.testing.node.User
import org.eclipse.jetty.client.HttpClient
import org.junit.Test
import tech.b180.cordaptor.kernel.Container
import tech.b180.cordaptor.kernel.SystemPropertiesBootstrapSettings

const val NODE_NAME = "O=Bank, L=London, C=GB"

class StandaloneBundleTest {

  companion object {
    private val logger = loggerFor<StandaloneBundleTest>()
  }

  @Test
  fun testRestAPI() = withDriver {
    val handle = startNode(CordaX500Name.parse(NODE_NAME))

    logger.info("Started node $handle")

    val address = handle.rpcAddress

    System.setProperty("useLocalCache", "false")

    val containerInstance = Container(SystemPropertiesBootstrapSettings())
    containerInstance.initialize();

    val client = HttpClient()
    client.isFollowRedirects = false

    client.start()

    testNodeInfoRequest(client)
  }

  private fun testNodeInfoRequest(client: HttpClient) {

  }

  private fun DriverDSL.startNode(name: CordaX500Name): NodeHandle {
    return startNode(
        defaultParameters = NodeParameters(
            providedName = name,
            additionalCordapps = listOf(
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
