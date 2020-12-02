package tech.b180.cordaptor.rpc

import net.corda.client.rpc.CordaRPCClientConfiguration
import org.koin.dsl.bind
import org.koin.dsl.binds
import org.koin.dsl.module
import tech.b180.cordaptor.corda.CordaNodeCatalog
import tech.b180.cordaptor.corda.CordaNodeCatalogInner
import tech.b180.cordaptor.corda.CordaNodeState
import tech.b180.cordaptor.corda.CordaNodeStateInner
import tech.b180.cordaptor.kernel.*
import java.io.File
import java.nio.file.Path

/**
 * Implementation of the microkernel module provider that makes the components
 * of this module available for injection into other modules' components.
 *
 * This class is instantiated by the microkernel at runtime using [java.util.ServiceLoader].
 */
@Suppress("UNUSED")
class CordaRpcClientModuleProvider : ModuleProvider {
  override val salience = ModuleProvider.INNER_MODULE_SALIENCE

  override val configPath = "rpcClient"

  override fun provideModule(moduleConfig: Config) = module {
    val settings = Settings(moduleConfig)
    single { settings }

    // RPC connection to the node
    single { NodeConnection(get()) } binds arrayOf(LifecycleAware::class, CordaRPCOpsLocator::class)
    single { get<NodeConnection>().rpcProxy }

    // actual components implementing Corda API access layer
    // these 'inner' definitions may be accessed by other modules to use as delegates
    // when overriding 'outer' definitions, e.g. by the caching layer
    single<CordaNodeCatalogInner> { ClientNodeCatalogImpl() }
    single<CordaNodeStateInner> { ClientNodeStateImpl() }

    // outward-facing definitions for the Corda API access layer components
    // which may be overridden by higher-tier modules augmenting the functionality
    single<CordaNodeCatalog> { get<CordaNodeCatalogInner>() }
    single<CordaNodeState> { get<CordaNodeStateInner>() }

    single { RPCFlowInitiator<Any>() }
  }
}

/**
 * Eagerly-initialized typesafe wrapper for module's configuration.
 * FIXME move RPC credentials management to a secrets score
 */
class Settings private constructor(
    val nodeAddress: HostAndPort,
    val rpcUsername: StringSecret,
    val rpcPassword: StringSecret,
    val cordappDir: Path,
    val rpcClientConfiguration: CordaRPCClientConfiguration,
    val rpcSSLSettings: SSLSettings?
) {
  companion object {
    var logger = loggerFor<Settings>()

    private fun validateCordappDir(dirName: String): Path {
      val dir = File(dirName)
      val absoluteDir = if (dir.isAbsolute) dir else dir.absoluteFile

      logger.debug("CordappDir property value $dirName resolved to absolute path $absoluteDir")

      if (!absoluteDir.exists()) {
        throw IllegalArgumentException("Cordapp directory does not exist: $absoluteDir")
      }
      if (!absoluteDir.isDirectory) {
        throw IllegalArgumentException("Cordapp directory is not a directory: $absoluteDir")
      }
      return absoluteDir.toPath()
    }
  }

  // FIXME add more helpful error messages when RPC connection properties are undefined
  constructor(ourConfig: Config) : this(
      nodeAddress = ourConfig.getHostAndPort("nodeAddress"),
      rpcUsername = ourConfig.getStringSecret("rpcUsername"),
      rpcPassword = ourConfig.getStringSecret("rpcPassword"),
      cordappDir = validateCordappDir(ourConfig.getString("cordappDir")),
      rpcClientConfiguration = ourConfig.getSubtree("clientConfig").let { clientConfig ->
        val conf = CordaRPCClientConfiguration.DEFAULT
        conf.copy(
            connectionMaxRetryInterval = clientConfig.getOptionalDuration("connectionMaxRetryInterval", conf.connectionMaxRetryInterval),
            minimumServerProtocolVersion = clientConfig.getOptionalInt("minimumServerProtocolVersion", conf.minimumServerProtocolVersion),
            trackRpcCallSites = clientConfig.getOptionalBoolean("trackRpcCallSites", conf.trackRpcCallSites),
            reapInterval = clientConfig.getOptionalDuration("reapInterval", conf.reapInterval),
            observationExecutorPoolSize = clientConfig.getOptionalInt("observationExecutorPoolSize", conf.observationExecutorPoolSize),
            connectionRetryInterval = clientConfig.getOptionalDuration("connectionRetryInterval", conf.connectionRetryInterval),
            connectionRetryIntervalMultiplier = clientConfig.getOptionalDouble("connectionRetryIntervalMultiplier", conf.connectionRetryIntervalMultiplier),
            maxReconnectAttempts = clientConfig.getOptionalInt("maxReconnectAttempts", conf.maxReconnectAttempts),
            maxFileSize = clientConfig.getOptionalBytesSize("maxFileSize")?.coerceAtMost(Int.MAX_VALUE.toLong())?.toInt() ?: conf.maxFileSize,
            deduplicationCacheExpiry = clientConfig.getOptionalDuration("deduplicationCacheExpiry", conf.deduplicationCacheExpiry)
        )
      },
      rpcSSLSettings = ourConfig.getSubtree("ssl").let { sslConfig ->
        if (sslConfig.getBoolean("enabled")) {
          SSLSettings(sslConfig)
        } else {
          null
        }
      }
  )

  data class SSLSettings(
      val trustStorePath: Path,
      val trustStorePassword: StringSecret,
      val trustStoreProvider: String
  ) {
    constructor(sslConfig: Config) : this(
        trustStoreProvider = sslConfig.getString("trustStoreProvider"),
        trustStorePassword = sslConfig.getStringSecret("trustStorePassword"),
        trustStorePath = File(sslConfig.getString("trustStorePath")).toPath()
    )
  }
}
