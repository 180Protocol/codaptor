package tech.b180.cordaptor.cordapp

import net.corda.core.node.AppServiceHub
import net.corda.core.node.services.CordaService
import net.corda.core.node.services.ServiceLifecycleEvent
import net.corda.core.serialization.SingletonSerializeAsToken
import net.corda.node.services.api.ServiceHubInternal
import net.corda.serialization.internal.AllWhitelist
import net.corda.serialization.internal.amqp.CachingCustomSerializerRegistry
import net.corda.serialization.internal.amqp.DefaultDescriptorBasedSerializerRegistry
import net.corda.serialization.internal.amqp.WhitelistBasedTypeModelConfiguration
import net.corda.serialization.internal.model.ConfigurableLocalTypeModel
import net.corda.serialization.internal.model.LocalTypeModel
import org.koin.dsl.module
import tech.b180.cordaptor.kernel.Container
import tech.b180.cordaptor.kernel.TypesafeConfig

/**
 * Corda service that obtains references to necessary API objects within the node
 * and makes them available for other modules via [NodeServicesLocator] component.
 *
 * This class is never instantiated directly, but instructs Corda to do so with [CordaService] annotation.
 */
@CordaService
@Suppress("UNUSED")
class CordaptorService(private val serviceHub: AppServiceHub) : SingletonSerializeAsToken() {

  // Corda uses typesafe as well, so loading config using default conventions causes weird errors
  private val ourConfig = TypesafeConfig.loadFrom("bundled-reference.conf")

  private val config = CordappConfigWithFallback(
      cordappConfig = serviceHub.getAppContext().config,
      fallback = ourConfig)

  private val container = Container(config) {
    module {
      single<NodeServicesLocator> { NodeServicesLocatorImpl(serviceHub) }
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

    serviceHub.registerUnloadHandler {
      container.shutdown()
    }
  }
}

/**
 * Responsible for providing access to various APIs available within the Corda node.
 */
data class NodeServicesLocatorImpl(
    override val appServiceHub : AppServiceHub
) : NodeServicesLocator {

  override val serviceHubInternal: ServiceHubInternal

  init {
    // AppServiceHubImpl is declared internal, which means we cannot access it directly
    val appServiceHubImplClass =
        Class.forName("net.corda.node.internal.AppServiceHubImpl") as Class<*>

    // AppServiceHubImpl delegates all of the implementation of ServiceHub to a field,
    // which contains an instance of ServiceHubInternalImpl
    val serviceHubField = appServiceHubImplClass.declaredFields.find { it.name == "serviceHub" }!!

    // the field is private, so we need to unlock the access to it first before the reflection call
    serviceHubField.isAccessible = true

    serviceHubInternal = serviceHubField.get(appServiceHub) as ServiceHubInternal
  }

  override val localTypeModel: LocalTypeModel

  init {
    val customSerializerRegistry = CachingCustomSerializerRegistry(DefaultDescriptorBasedSerializerRegistry())
    val typeModelConfiguration = WhitelistBasedTypeModelConfiguration(AllWhitelist, customSerializerRegistry)
    localTypeModel = ConfigurableLocalTypeModel(typeModelConfiguration)
  }
}
