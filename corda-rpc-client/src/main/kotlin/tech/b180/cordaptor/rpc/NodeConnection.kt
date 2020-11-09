package tech.b180.cordaptor.rpc

import net.corda.client.rpc.CordaRPCClient
import net.corda.client.rpc.CordaRPCConnection
import net.corda.core.messaging.ClientRpcSslOptions
import net.corda.core.messaging.CordaRPCOps
import net.corda.core.utilities.NetworkHostAndPort
import tech.b180.cordaptor.kernel.*

/**
 * Wraps an instance of [CordaRPCClient] and knows how to configure it using module's settings.
 */
class NodeConnection(
    private val settings: Settings
) : LifecycleAware, CordaptorComponent {

  private lateinit var rpcConnection: CordaRPCConnection

  val rpcProxy: CordaRPCOps
    get() = rpcConnection.proxy

  override fun onInitialize() {
    val client = CordaRPCClient(
        hostAndPort = settings.nodeAddress.toCordaNetworkHostAndPort(),
        configuration = settings.rpcClientConfiguration,
        sslConfiguration = settings.rpcSSLSettings?.let { sslSettings ->
          // this is not ideal as password will be kept as an immutable string in JVM memory
          // potentially for a long time, but we have no choice here as Corda RPC API requires strings
          useSecret(sslSettings.trustStorePassword) { secret ->
            ClientRpcSslOptions(sslSettings.trustStorePath, String(secret), sslSettings.trustStoreProvider)
          }
        }
    )
    rpcConnection = useSecrets(settings.rpcUsername, settings.rpcPassword) { user, password ->
      // this is not ideal as password will be kept as an immutable string in JVM memory
      // potentially for a long time, but we have no choice here as Corda RPC API requires strings
      client.start(username = String(user), password = String(password))
    }
  }

  override fun onShutdown() {
    rpcConnection.notifyServerAndClose()
  }
}

private fun HostAndPort.toCordaNetworkHostAndPort(): NetworkHostAndPort
    = NetworkHostAndPort(hostname, port)