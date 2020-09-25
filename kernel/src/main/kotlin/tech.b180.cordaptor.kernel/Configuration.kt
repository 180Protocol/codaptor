package tech.b180.cordaptor.kernel

import org.koin.core.scope.Scope
import java.net.InetSocketAddress

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

fun Scope.getHostAndPortProperty(name: String): HostAndPort {
  val ( hostPart, portPart ) = getTokenizedProperty(name, ":")
  return HostAndPort(hostPart, Integer.parseInt(portPart))
}