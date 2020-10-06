package tech.b180.cordaptor.kernel

import org.koin.core.scope.Scope
import java.net.InetSocketAddress

/**
 * Provides uniform access to configuration settings available during
 * the container bootstrap stage, which may be used to change the way modules are defined.
 */
data class BootstrapSettings(
    val settings: Map<String, String>
) {

  fun getBoolean(name: String, defaultValue: Boolean? = null) = settings[name]?.toBoolean()
      ?: defaultValue
      ?: throw NoSuchElementException(name)
}

/**
 * Container for a parsed configuration option for a network socket address
 */
data class HostAndPort(val hostname: String, val port: Int) {
  val socketAddress : InetSocketAddress
  get() = InetSocketAddress(hostname, port)
}

/**
 * Helper method allowing Koin definitions to request Koin to resolve
 * a property and then treat it as a string with multiple values delimited by a comma.
 */
fun Scope.getTokenizedProperty(name: String, delimiter: String = ","): List<String> {
  return getProperty(name).split(delimiter)
}

/**
 * Helper method allowing Koin definitions to request Koin to resolve
 * a property and then treat it an integer number.
 */
fun Scope.getIntegerProperty(name: String): Int {
  return Integer.parseInt(getProperty(name))
}

/**
 * Helper method allowing Koin definitions to request Koin to resolve
 * a property and then treat it as a boolean value.
 */
fun Scope.getBooleanProperty(name: String): Boolean {
  return getProperty(name).toBoolean()
}

fun Scope.getHostAndPortProperty(name: String): HostAndPort {
  val ( hostPart, portPart ) = getTokenizedProperty(name, ":")
  return HostAndPort(hostPart, Integer.parseInt(portPart))
}