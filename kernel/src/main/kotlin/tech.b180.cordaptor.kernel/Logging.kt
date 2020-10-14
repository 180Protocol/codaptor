package tech.b180.cordaptor.kernel

import org.slf4j.Logger
import org.slf4j.LoggerFactory

/**
 * Shorthand for obtaining an instance of slf4j [Logger] for a class.
 */
inline fun <reified T : Any> loggerFor(): Logger = LoggerFactory.getLogger(T::class.java)
