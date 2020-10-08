package tech.b180.ref_cordapp

import net.corda.core.identity.CordaX500Name
import net.corda.core.utilities.getOrThrow
import net.corda.testing.node.MockNetwork
import net.corda.testing.node.MockNetworkParameters
import net.corda.testing.node.MockNodeParameters
import net.corda.testing.node.TestCordapp
import org.junit.After
import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * This test ensures that the reference Cordapp behaves as expected using
 * normal Corda API in order to avoid false positives/negatives in other Cordaptor tests
 */
class ReferenceCordappTest {

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
  fun `can use simple flow`() {
    val f = node.started.startFlow(SimpleFlow(externalId = "TEST"))
    val tx = f.getOrThrow().tx

    val output = tx.outputs.single().data as SimpleLinearState
    assertEquals("TEST", output.externalId)
  }
}