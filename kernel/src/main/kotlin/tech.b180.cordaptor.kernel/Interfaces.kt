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

  companion object {
    /** [salience] value used for the context module as the point of container instantiation */
    const val CONTEXT_MODULE_SALIENCE = 1000

    /** [salience] value suggested for modules sitting in the immediate proximity with the underlying Corda node */
    const val INNER_MODULE_SALIENCE = 1
  }

  /**
   * Returns a Koin module construct, which may declare types to be made available
   * for other modules and/or dependencies to be resolved.
   *
   * @param settings parameters available during container instantiation
   * that may affect the way module definition is created
   */
  fun provideModule(settings: BootstrapSettings): Module

  /**
   * A number that is used to order module declarations when initializing the container.
   * Higher numbers declared later than lower numbers, and this may be used to override
   * modules declared in earlier declarations.
   *
   * As a rough guideline, actual values used by modules should fall within the range
   * between [INNER_MODULE_SALIENCE] and [CONTEXT_MODULE_SALIENCE].
   *
   * Salience does not play a role in dependency resolution.
   */
  val salience: Int
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
