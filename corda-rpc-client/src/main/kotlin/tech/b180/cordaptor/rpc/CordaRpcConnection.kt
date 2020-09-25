package tech.b180.cordaptor.rpc

import org.koin.core.KoinComponent
import tech.b180.cordaptor.kernel.HostAndPort
import tech.b180.cordaptor.kernel.LifecycleAware

class CordaRpcConnection(private val cordaNodeAddress: HostAndPort) : LifecycleAware, KoinComponent {

  override fun initialize() {
  }

  override fun shutdown() {
  }
}