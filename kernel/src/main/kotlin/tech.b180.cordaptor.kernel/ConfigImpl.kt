package tech.b180.cordaptor.kernel

import com.typesafe.config.ConfigFactory
import com.typesafe.config.ConfigResolveOptions
import java.time.Duration
import com.typesafe.config.Config as _Config

/**
 * Implementation of the microkernel's [Config] interface that wraps typesafe-config instance.
 * Note that for a typical module there is no need to create instances of this class,
 * unless the module is somehow involved in bootstrapping the container.
 */
@ModuleAPI(since = "0.1")
class TypesafeConfig private constructor(private val config: _Config) : Config {
  companion object {

    /**
     * Loads root configuration object using Cordaptor's standard conventions.
     * Each module should have it's own module-reference.conf file at the root of the classpath.
     * This is introduced because Corda has it's own reference.conf, which causes a clash
     */
    fun loadDefault(): TypesafeConfig {
      val reference = loadFrom("module-reference.conf")
      return TypesafeConfig(ConfigFactory
          .defaultOverrides()
          .withFallback(ConfigFactory.defaultApplication())
          .withFallback(reference.config)
          .resolve(ConfigResolveOptions.defaults().setAllowUnresolved(false)))
    }

    /**
     * Loads root configuration object using given resource name.
     */
    fun loadFrom(resourceName: String) = TypesafeConfig(
        ConfigFactory.parseResources(resourceName).resolve(
            ConfigResolveOptions.defaults().setAllowUnresolved(false)
        )
    )

    fun empty() = TypesafeConfig(
        ConfigFactory.parseMap(emptyMap())
    )

    fun fromMap(map: Map<String, Any>) = TypesafeConfig(
        ConfigFactory.parseMap(map)
    )
  }

  override fun pathExists(path: ConfigPath): Boolean {
    return config.hasPath(path)
  }

  override fun getSubtree(path: ConfigPath): Config {
    return TypesafeConfig(config.getConfig(path))
  }

  override fun getString(path: ConfigPath): String {
    return config.getString(path)
  }

  override fun getStringsList(path: ConfigPath): List<String> {
    return config.getStringList(path)
  }

  override fun getInt(path: ConfigPath): Int {
    return config.getInt(path)
  }

  override fun getDouble(path: ConfigPath): Double {
    return config.getDouble(path)
  }

  override fun getBytesSize(path: ConfigPath): Long {
    return config.getBytes(path)
  }

  override fun getDuration(path: ConfigPath): Duration {
    return config.getDuration(path)
  }

  override fun getLong(path: ConfigPath): Long {
    return config.getLong(path)
  }

  override fun getBoolean(path: ConfigPath): Boolean {
    return config.getBoolean(path)
  }

  /**
   * This method is intended to be used in integration tests programmatically instantiating the kernel.
   */
  fun withOverrides(newConfig: _Config): TypesafeConfig {
    return TypesafeConfig(newConfig.withFallback(config))
  }
}
