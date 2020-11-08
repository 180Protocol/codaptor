package tech.b180.cordaptor.rest

import tech.b180.cordaptor.kernel.Config
import tech.b180.cordaptor.kernel.HostAndPort
import tech.b180.cordaptor.kernel.ModuleAPI
import tech.b180.cordaptor.kernel.getHostAndPort
import java.time.Duration

/**
 * Utility allowing public URLs for API endpoints to be obtained
 * in a way specific to the configuration of the server.
 */
@ModuleAPI
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
    val maxVaultQueryPageSize: Int
) {
  constructor(ourConfig: Config) : this(
      isOpenAPISpecificationEnabled = ourConfig.getBoolean("spec.enabled"),
      isSwaggerUIEnabled = ourConfig.getBoolean("swaggerUI.enabled"),
      isFlowSnapshotsEndpointEnabled = ourConfig.getBoolean("flowSnapshots.enabled"),
      maxFlowInitiationTimeout = ourConfig.getDuration("flowInitiation.maxTimeout"),
      maxVaultQueryPageSize = ourConfig.getInt("vaultQueries.maxPageSize")
  )
}

/**
 * Wrapper for HTTP connector settings and utility functions helping to construct absolute URLs.
 */
data class WebServerSettings(
    val bindAddress: HostAndPort,
    val secureTransportSettings: SecureTransportSettings,
    val ioThreads: Int,
    val workerThreads: Int
) : URLBuilder {
  constructor(serverConfig: Config) : this(
      bindAddress = serverConfig.getHostAndPort("listenAddress"),
      secureTransportSettings = SecureTransportSettings(serverConfig.getSubtree("tls")),
      ioThreads = serverConfig.getInt("ioThreads"),
      workerThreads = serverConfig.getInt("workerThreads")
  )

  override fun toAbsoluteUrl(contextPath: String) = baseUrl + contextPath

  override val baseUrl = "${if (isSecure) "https" else "http"}://${bindAddress.hostname}:${bindAddress.port}"

  val isSecure: Boolean get() = secureTransportSettings.enabled
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