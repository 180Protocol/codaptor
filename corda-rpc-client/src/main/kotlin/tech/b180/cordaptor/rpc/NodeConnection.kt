package tech.b180.cordaptor.rpc

import net.corda.client.rpc.CordaRPCClient
import net.corda.client.rpc.CordaRPCClientConfiguration
import net.corda.client.rpc.CordaRPCConnection
import net.corda.core.messaging.CordaRPCOps
import net.corda.core.messaging.RPCOps
import net.corda.core.utilities.NetworkHostAndPort
import tech.b180.cordaptor.kernel.HostAndPort
import tech.b180.cordaptor.kernel.LifecycleAware

/**
 * Wraps an instance of [CordaRPCClient] and knows how to configure it using module's settings.
 */
class NodeConnection(
    private val settings: Settings
) : LifecycleAware {

  private lateinit var rpcConnection: CordaRPCConnection

  val rpcProxy: CordaRPCOps
    get() = rpcConnection.proxy

  override fun onInitialize() {
    val client = CordaRPCClient(
        hostAndPort = settings.nodeAddress.toCordaNetworkHostAndPort(),
        configuration = settings.rpcClientConfiguration,
        sslConfiguration = settings.rpcSslOptions
    )
    rpcConnection = client.start(username = settings.rpcUsername, password = settings.rpcPassword)
  }

  override fun onShutdown() {
    rpcConnection.notifyServerAndClose()
  }
}

private fun HostAndPort.toCordaNetworkHostAndPort(): NetworkHostAndPort
    = NetworkHostAndPort(hostname, port)