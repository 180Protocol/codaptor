package tech.b180.cordaptor.rpc

import tech.b180.cordaptor.kernel.HostAndPort
import tech.b180.cordaptor.kernel.LifecycleAware

class NodeConnection(private val cordaNodeAddress: HostAndPort) : LifecycleAware {

  override fun initialize() {
  }

  override fun shutdown() {
  }
}