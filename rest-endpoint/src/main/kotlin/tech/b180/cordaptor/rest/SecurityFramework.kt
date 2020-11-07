package tech.b180.cordaptor.rest


/**
 * This is the entry point for the API security framework.
 * This is called early in the API request processing lifecycle
 * to authenticate all incoming requests.
 */
interface SecurityEngine {

  /**
   * Logic used to verify credentials in the incoming request and construct
   * a security subject for it. This will throw an exception if the credentials
   * are not valid, but the implementation will not attempt to validate
   * whether the subject has rights to invoke the operation.
   */
//  fun authenticateRequest(request: HttpServletRequest): Subject
}

/**
 * Representation of the security subject associated with the current API invocation.
 * Authorization checks are performed at the level of individual API operations.
 */
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
 * Security engine implementation that is created when no other engine is configured.
 * The actual implementation simply allows everything it is asked for.
 */
class NoopSecurityEngine : SecurityEngine {

//  override fun authenticateRequest(request: HttpServletRequest): Subject = PermissiveSubject

  object PermissiveSubject : Subject {
    override fun isPermitted(operation: String, entity: String?) = true
  }
}

/**
 * Simple implementation of the API security engine which checks if the request
 * contains predetermined API key string. If this is the case, any operation
 * is permitted by the engine.
 */
class APIKeySecurityEngine : SecurityEngine {
//  override fun authenticateRequest(request: HttpServletRequest): Subject {
//    TODO("Not yet implemented")
//  }
}
