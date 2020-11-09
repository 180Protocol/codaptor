package tech.b180.cordaptor.rest

import io.undertow.server.HttpHandler
import io.undertow.server.HttpServerExchange
import io.undertow.util.AttachmentKey
import org.apache.commons.lang3.RandomStringUtils
import org.pac4j.core.authorization.authorizer.CheckProfileTypeAuthorizer
import org.pac4j.core.client.Client
import org.pac4j.core.context.WebContext
import org.pac4j.core.credentials.Credentials
import org.pac4j.core.credentials.TokenCredentials
import org.pac4j.core.credentials.authenticator.Authenticator
import org.pac4j.core.engine.DefaultSecurityLogic
import org.pac4j.core.exception.CredentialsException
import org.pac4j.core.exception.http.ForbiddenAction
import org.pac4j.core.exception.http.HttpAction
import org.pac4j.core.profile.ProfileManager
import org.pac4j.core.profile.UserProfile
import org.pac4j.core.profile.creator.ProfileCreator
import org.pac4j.http.client.direct.HeaderClient
import org.pac4j.undertow.context.UndertowWebContext
import org.pac4j.undertow.handler.SecurityHandler
import tech.b180.cordaptor.kernel.Config
import tech.b180.cordaptor.kernel.ModuleAPI
import tech.b180.cordaptor.kernel.loggerFor
import java.util.*
import kotlin.collections.LinkedHashMap
import org.pac4j.core.config.Config as PAC4JConfig

/**
 * This is the entry point for the API security framework.
 */
@ModuleAPI(since = "0.1")
interface SecurityHandlerFactory {

  fun createSecurityHandler(innerHandler: HttpHandler): HttpHandler
}

/**
 * Representation of the security subject associated with the current API invocation.
 * Authorization checks are performed at the level of individual API operations.
 */
@ModuleAPI(since = "0.1")
interface Subject {

  /**
   * Validates whether the subject can perform a given operation, optionally also providing
   * details about an entity on which the operation is performed.
   *
   * The implementation may use role-based security or some other mechanism, but
   * the underlying details of the configuration are not exposed to the client code.
   */
  fun isPermitted(operation: String, entity: String? = null): Boolean
}

/**
 * Simple implementation of the API security configuration which checks if the request
 * contains predetermined API key string. If this is the case, any operation is permitted.
 *
 * Returned configuration only matches URLs starting with /node,
 * so it does not protect things like OpenAPI JSON or Swagger UI.
 *
 * Implementation is also a PAC4J authenticator and a profile creator to handle PAC4J aspects.
 */
class APIKeySecurityHandlerFactory(factoryConfig: Config)
  : SecurityHandlerFactory, Authenticator<TokenCredentials>, ProfileCreator<TokenCredentials> {

  companion object {
    private val logger = loggerFor<APIKeySecurityHandlerFactory>()
  }

  private val settings = Settings(factoryConfig)
  private val apiKeys: List<String>

  init {
    apiKeys = if (settings.apiKeys.isEmpty()) {
      val randomKey = RandomStringUtils.random(10, true, true)
      logger.warn("Empty list of API keys in config, generated random key [$randomKey]")
      listOf(randomKey)
    } else {
      settings.apiKeys
    }
  }

  override fun createSecurityHandler(innerHandler: HttpHandler): HttpHandler {
    val client = HeaderClient(settings.headerName, this, this)
    val config = PAC4JConfig(client)
    config.addAuthorizer("APIUserAuthorizer", CheckProfileTypeAuthorizer<APIUserProfile>(APIUserProfile::class.java))
    config.addMatcher("APIFunctionsPathPrefix") {
      it.path.startsWith("/node")
    }

    return SecurityHandler.build(innerHandler, config, "HeaderClient", "APIUserAuthorizer",
        "APIFunctionsPathPrefix", false, UnauthorizedAsForbiddenLogic)
  }

  override fun validate(credentials: TokenCredentials, context: WebContext) {
    val keyHeader = context.getRequestHeader(settings.headerName)
    if (!keyHeader.isPresent) {
      logger.debug("No {} header in the request", settings.headerName)
      throw CredentialsException("No API key header in request")
    } else {
      val token = keyHeader.get()
      logger.debug("{} header received: {}", settings.headerName, token)
      if (token !in apiKeys) {
        logger.debug("API key {} is not unknown", token)
        throw CredentialsException("Provided API key $token is unknown")
      }
    }
  }

  override fun create(credentials: TokenCredentials, context: WebContext): Optional<UserProfile> {
    return Optional.of(APIUserProfile)
  }

  /** Eagerly-loaded wrapper for the configuration of this factory */
  data class Settings(
      val headerName: String,
      val apiKeys: List<String>
  ) {
    constructor(config: Config) : this(
        headerName = config.getString("header"),
        apiKeys = config.getStringsList("keys")
    )
  }

  object APIUserProfile : UserProfile {
    private const val ID = "API-Key-User"

    override fun getId() = ID
  }
}

object PermissiveSubject : Subject {
  override fun isPermitted(operation: String, entity: String?) = true
}

/**
 * This is a tweak for PAC4J's [DefaultSecurityLogic] that is suitable for token-based authentication.
 * According to HTTP spec, 401 response must come with WWW-Authenticate header, which is not generated.
 * We map all 401 responses to 403 Forbidden
 *
 * TODO report bug to PAC4J
 */
object UnauthorizedAsForbiddenLogic : DefaultSecurityLogic<Any, UndertowWebContext>() {

  override fun unauthorized(context: UndertowWebContext, currentClients: MutableList<Client<out Credentials>>): HttpAction {
    return ForbiddenAction.INSTANCE
  }

  override fun getProfileManager(context: UndertowWebContext): ProfileManager<UserProfile> {
    // the purpose of this is to inject a customized web context that stores user profiles using exchange attachments
    return ProfileManager(RequestAttributesAsAttachmentsUndertowWebContext(context))
  }
}

/**
 * Uses [HttpServerExchange] attachments for storing user profiles as opposed
 * to [UndertowWebContext] that uses request path parameters and values serialized to strings.
 *
 * TODO report bug to undertow-pac4j
 */
class RequestAttributesAsAttachmentsUndertowWebContext(
    private val delegate: UndertowWebContext
) : WebContext by delegate {

  companion object {
    private val KEY = AttachmentKey.create(RequestAttributesMap::class.java)
  }

  class RequestAttributesMap : LinkedHashMap<String, Any>()

  private val attributesMap: RequestAttributesMap
    get() = delegate.exchange.getAttachment(KEY)
          ?: RequestAttributesMap().also { delegate.exchange.putAttachment(KEY, it) }

  override fun getRequestAttribute(name: String): Optional<Any> {
    return Optional.ofNullable(attributesMap[name])
  }

  override fun setRequestAttribute(name: String, value: Any) {
    attributesMap[name] = value
  }
}