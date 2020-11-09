package tech.b180.cordaptor.kernel

import org.koin.core.Koin
import org.koin.core.KoinComponent
import org.koin.core.scope.Scope

/**
 * Marks types, methods, and properties that are intended to be used as a public API for a module.
 * Documentation should make clarify how to interact with Koin to access instances where
 * applicable (e.g. are injection parameters or qualifiers required).
 *
 * All classes and interfaces within a module that are not annotated as [ModuleAPI],
 * are considered internal and are subject to change.
 */
@MustBeDocumented
@Retention(AnnotationRetention.RUNTIME)
@Target(
    AnnotationTarget.CLASS, AnnotationTarget.CONSTRUCTOR, AnnotationTarget.TYPE,
    AnnotationTarget.FUNCTION, AnnotationTarget.PROPERTY
)
annotation class ModuleAPI(
    /** Semantic version number of the module when the API feature was first introduced */
    val since: String
)

/**
 * Marks types, methods, and properties that were part of the module's public API,
 * but are now discouraged from use.
 */
@MustBeDocumented
@Retention(AnnotationRetention.RUNTIME)
@Target(
    AnnotationTarget.CLASS, AnnotationTarget.CONSTRUCTOR, AnnotationTarget.TYPE,
    AnnotationTarget.FUNCTION, AnnotationTarget.PROPERTY
)
annotation class DeprecatedModuleAPI(
    /** Semantic version number of the module when the API feature became deprecated */
    val since: String
)

/**
 * Base mixin interface for Cordaptor component classes which need to use Koin API.
 */
@ModuleAPI(since = "0.1")
interface CordaptorComponent : KoinComponent {
  override fun getKoin(): Koin = Container.koinInstance
}

/**
 * Get all instances from Koin
 */
@ModuleAPI(since = "0.1")
inline fun <reified T : Any> CordaptorComponent.getAll(): List<T> = getKoin().getAll()

/**
 * Shorthand for creating a Kotlin [Lazy] to use in Koin Module DSL.
 * Returned lazy resolves to a list of instances bound to a given class or interface
 * in the context of the scope of the definition.
 */
@ModuleAPI(since = "0.1")
inline fun <reified T : Any> Scope.lazyGetAll(): Lazy<List<T>> = lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
  this.getAll<T>(T::class)
}