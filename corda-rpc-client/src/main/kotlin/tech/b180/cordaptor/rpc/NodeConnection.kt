package tech.b180.cordaptor.rpc

import net.corda.client.rpc.CordaRPCClient
import net.corda.client.rpc.CordaRPCConnection
import net.corda.client.rpc.RPCException
import net.corda.core.messaging.ClientRpcSslOptions
import net.corda.core.messaging.CordaRPCOps
import net.corda.core.utilities.NetworkHostAndPort
import org.apache.activemq.artemis.api.core.ActiveMQNotConnectedException
import tech.b180.cordaptor.kernel.*
import java.lang.Double.max
import java.time.Duration

/**
 * Allows to access [CordaRPCOps] instance representing Corda RPC link
 * to a remote Corda node.
 *
 * Note that even through this interface is a part of the module API, other modules should
 * exercise caution because this definition will only be available when Cordaptor
 * is deployed as a standalone process.
 */
@ModuleAPI(since = "0.2")
interface CordaRPCOpsLocator {

  val rpcProxy: CordaRPCOps
}

/**
 * Wraps an instance of [CordaRPCClient] and knows how to configure it using module's settings.
 */
class NodeConnection(
    private val settings: Settings
) : LifecycleAware, CordaptorComponent, CordaRPCOpsLocator {

  companion object {
    private val logger = loggerFor<NodeConnection>()
  }

  private lateinit var rpcConnection: CordaRPCConnection

  override val rpcProxy: CordaRPCOps
    get() = rpcConnection.proxy

  override fun onInitialize() {
    logger.info("Trying to connect to Corda node at {} via RPC for the maximum of {}s",
        settings.nodeAddress, settings.rpcClientConfiguration.connectionMaxRetryInterval.seconds)

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
    rpcConnection = startRpcConnection(client, 1, settings.rpcClientConfiguration.connectionRetryInterval)
  }

  /**
   * Method responsible for establishing initial connection to the Corda node via RPC.
   * The implementation of [CordaRPCConnection] contains logic dealing with reconnection if the
   * node goes away, but there is no provision for multiple attempts to establish the initial connection.
   * This method uses parameters of [CordaRPCClientConfiguration] to attempt the initial connection.
   */
  private tailrec fun startRpcConnection(
      client: CordaRPCClient, attemptNo: Int, timeoutOnFailure: Duration
  ): CordaRPCConnection {

    logger.debug("RPC connection attempt {}, timeout on failure: {}s", attemptNo, timeoutOnFailure.seconds)

    val connection = try {
      // this is not ideal as password will be kept as an immutable string in JVM memory
      // potentially for a long time, but we have no choice here as Corda RPC API requires strings
      useSecrets(settings.rpcUsername, settings.rpcPassword) { user, password ->
        client.start(username = String(user), password = String(password))
      }
    } catch (e: RPCException) {
      if (e.cause is ActiveMQNotConnectedException) {
        logger.warn("RPC connection attempt {} failed, cause: {}", attemptNo, e.message)

        if (settings.rpcClientConfiguration.maxReconnectAttempts == -1
            || attemptNo < settings.rpcClientConfiguration.maxReconnectAttempts) {

          Thread.sleep(timeoutOnFailure.seconds)
          null

        } else {
          logger.error("This was the last attempt, unable to establish connection with Corda node")
          throw e
        }
      } else {
        // unexpected error probably due to a misconfiguration, fail fast to avoid futile attempts
        throw e;
      }
    }

    // recur into connection logic
    return connection ?: startRpcConnection(
        client, attemptNo + 1,
        Duration.ofMillis(
            max(timeoutOnFailure.toMillis() * settings.rpcClientConfiguration.connectionRetryIntervalMultiplier,
                settings.rpcClientConfiguration.connectionMaxRetryInterval.toMillis().toDouble()).toLong()))
  }

  override fun onShutdown() {
    rpcConnection.notifyServerAndClose()
  }
}

private fun HostAndPort.toCordaNetworkHostAndPort(): NetworkHostAndPort
    = NetworkHostAndPort(hostname, port)