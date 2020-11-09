package tech.b180.cordaptor.rest

import org.slf4j.LoggerFactory
import tech.b180.cordaptor.kernel.ModuleAPI

/**
 * Collection of methods to interact with Corda operational environment.
 *
 * FIXME this is potentially version-dependent, which would require refactoring
 */
@ModuleAPI(since = "0.1")
class NodeNotifications {

  companion object {

    /** Corda default logging configuration emits this to the console.
     * Using it to advertise Cordaptor API base URL */
    private val basicInfoLogger = LoggerFactory.getLogger("BasicInfo")
  }

  fun emitOperatorMessage(message: String) {
    basicInfoLogger.info(message)
  }
}