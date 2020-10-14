package tech.b180.cordaptor.kernel

import org.koin.core.Koin
import org.koin.core.KoinApplication
import org.koin.core.context.KoinContextHandler
import org.koin.core.logger.Level
import org.koin.core.logger.Logger as KoinLogger
import org.koin.core.logger.MESSAGE
import org.koin.core.logger.PrintLogger
import org.koin.core.module.Module
import org.koin.core.qualifier.Qualifier
import org.koin.dsl.koinApplication
import org.koin.dsl.module
import org.slf4j.Logger
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
class Container(bootstrapSettings: BootstrapSettings, contextModuleFactory: () -> Module = { module {  } }) {

  companion object {
    private val logger = loggerFor<Container>()

    private var koinApp : KoinApplication? = null

    val koinInstance: Koin
      get() = koinApp?.koin
          ?: KoinContextHandler.getOrNull() // this caters for tests using KoinTest API
          ?: throw IllegalStateException("No Koin instance -- was Container instantiated or test set up correctly?")
  }

  init {
    if (KoinContextHandler.getOrNull() != null) {
      throw AssertionError("A Koin instance was started with startKoin call -- incorrect test setup?")
    }

    koinApp = koinApplication {
      logger(KoinLoggerAdapter(logger))
      fileProperties()
      environmentProperties()

      val providers = ServiceLoader.load(ModuleProvider::class.java).iterator().asSequence().toList()
      logger.info("Found ${providers.size} Cordaptor module provider(s) in classpath:")
      for (provider in providers) {
        logger.info(provider.javaClass.name)
      }

      // mapping modules to a list of pairs with salience being the first item
      // sorting by salience in the ascending order, so the higher values are applied later
      val modules = providers.map { it.salience to it.provideModule(bootstrapSettings) } +
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

class KoinLoggerAdapter(private val delegate: Logger) : KoinLogger() {
  override fun log(level: Level, msg: MESSAGE) {
    when (level) {
      Level.DEBUG -> delegate.debug(msg)
      Level.INFO -> delegate.info(msg)
      Level.ERROR -> delegate.error(msg)
      else -> { }
    }
  }
}

/**
 * Implementation delegating all properties to [System.getProperty]
 */
class SystemPropertiesBootstrapSettings : BootstrapSettings {

  override fun getOptionalString(name: String): String? = System.getProperty(name)

  override fun getOptionalFlag(name: String): Boolean? = getOptionalString(name)?.toBoolean()
}

/**
 * Entry point for Cordaptor when it is running as a standalone JVM.
 */
fun main(args: Array<String>) {
  println("Cordaptor is starting up")

  val containerInstance = Container(SystemPropertiesBootstrapSettings())

  containerInstance.initialize();

  Runtime.getRuntime().addShutdownHook(Thread {
    containerInstance.shutdown()
  })
}