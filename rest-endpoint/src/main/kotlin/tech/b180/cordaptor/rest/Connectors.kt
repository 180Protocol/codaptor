package tech.b180.cordaptor.rest

import org.eclipse.jetty.server.Server
import org.eclipse.jetty.server.ServerConnector
import org.koin.core.KoinComponent
import org.koin.core.inject
import tech.b180.cordaptor.kernel.HostAndPort

data class JettyConnectorConfiguration(
    val bindAddress: HostAndPort
)

/**
 * Responsible for configuring an HTTP connector for an instance of Jetty server.
 * Depending on the configuration, the connector may be a plain connector or a secure one.
 */
class ConnectorFactory(private val configuration: JettyConnectorConfiguration) : JettyConfigurator {

  override fun configure(server: Server) {
    val connector = ServerConnector(server);

    val addr = configuration.bindAddress.socketAddress;
    connector.host = addr.hostName;
    connector.port = addr.port;
    server.addConnector(connector)
  }

}
