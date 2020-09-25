package tech.b180.cordaptor.kernel

import org.koin.core.module.Module
import java.util.*

/**
 * To be implemented in each module providing components for microkernel's automated
 * dependency management and configuration property resolution.
 *
 * Microkernel uses [ServiceLoader.load] API for finding all modules in the classpath,
 * so there also must a file called META-INF/services/tech.b180.cordaptor.kernel.ModuleProvider
 * in each module containing the name of a concrete implementation.
 */
interface ModuleProvider {

  /**
   * Returns a Koin module declaration, which may declare types to be made available
   * for other modules and/or dependencies to be resolved.
   */
  fun buildModule(): Module
}

/**
 * This interface is meant to be implemented by Cordaptor components
 * to be notified of the main container lifecycle events, e.g. to acquire
 * and/or gracefully release necessary resources.
 */
interface LifecycleAware {
  fun initialize()

  fun shutdown()
}
