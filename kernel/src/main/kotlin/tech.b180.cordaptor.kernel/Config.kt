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

  fun getDuration(path: ConfigPath): Duration

  fun getInt(path: ConfigPath): Int

  fun getLong(path: ConfigPath): Long

  fun getDouble(path: ConfigPath): Double

  fun getBytesSize(path: ConfigPath): Long

  fun getBoolean(path: ConfigPath): Boolean

  fun getOptionalString(path: ConfigPath): String? = if (pathExists(path)) getString(path) else null

  fun getOptionalInt(path: ConfigPath): Int? = if (pathExists(path)) getInt(path) else null

  fun getOptionalLong(path: ConfigPath): Long? = if (pathExists(path)) getLong(path) else null

  fun getOptionalBoolean(path: ConfigPath): Boolean? = if (pathExists(path)) getBoolean(path) else null
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
