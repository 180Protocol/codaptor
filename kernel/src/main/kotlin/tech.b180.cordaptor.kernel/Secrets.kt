package tech.b180.cordaptor.kernel

import org.koin.core.get

/**
 * Entry point for the microkernel's secrets manager. Container will define an instance of [ConfigSecretsStore]
 * bound to [SecretsStore] in its Koin module, but other modules may override this component.
 *
 * Note that default implementation is not secure as it reads secret values from plain-text configuration
 * and keeps them in the memory of JVM as strings all the time once they are read.
 */
@ModuleAPI(since = "0.1")
interface SecretsStore {

  fun <R> useStringSecret(id: String, block: (CharArray) -> R): R
}

/**
 * Generic handle of a secret that allows some code to access it while limiting secret's exposure.
 */
@ModuleAPI(since = "0.1")
interface Secret<T> {

  val id: String

  /** Calls provided code block with the secret value as parameter.
   * Secure implementation will ensure that the secret does not remain in the memory unencrypted outside the call */
  fun <R> use(secretsStore: SecretsStore, block: (T) -> R): R
}

/**
 * Typesafe secret handle that can use a [SecretsStore] to obtain its value.
 */
@ModuleAPI(since = "0.1")
class StringSecret(override val id: String): Secret<CharArray> {
  override fun <R> use(secretsStore: SecretsStore, block: (CharArray) -> R): R {
    return secretsStore.useStringSecret(id, block)
  }
}

/**
 * Shorthand for accessing a secret within a managed component
 */
@ModuleAPI(since = "0.1")
fun <T, R> CordaptorComponent.useSecret(secret: Secret<T>, block: (T) -> R): R = secret.use(get(), block)

/**
 * Shorthand for accessing values of two secrets simultaneously within a managed component
 */
@ModuleAPI(since = "0.1")
fun <T, R> CordaptorComponent.useSecrets(s1: Secret<T>, s2: Secret<T>, block: (T, T) -> R): R {
  return get<SecretsStore>().let { store ->
    s1.use(store) { v1 ->
      s2.use(store) { v2 ->
        block(v1, v2)
      }
    }
  }
}

/**
 * Default insecure implementation of [SecretsStore] that reads values from module configuration.
 */
class ConfigSecretsStore(private val bootstrapConfig: Config) : SecretsStore {

  override fun <R> useStringSecret(id: String, block: (CharArray) -> R): R {
    return block(bootstrapConfig.getString(id).toCharArray())
  }
}
