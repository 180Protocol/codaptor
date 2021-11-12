package tech.b180.cordaptor.rest

import tech.b180.cordaptor.kernel.*
import java.io.File
import java.time.Duration
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.TrustManagerFactory

/**
 * Utility allowing public URLs for API endpoints to be obtained
 * in a way specific to the configuration of the server.
 */
@ModuleAPI(since = "0.1")
interface URLBuilder {
  val baseUrl: String

  fun toAbsoluteUrl(contextPath: String): String
}

/**
 * Eagerly-initialized typesafe wrapper for top-level module's configuration.
 */
data class Settings(
    val isOpenAPISpecificationEnabled: Boolean,
    val isSwaggerUIEnabled: Boolean,
    val isFlowSnapshotsEndpointEnabled: Boolean,
    val maxFlowInitiationTimeout: Duration,
    val maxVaultQueryPageSize: Int,
    val isNodeAttachmentEndpointEnabled: Boolean
) {
  constructor(ourConfig: Config) : this(
      isOpenAPISpecificationEnabled = ourConfig.getBoolean("spec.enabled"),
      isSwaggerUIEnabled = ourConfig.getBoolean("swaggerUI.enabled"),
      isFlowSnapshotsEndpointEnabled = ourConfig.getBoolean("flowSnapshots.enabled"),
      maxFlowInitiationTimeout = ourConfig.getDuration("flowInitiation.maxTimeout"),
      maxVaultQueryPageSize = ourConfig.getInt("vaultQueries.maxPageSize"),
      isNodeAttachmentEndpointEnabled = ourConfig.getBoolean("nodeAttachment.enabled")
  )
}

/**
 * Wrapper for HTTP connector settings and utility functions helping to construct absolute URLs.
 */
data class WebServerSettings(
    val bindAddress: HostAndPort,
    val secureTransportSettings: SecureTransportSettings,
    val ioThreads: Int,
    val workerThreads: Int,
    val externalAddress: HostAndPort = bindAddress,
    val sslOnExternalAddress: Boolean = secureTransportSettings.enabled
) : URLBuilder {
  constructor(serverConfig: Config) : this(
      bindAddress = serverConfig.getHostAndPort("listenAddress"),
      externalAddress = serverConfig.getHostAndPort("externalAddress"),
      secureTransportSettings = SecureTransportSettings(serverConfig.getSubtree("ssl")),
      sslOnExternalAddress = serverConfig.getBoolean("sslOnExternalAddress"),
      ioThreads = serverConfig.getInt("ioThreads"),
      workerThreads = serverConfig.getInt("workerThreads")
  )

  override fun toAbsoluteUrl(contextPath: String) = baseUrl + contextPath

  override val baseUrl = "${if (sslOnExternalAddress) "https" else "http"}://${externalAddress.hostname}:${externalAddress.port}"

  val isSecure: Boolean get() = secureTransportSettings.enabled
}

/**
 * Wrapper for configuration section that is fed into the listener configuration.
 * We are not eagerly parsing config line by line to reduce the chance of a typo.
 */
data class SecureTransportSettings(
    val enabled: Boolean,
    val sslContextName: String,
    val sslContextProvider: String?,
    val keyManagerFactoryAlgo: String?,
    val keyStoreProvider: String?,
    val keyStoreLocation: File?,
    val keyStorePassword: StringSecret?,
    val keyStoreType: String,
    val trustManagerFactoryAlgo: String?,
    val trustStoreProvider: String?,
    val trustStoreLocation: File?,
    val trustStorePassword: StringSecret?,
    val trustStoreType: String
) {
  constructor(sslConfig: Config) : this(
      enabled = sslConfig.getBoolean("enabled"),
      sslContextName = sslConfig.getOptionalString("sslContextName", "TLS"),
      sslContextProvider = sslConfig.getOptionalString("sslContextProvider"),
      keyManagerFactoryAlgo = sslConfig.getOptionalString("keyManagerFactoryAlgo", KeyManagerFactory.getDefaultAlgorithm()),
      keyStoreProvider = sslConfig.getOptionalString("keyStoreProvider"),
      keyStoreLocation = sslConfig.getOptionalString("keyStoreLocation")?.let { resolveAsFile(it) },
      keyStorePassword = sslConfig.getOptionalStringSecret("keyStorePassword"),
      keyStoreType = sslConfig.getOptionalString("keyStoreType", "JKS"),
      trustManagerFactoryAlgo = sslConfig.getOptionalString("trustManagerFactoryAlgo", TrustManagerFactory.getDefaultAlgorithm()),
      trustStoreProvider = sslConfig.getOptionalString("trustStoreProvider"),
      trustStoreLocation = sslConfig.getOptionalString("trustStoreLocation")?.let { resolveAsFile(it) },
      trustStorePassword = sslConfig.getOptionalStringSecret("trustStorePassword"),
      trustStoreType = sslConfig.getOptionalString("trustStoreType", "JKS")
  )

  init {
    if (enabled) {
      requireNotNull(keyStoreLocation) { "keyStoreLocation is required if SSL is enabled" }
      requireNotNull(keyStorePassword) { "keyStorePassword is required if SSL is enabled" }
      requireNotNull(trustStoreLocation) { "trustStoreLocation is required if SSL is enabled" }
      requireNotNull(trustStorePassword) { "trustStorePassword is required if SSL is enabled" }
    }
  }

  companion object {
    private fun resolveAsFile(location: String): File {
      return File(location).also {
        if (!it.exists()) {
          throw IllegalArgumentException("File does not exist: ${it.absolutePath}")
        }
        if (!it.canRead()) {
          throw IllegalArgumentException("File is not readable: ${it.absolutePath}")
        }
      }
    }
  }
}

/**
 * Wrapper for API endpoint security settings
 */
data class SecuritySettings(
    val securityHandlerName: String
) {
  constructor(securityConfig: Config) : this(
      securityHandlerName = securityConfig.getString("handler")
  )
}
