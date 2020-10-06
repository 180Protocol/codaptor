package tech.b180.cordaptor_test

import net.corda.core.identity.CordaX500Name
import net.corda.core.utilities.getOrThrow
import net.corda.node.services.Permissions
import net.corda.testing.driver.*
import net.corda.testing.node.NotarySpec
import net.corda.testing.node.TestCordapp
import net.corda.testing.node.User
import kotlin.test.Test

class EmbeddedBundleTest {

  @Test
  fun `can access node info`() = withDriver {
    val handle = startNode(CordaX500Name.parse("O=Bank,L=London,C=GB"))
  }

  private fun DriverDSL.startNode(name: CordaX500Name): NodeHandle {
    return startNode(
        providedName = name,
        rpcUsers = listOf(
            User(
                username = "client",
                password = "test",
                permissions = setOf(Permissions.all()))
        )
    ).getOrThrow()
  }
}

private fun withDriver(test: DriverDSL.() -> Unit) = driver(
    DriverParameters(isDebug = true, startNodesInProcess = true)
        .withCordappsForAllNodes(listOf(
            TestCordapp.findCordapp("tech.b180.cordaptor")))
        .withNotarySpecs(listOf(
            NotarySpec(
                validating = false,
                name = CordaX500Name.parse("O=Notary,L=London,C=GB"),
                rpcUsers = emptyList(),
                verifierType = VerifierType.InMemory)))
) {
  test()
}
