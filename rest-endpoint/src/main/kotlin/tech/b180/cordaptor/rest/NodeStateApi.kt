package tech.b180.cordaptor.rest

import net.corda.core.crypto.SecureHash
import org.eclipse.jetty.server.Request
import org.eclipse.jetty.server.handler.AbstractHandler
import org.koin.core.KoinComponent
import org.koin.core.inject
import tech.b180.cordaptor.corda.CordaNodeCatalog
import tech.b180.cordaptor.corda.CordaNodeState
import javax.servlet.http.HttpServletRequest
import javax.servlet.http.HttpServletResponse

class NodeStateApiProvider(private val contextPath: String) : ContextMappedHandlerFactory, KoinComponent {

  override val handlers: List<ContextMappedHandler>

  private val nodeCatalog by inject<CordaNodeCatalog>()

  private val serializationFactory: SerializationFactory by inject()

  init {
    handlers = nodeCatalog.cordapps.flatMap {
      val flowHandlers : List<ContextMappedHandler> = it.flows.map { flowInfo ->
        FlowInitiationHandler("$contextPath/${flowInfo.flowClassName}")
      }

      val stateHandlers = it.contractStates.map { stateInfo ->
        StateQueryHandler("$contextPath/${stateInfo.stateClassName}")
      }

      flowHandlers + stateHandlers
    }
  }
}

class FlowInitiationHandler(override val contextPath: String) : ContextMappedHandler, AbstractHandler() {
  override fun handle(target: String?, baseRequest: Request?, request: HttpServletRequest?, response: HttpServletResponse?) {
    TODO("Not yet implemented")
  }

}

class StateQueryHandler(override val contextPath: String) : ContextMappedHandler, AbstractHandler() {
  override fun handle(target: String?, baseRequest: Request?, request: HttpServletRequest?, response: HttpServletResponse?) {
    TODO("Not yet implemented")
  }

}

/**
 * Resolves REST API queries for specific transactions using a secure hash.
 */
class TransactionQueryHandler(override val contextPath: String)
  : ContextMappedHandler, AbstractHandler(), KoinComponent {

  private val nodeState: CordaNodeState by inject()
//  private val txSerializer: CordaSignedTransactionSerializer by inject()

  override fun handle(target: String?, baseRequest: Request?, request: HttpServletRequest?, response: HttpServletResponse?) {
    when (baseRequest!!.method) {
      "GET" -> {
        val hash = SecureHash.parse(request!!.pathInfo!!)
        val tx = nodeState.findTransactionByHash(hash)
            ?: throw NoSuchElementException()

//        val serializedTx = txSerializer.toJson(tx)
//        response!!.outputStream.print(serializedTx.toString())
      }
      else -> {
        response!!.status = HttpServletResponse.SC_METHOD_NOT_ALLOWED
      }
    }
    baseRequest.isHandled = true
  }

}

class VaultQueryHandler(override val contextPath: String) : ContextMappedHandler, AbstractHandler() {
  override fun handle(target: String?, baseRequest: Request?, request: HttpServletRequest?, response: HttpServletResponse?) {
    TODO("Not yet implemented")
  }

}

class CountingVaultQueryHandler(override val contextPath: String) : ContextMappedHandler, AbstractHandler() {
  override fun handle(target: String?, baseRequest: Request?, request: HttpServletRequest?, response: HttpServletResponse?) {
    TODO("Not yet implemented")
  }

}

class AggregatingVaultQueryHandler(override val contextPath: String) : ContextMappedHandler, AbstractHandler() {
  override fun handle(target: String?, baseRequest: Request?, request: HttpServletRequest?, response: HttpServletResponse?) {
    TODO("Not yet implemented")
  }

}