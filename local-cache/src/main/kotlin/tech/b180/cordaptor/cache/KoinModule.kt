package tech.b180.cordaptor.cache

import org.koin.dsl.module
import tech.b180.cordaptor.corda.CordaFlowSnapshotsCache
import tech.b180.cordaptor.corda.CordaNodeState
import tech.b180.cordaptor.kernel.Config
import tech.b180.cordaptor.kernel.ModuleProvider
import java.time.Duration
import kotlin.reflect.KClass

/**
 * Implementation of the microkernel module provider that makes the components
 * of this module available for injection into other modules' components.
 *
 * This class is instantiated by the microkernel at runtime using [java.util.ServiceLoader].
 */
@Suppress("UNUSED")
class LocalCacheModuleProvider : ModuleProvider {
  override val salience = ModuleProvider.INNER_MODULE_SALIENCE + 100

  override val configPath = "localCache"

  override fun provideModule(moduleConfig: Config) = module {
    val settings = Settings(moduleConfig)
    single { settings }

    // the implementation relies on the inner implementation as delegates
    single { CachedNodeState(get(), get()) }

    // specifically export as an instance of snapshots cache interface
    single<CordaFlowSnapshotsCache>(override = true) { get<CachedNodeState>() }

    // overrides for the outward-facing interfaces
    single<CordaNodeState>(override = true) { get<CachedNodeState>() }
  }
}

/**
 * Typesafe wrapper for module's configuration.
 * Flow result cache settings cannot be initialized eagerly, because within Corda node config API
 * does not allow keys to be enumerated.
 */
class Settings private constructor(
    val defaultFlowResultsCacheSettings: FlowResultsCacheSettings,
    private val flowResultsConfig: Config
) {
  constructor(config: Config) : this(
      flowResultsConfig = config.getSubtree("flowSnapshots"),
      defaultFlowResultsCacheSettings = FlowResultsCacheSettings(config.getSubtree("flowSnapshots.default"))
  )

  fun getFlowResultsCacheSettings(flowClass: KClass<*>): FlowResultsCacheSettings =
      findFlowResultsCacheConfig(flowClass)?.let { FlowResultsCacheSettings(it, defaultFlowResultsCacheSettings) }
          ?: defaultFlowResultsCacheSettings

  private fun findFlowResultsCacheConfig(flowClass: KClass<*>): Config? =
      when {
        flowResultsConfig.pathExists(flowClass.qualifiedName!!) -> flowResultsConfig.getSubtree(flowClass.qualifiedName!!)
        flowResultsConfig.pathExists(flowClass.simpleName!!) -> flowResultsConfig.getSubtree(flowClass.simpleName!!)
        else -> null
      }
}

/**
 * Cache configuration for a specific flow class or default fallback configuration.
 */
data class FlowResultsCacheSettings private constructor(
    val enabled: Boolean,
    val expireAfterCompletion: Duration
) {
  constructor(config: Config, defaults: FlowResultsCacheSettings) : this(
      enabled = config.getOptionalBoolean("enabled", defaults.enabled),
      expireAfterCompletion = config.getOptionalDuration("expireAfterCompletion", defaults.expireAfterCompletion)
  )

  constructor(config: Config) : this(
      enabled = config.getBoolean("enabled"),
      expireAfterCompletion = config.getDuration("expireAfterCompletion")
  )
}