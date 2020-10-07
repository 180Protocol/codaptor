package tech.b180.cordaptor_test

import net.corda.core.identity.CordaX500Name
import net.corda.core.utilities.getOrThrow
import net.corda.node.services.Permissions
import net.corda.testing.driver.*
import net.corda.testing.node.NotarySpec
import net.corda.testing.node.TestCordapp
import net.corda.testing.node.User
import org.eclipse.jetty.client.HttpClient
import javax.servlet.http.HttpServletResponse
import kotlin.test.Test
import kotlin.test.assertEquals

class EmbeddedBundleTest {

  @Test
  fun `can access node info`() = withDriver {
    val handle = startNode(CordaX500Name.parse("O=Bank,L=London,C=GB"))

    val client = HttpClient()
    client.start()

    val r = client.GET("http://localhost:8500/node/info")
    assertEquals(HttpServletResponse.SC_OK, r.status)
    println(r.contentAsString)
  }

  private fun DriverDSL.startNode(name: CordaX500Name): NodeHandle {
    return startNode(
        defaultParameters = NodeParameters(
            providedName = name,
            additionalCordapps = listOf(TestCordapp.findCordapp("tech.b180.cordaptor").withConfig(
                mapOf("useLocalCache" to false)
            )),
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
