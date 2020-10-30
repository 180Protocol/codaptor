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
import org.eclipse.jetty.client.HttpClient
import org.junit.Test
import tech.b180.cordaptor.kernel.Container
import tech.b180.cordaptor.kernel.TypesafeConfig
import javax.json.JsonValue
import javax.servlet.http.HttpServletResponse
import kotlin.test.assertEquals

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

    val containerInstance = Container(TypesafeConfig.loadDefault().withOverrides(ConfigValueFactory.fromMap(
        mapOf(
            "rpcClient" to mapOf(
                "cordappDir" to handle.baseDirectory.toFile().resolve("cordapps").absolutePath,
                "nodeAddress" to address.host + ":" + address.port
            )
        ),
        StandaloneBundleTest::class.simpleName
    ).toConfig()))
    containerInstance.initialize();

    val suite = CordaptorAPITestSuite(
        baseUrl = "http://localhost:8500",
        nodeName = NODE_NAME
    )

    suite.runTests()
  }

  private fun testNodeInfoRequest(client: HttpClient) {
    val response = client.GET("http://localhost:8500/node/info")
    assertEquals(HttpServletResponse.SC_OK, response.status)
    assertEquals("application/json", response.mediaType)

    val nodeInfo = response.contentAsString.asJsonObject()
    assertEquals("localhost".asJsonValue(), nodeInfo.getValue("/addresses/0/host"))
    assertEquals(NODE_NAME.asJsonValue(), nodeInfo.getValue("/legalIdentitiesAndCerts/0/party/name"))
    assertEquals(7.asJsonValue(), nodeInfo.getValue("/platformVersion"))
    assertEquals(JsonValue.ValueType.NUMBER, nodeInfo.getValue("/serial").valueType)
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
