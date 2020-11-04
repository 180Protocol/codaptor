package tech.b180.cordaptor.cache

import net.corda.core.flows.FlowLogic
import tech.b180.cordaptor.corda.*
import tech.b180.cordaptor.kernel.CordaptorComponent
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import kotlin.reflect.KClass

/**
 * Caching decorator for the underlying [CordaNodeState] implementation.
 */
class CachedNodeState(
    private val delegate: CordaNodeStateInner,
    private val settings: Settings
) : CordaNodeState by delegate, CordaFlowSnapshotsCache, CordaptorComponent {

  private val trackers = ConcurrentHashMap<String, FlowTracker<*>>()

  override fun <ReturnType : Any> initiateFlow(
      instruction: CordaFlowInstruction<FlowLogic<ReturnType>>
  ): CordaFlowHandle<ReturnType> {

    return delegate.initiateFlow(instruction).also { handle ->
      getFlowTracker(handle.flowClass).addFlow(handle)
    }
  }

  override fun <ReturnType : Any> getFlowSnapshot(
      flowClass: KClass<out FlowLogic<ReturnType>>,
      flowRunId: UUID
  ): CordaFlowSnapshot<ReturnType>? {

    return getFlowTracker(flowClass).getFlowSnapshot(flowRunId)
  }

  @Suppress("UNCHECKED_CAST")
  fun <ReturnType: Any> getFlowTracker(flowClass: KClass<out FlowLogic<ReturnType>>): FlowTracker<ReturnType> {
    return trackers.getOrPut(flowClass.qualifiedName) {
      FlowTracker(flowClass, settings)
    } as FlowTracker<ReturnType>
  }
}
