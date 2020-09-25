package tech.b180.cordaptor.cordapp

import net.corda.core.node.AppServiceHub
import net.corda.core.node.services.CordaService
import net.corda.core.node.services.ServiceLifecycleEvent
import net.corda.core.node.services.ServiceLifecycleObserver
import net.corda.core.serialization.SingletonSerializeAsToken
import org.koin.dsl.module
import tech.b180.cordaptor.kernel.Container
import tech.b180.cordaptor.kernel.LifecycleAware

/**
 * Corda service that obtains references to necessary API objects within the node
 * and makes them available for other modules via [CordaBindings] component.
 *
 * This class is never instantiated directly, but instructs Corda to do so with [CordaService] annotation.
 */
@CordaService
@Suppress("UNUSED")
class CordaptorService(private val serviceHub: AppServiceHub) : SingletonSerializeAsToken() {

  private val container = Container {
    module {
      single { CordaBindings(serviceHub) }
    }
  }

  init {
    serviceHub.register(AppServiceHub.SERVICE_PRIORITY_NORMAL) {
      when (it) {
        ServiceLifecycleEvent.STATE_MACHINE_STARTED -> {
          container.initialize()
        }
      }
    }
  }
}

data class CordaBindings(
    val serviceHub : AppServiceHub
);
