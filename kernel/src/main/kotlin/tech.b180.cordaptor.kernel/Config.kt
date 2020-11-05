package tech.b180.cordaptor.kernel

import java.net.InetSocketAddress
import java.time.Duration

/** Dot-separated sequence of string keys */
typealias ConfigPath = String

/**
 * Provides access to configuration properties styled after and based on typesafe-config
 * and HOCON (see [https://github.com/lightbend/config/blob/master/HOCON.md]).
 *
 * Configuration properties are hierarchically structured and navigated using dot-separated
 * path expressions.
 *
 * Modules will be passed in a subtree of their configuration identified by [ModuleProvider.configPath],
 * but they can access configuration from the outside of that subtree by asking Koin
 * to inject an instance of [Config], which will point to the root of the tree.
 *
 * It is recommended that modules wrap access to configuration in a settings data class,
 * that knows how to initialize itself from a [Config] instance. This way, all configuration
 * issues could be detected at container startup time.
 */
@ModuleAPI
interface Config {

  fun pathExists(path: ConfigPath): Boolean

  fun getSubtree(path: ConfigPath): Config

  fun getString(path: ConfigPath): String

  fun getStringsList(path: ConfigPath): List<String>

  fun getDuration(path: ConfigPath): Duration

  fun getInt(path: ConfigPath): Int

  fun getLong(path: ConfigPath): Long

  fun getDouble(path: ConfigPath): Double

  fun getBytesSize(path: ConfigPath): Long

  fun getBoolean(path: ConfigPath): Boolean

  fun getOptionalString(path: ConfigPath) = if (pathExists(path)) getString(path) else null
  fun getOptionalStringsList(path: ConfigPath) = if (pathExists(path)) getStringsList(path) else null
  fun getOptionalInt(path: ConfigPath) = if (pathExists(path)) getInt(path) else null
  fun getOptionalLong(path: ConfigPath) = if (pathExists(path)) getLong(path) else null
  fun getOptionalBoolean(path: ConfigPath) = if (pathExists(path)) getBoolean(path) else null
  fun getOptionalDuration(path: ConfigPath) = if (pathExists(path)) getDuration(path) else null
  fun getOptionalDouble(path: ConfigPath) = if (pathExists(path)) getDouble(path) else null
  fun getOptionalBytesSize(path: ConfigPath) = if (pathExists(path)) getBytesSize(path) else null

  fun getOptionalString(path: ConfigPath, default: String) = getOptionalString(path) ?: default
  fun getOptionalStringsList(path: ConfigPath, default: List<String>) = if (pathExists(path)) getStringsList(path) else default
  fun getOptionalInt(path: ConfigPath, default: Int) = getOptionalInt(path) ?: default
  fun getOptionalLong(path: ConfigPath, default: Long) = getOptionalLong(path) ?: default
  fun getOptionalBoolean(path: ConfigPath, default: Boolean) = getOptionalBoolean(path) ?: default
  fun getOptionalDuration(path: ConfigPath, default: Duration) = getOptionalDuration(path) ?: default
  fun getOptionalDouble(path: ConfigPath, default: Double) = getOptionalDouble(path) ?: default
  fun getOptionalBytesSize(path: ConfigPath, default: Long) = getOptionalBytesSize(path) ?: default
}

/**
 * Container for a parsed configuration option for a network socket address
 */
data class HostAndPort(val hostname: String, val port: Int) {
  val socketAddress : InetSocketAddress
  get() = InetSocketAddress(hostname, port)
}

/** Utility method for parsing socket address */
fun Config.getHostAndPort(path: ConfigPath): HostAndPort {
  val ( hostPart, portPart ) = getString(path).split(":")
  return HostAndPort(hostPart, Integer.parseInt(portPart))
}
