package tech.b180.cordaptor.rest

import org.eclipse.jetty.server.Server
import tech.b180.cordaptor.kernel.HostAndPort
import tech.b180.cordaptor.kernel.LifecycleAware

class JettyServer(private val bindAddress: HostAndPort) : LifecycleAware {

  private val server = Server(bindAddress.socketAddress)

  override fun initialize() {
    server.start()
    println("Jetty server started $server")
  }

  override fun shutdown() {
    server.stop()
  }
}