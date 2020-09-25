package tech.b180.cordaptor.kernel

import org.koin.core.KoinApplication
import org.koin.core.logger.Level
import org.koin.core.logger.Logger
import org.koin.core.logger.PrintLogger
import org.koin.core.module.Module
import org.koin.dsl.koinApplication
import org.koin.dsl.module
import java.util.*

/**
 * Represents an instance of Cordaptor microkernel.
 *
 * Constructor takes an argument, which is a factory building a Koin module to be added to Koin container.
 * The purpose of this module is to make any information preset at the point of
 * the microkernel's instantiation available in other Koin definitions. For example,
 * if the microkernel is instantiated via the [main] function, then the context module will
 * contribute [CommandLineArguments] singleton.
 */
class Container(contextModuleFactory: () -> Module) {

  private val koinApp : KoinApplication
  private val logger: Logger = PrintLogger(Level.INFO)

  init {
    koinApp = koinApplication {
      logger(logger)
      environmentProperties()

      val providers = ServiceLoader.load(ModuleProvider::class.java).iterator().asSequence().toList()
      logger.info("Found ${providers.size} Cordaptor module provider(s) in classpath")
      val modules = listOf(contextModuleFactory()) + providers.map { it.buildModule() }

      modules(modules)
    }
  }

  fun initialize() {
    logger.info("Initializing lifecycle aware components")
    koinApp.koin.getAll<LifecycleAware>().forEach {
      it.initialize();
    }
  }

  fun shutdown() {
    logger.info("Shutting down lifecycle aware components")
    koinApp.koin.getAll<LifecycleAware>().forEach {
      it.shutdown();
    }
  }
}

data class CommandLineArguments(val args: List<String>);

/**
 * Entry point for Cordaptor when it is running as a standalone JVM.
 */
fun main(args: Array<String>) {
  println("Cordaptor is starting up")

  val containerInstance = Container {
    module {
      single { CommandLineArguments(args.asList()) }
    }
  }

  containerInstance.initialize();

  Runtime.getRuntime().addShutdownHook(Thread {
    containerInstance.shutdown()
  })
}