package tech.b180.cordaptor.kernel

import org.koin.core.KoinComponent
import org.koin.core.qualifier.Qualifier

/**
 * Get all instances from Koin
 */
inline fun <reified T : Any> KoinComponent.getAll(): List<T> = getKoin().getAll()