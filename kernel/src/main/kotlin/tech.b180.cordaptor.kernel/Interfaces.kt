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
@ModuleAPI(since = "0.1")
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
   * Note that this method will not be called if 'enabled' key under [configPath] is not set to a truthy value.
   *
   * @param moduleConfig configuration subtree for the module at the path identified by [configPath]
   */
  fun provideModule(moduleConfig: Config): Module

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

  /**
   * Path to the root of the configuration tree that will be passed into [provideModule] call.
   */
  val configPath: ConfigPath
}

/**
 * This interface is meant to be implemented by Cordaptor components
 * to be notified of the main container lifecycle events, e.g. to acquire
 * and/or gracefully release necessary resources.
 */
@ModuleAPI(since = "0.1")
interface LifecycleAware {

  /**
   * Called when the microkernel container got instantiated.
   * At this point all the dependencies between modules will be resolved, so lazily injected
   * properties are safe to use.
   *
   * The implementation must not assume any order of invocations between components.
   */
  fun onInitialize() { }

  /**
   * Called when the Cordaptor server has started and ready to accept API calls.
   * At this point [onInitialize] is guaranteed to have been invoked.
   *
   * The implementation must not assume any order of invocations between components.
   */
  fun onStarted() { }

  /**
   * Called when the microkernel container is shutting down.
   * It is not guaranteed that this will be invoked in all cases, e.g. abrupt JVM shutdown.
   *
   * The implementation must not assume any order of invocations between components.
   * At this point [onInitialize] and [onSuccess] may have not been invoked if there was an initialization error.
   */
  fun onShutdown() { }
}

/**
 * Allows components to initiate microkernel lifecycle events.
 */
@ModuleAPI(since = "0.1")
interface LifecycleControl {

  /** This will trigger [LifecycleAware.onStarted] lifecycle event callbacks for all registered components */
  fun serverStarted()
}
