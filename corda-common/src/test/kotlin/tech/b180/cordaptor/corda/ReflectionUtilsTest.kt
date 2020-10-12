package tech.b180.cordaptor.corda

import net.corda.core.flows.FlowLogic
import net.corda.core.transactions.SignedTransaction
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ReflectionUtilsTest {

  @Test
  fun `test flow result type logic`() {
    assertEquals(SignedTransaction::class, determineFlowResultClass(TestFlow::class))

    assertFailsWith(AssertionError::class) {
      determineFlowResultClass(TestFlowWithoutSpecificType::class)
    }
  }
}

/**
 * This kind of flow class definition is not supported because it is impossible to
 * determine what would be the type of resulting value from running the flow.
 */
class TestFlowWithoutSpecificType<T : Any> : FlowLogic<T>() {
  override fun call(): T {
    TODO("Not meant to be called")
  }
}

/**
 * Supported kind of flow class definition passing in explicit type parameter when subclassing [FlowLogic]
 */
class TestFlow : FlowLogic<SignedTransaction>() {
  override fun call(): SignedTransaction {
    TODO("Not meant to be called")
  }
}