package tech.b180.cordaptor.kernel

import org.koin.core.Koin
import org.koin.core.KoinApplication
import org.koin.core.logger.Level
import org.koin.core.logger.Logger
import org.koin.core.logger.PrintLogger
import org.koin.core.module.Module
import org.koin.core.qualifier.Qualifier
import org.koin.dsl.koinApplication
import org.koin.dsl.module
import java.util.*
import kotlin.reflect.KClass

/**
 * Represents an instance of Cordaptor microkernel.
 *
 * Constructor takes an argument, which is a factory building a Koin module to be added to Koin container.
 * The purpose of this module is to make any information preset at the point of
 * the microkernel's instantiation available in other Koin definitions. For example,
 * if the microkernel is instantiated via the [main] function, then the context module will
 * contribute [CommandLineArguments] singleton.
 *
 * A factory is used instead of passing in the actual instance of [Module] because
 * Koin does not like its Module DSL used outside of Koin instantiation flow.
 */
class Container(contextModuleFactory: () -> Module) {

  private val logger: Logger = PrintLogger(Level.INFO)

  companion object {
    private var koinApp : KoinApplication? = null

    val koinInstance: Koin
      get() = koinApp?.koin
          ?: throw IllegalStateException("Container has not been instantiated")
  }

  init {
    koinApp = koinApplication {
      logger(logger)
      fileProperties()
      environmentProperties()

      val providers = ServiceLoader.load(ModuleProvider::class.java).iterator().asSequence().toList()
      logger.info("Found ${providers.size} Cordaptor module provider(s) in classpath:")
      for (provider in providers) {
        logger.info(provider.javaClass.name)
      }

      val settings = BootstrapSettings(emptyMap())

      // mapping modules to a list of pairs with salience being the first item
      // sorting by salience in the ascending order, so the higher values are applied later
      val modules = providers.map { it.salience to it.provideModule(settings) } +
          (ModuleProvider.CONTEXT_MODULE_SALIENCE to contextModuleFactory())

      val sortedModules = modules.sortedBy { it.first }

      modules(sortedModules.map { it.second })
    }
    logger.info("Initialized Koin application $koinApp")
  }

  fun <T : Any> get(clazz: KClass<T>, qualifier: Qualifier? = null): T {
    return koinInstance.get<T>(clazz, qualifier)
  }

  fun <T : Any> getAll(clazz: KClass<T>): List<T> {
    return koinInstance._scopeRegistry.rootScope.getAll(clazz)
  }

  fun initialize() {
    logger.info("Initializing lifecycle aware components")

    // creating a set to avoid calling more than once if bound twice
    val all = koinInstance.getAll<LifecycleAware>().toSet()
    all.forEach {
      it.initialize();
    }
  }

  fun shutdown() {
    logger.info("Shutting down lifecycle aware components")
    val all = koinInstance.getAll<LifecycleAware>().toSet()
    all.forEach {
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