package tech.b180.cordaptor.rest

import org.eclipse.jetty.server.Server
import org.eclipse.jetty.server.ServerConnector
import org.eclipse.jetty.util.ssl.SslContextFactory
import tech.b180.cordaptor.kernel.HostAndPort

data class JettyConnectorConfiguration(
    val bindAddress: HostAndPort,
    val secureEndpointSettings: SecureEndpointSettings
) {

  fun toAbsoluteUrl(contextPath: String) = baseUrl + contextPath

  val isSecure: Boolean get() = secureEndpointSettings.enabled

  val baseUrl = "${if (isSecure) "https" else "http"}://${bindAddress.hostname}:${bindAddress.port}"
}

/**
 * Responsible for configuring an HTTP connector for an instance of Jetty server.
 * Depending on the configuration, the connector may be a plain connector or a secure one.
 */
class ConnectorFactory(
    private val configuration: JettyConnectorConfiguration,
    private val notifications: NodeNotifications
) : JettyConfigurator {

  override fun beforeServerStarted(server: Server) {
    val connector = if (configuration.isSecure) {
      val factory = SslContextFactory.Server()
      configureSslContextFactory(factory, configuration.secureEndpointSettings)
      ServerConnector(server, factory)
    } else {
      ServerConnector(server)
    }

    val addr = configuration.bindAddress.socketAddress
    connector.host = addr.hostName
    connector.port = addr.port
    server.addConnector(connector)
  }

  private fun configureSslContextFactory(factory: SslContextFactory.Server, settings: SecureEndpointSettings) {
    settings.getOptionalBoolean("trustAll")?.let { factory.isTrustAll = it }
    settings.getOptionalString("provider")?.let { factory.provider = it }
    settings.getOptionalStringsList("excludeProtocols")?.let { factory.setExcludeProtocols(*it.toTypedArray()) }
    settings.getOptionalStringsList("includeProtocols")?.let { factory.setIncludeProtocols(*it.toTypedArray()) }
    settings.getOptionalStringsList("excludeCipherSuites")?.let { factory.setExcludeCipherSuites(*it.toTypedArray()) }
    settings.getOptionalStringsList("includeCipherSuites")?.let { factory.setIncludeCipherSuites(*it.toTypedArray()) }
    settings.getOptionalBoolean("useCipherSuitesOrder")?.let { factory.isUseCipherSuitesOrder = it }
    settings.getOptionalString("keyStorePath")?.let { factory.keyStorePath = it }
    settings.getOptionalString("keyStoreProvider")?.let { factory.keyStoreProvider = it }
    settings.getOptionalString("keyStoreType")?.let { factory.keyStoreType = it }
    settings.getOptionalString("keyStorePassword")?.let { factory.setKeyStorePassword(it) }
    settings.getOptionalString("keyManagerPassword")?.let { factory.setKeyManagerPassword(it) }
    settings.getOptionalString("certAlias")?.let { factory.certAlias = it }
    settings.getOptionalString("trustStorePath")?.let { factory.trustStorePath = it }
    settings.getOptionalString("trustStoreProvider")?.let { factory.trustStoreProvider = it }
    settings.getOptionalString("trustStoreType")?.let { factory.trustStoreType = it }
    settings.getOptionalString("trustStorePassword")?.let { factory.setTrustStorePassword(it) }
    settings.getOptionalBoolean("needClientAuth")?.let { factory.needClientAuth = it }
    settings.getOptionalBoolean("wantClientAuth")?.let { factory.wantClientAuth = it }
    settings.getOptionalBoolean("validateCerts")?.let { factory.isValidateCerts = it }
    settings.getOptionalBoolean("validatePeerCerts")?.let { factory.isValidatePeerCerts = it }
    settings.getOptionalString("protocol")?.let { factory.protocol = it }
    settings.getOptionalString("secureRandomAlgorithm")?.let { factory.secureRandomAlgorithm = it }
    settings.getOptionalString("keyManagerFactoryAlgorithm")?.let { factory.keyManagerFactoryAlgorithm = it }
    settings.getOptionalString("trustManagerFactoryAlgorithm")?.let { factory.trustManagerFactoryAlgorithm = it }
    settings.getOptionalBoolean("renegotiationAllowed")?.let { factory.isRenegotiationAllowed = it }
    settings.getOptionalInt("renegotiationLimit")?.let { factory.renegotiationLimit = it }
    settings.getOptionalString("crlPath")?.let { factory.crlPath = it }
    settings.getOptionalInt("maxCertPathLength")?.let { factory.maxCertPathLength = it }
    settings.getOptionalString("endpointIdentificationAlgorithm")?.let { factory.endpointIdentificationAlgorithm = it }
    settings.getOptionalBoolean("enableCRLDP")?.let { factory.isEnableCRLDP = it }
    settings.getOptionalBoolean("enableOCSP")?.let { factory.isEnableOCSP = it }
    settings.getOptionalString("ocspResponderURL")?.let { factory.ocspResponderURL = it }
    settings.getOptionalBoolean("sessionCachingEnabled")?.let { factory.isSessionCachingEnabled = it }
    settings.getOptionalInt("sslSessionCacheSize")?.let { factory.sslSessionCacheSize = it }
    settings.getOptionalDuration("sslSessionTimeout")?.let { factory.sslSessionTimeout = it.seconds.toInt() }
  }

  override fun afterServerStarted(server: Server) {
    notifications.emitOperatorMessage("Cordaptor: base URL for the API is ${configuration.baseUrl}")
  }
}
