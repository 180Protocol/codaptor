package tech.b180.cordaptor.kernel

import org.koin.core.Koin
import org.koin.core.KoinComponent
import org.koin.core.scope.Scope

/**
 * Base mixin interface for Cordaptor component classes which need to use Koin API.
 */
interface CordaptorComponent : KoinComponent {
  override fun getKoin(): Koin = Container.koinInstance
}

/**
 * Get all instances from Koin
 */
inline fun <reified T : Any> CordaptorComponent.getAll(): List<T> = getKoin().getAll()

/**
 * Shorthand for creating a Kotlin [Lazy] to use in Koin Module DSL.
 * Returned lazy resolves to a list of instances bound to a given class or interface
 * in the context of the scope of the definition.
 */
inline fun <reified T : Any> Scope.lazyGetAll(): Lazy<List<T>> = lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
  this.getAll<T>(T::class)
}