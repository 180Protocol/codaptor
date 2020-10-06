package tech.b180.ref_cordapp

import net.corda.core.identity.CordaX500Name
import net.corda.core.utilities.getOrThrow
import net.corda.node.internal.cordapp.JarScanningCordappLoader
import net.corda.node.services.Permissions.Companion.all
import net.corda.serialization.internal.AllWhitelist
import net.corda.serialization.internal.amqp.SerializerFactoryBuilder
import net.corda.serialization.internal.carpenter.ClassCarpenterImpl
import net.corda.testing.driver.*
import net.corda.testing.node.NotarySpec
import net.corda.testing.node.User
import java.net.URLClassLoader
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class CordaRpcTypeDiscoveryTest {

  @Test
  fun `can discover cordapps via RPC`() = withDriver {
    val handle = startNode(CordaX500Name.parse("O=Bank,L=London,C=GB"))
    assertTrue(handle.rpc.registeredFlows().contains("tech.b180.ref_cordapp.SimpleFlow"))
    assertEquals(1, handle.rpc.nodeDiagnosticInfo().cordapps.size)

    val loader = JarScanningCordappLoader
        .fromDirectories(cordappDirs = listOf(handle.baseDirectory.resolve("cordapps")))

    assertEquals(1, loader.cordapps.size)

    val factory = SerializerFactoryBuilder.build(whitelist = AllWhitelist, classCarpenter = ClassCarpenterImpl(AllWhitelist))
    val typeInfo = factory.getTypeInformation(SimpleLinearState::class.java)

    assertNotNull(typeInfo, "Can access local type information")

  }

  private fun DriverDSL.startNode(name: CordaX500Name): NodeHandle {
    return startNode(
        providedName = name,
        rpcUsers = listOf(
            User(
                username = "client",
                password = "test",
                permissions = setOf(all()))
        )
    ).getOrThrow()
  }
}

// Runs a test inside the Driver DSL, which provides useful functions for starting nodes, etc.
private fun withDriver(test: DriverDSL.() -> Unit) = driver(
    DriverParameters(isDebug = true, startNodesInProcess = true)
        .withUseTestClock(true)
        .withNotarySpecs(listOf(
            NotarySpec(
                validating = false,
                name = CordaX500Name.parse("O=Notary,L=London,C=GB"),
                rpcUsers = emptyList(),
                verifierType = VerifierType.InMemory)))
) {
  test()
}
