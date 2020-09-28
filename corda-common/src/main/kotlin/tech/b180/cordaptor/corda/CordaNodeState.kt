package tech.b180.cordaptor.corda

import io.reactivex.rxjava3.core.Observable
import net.corda.core.contracts.*
import net.corda.core.crypto.SecureHash
import net.corda.core.flows.StateMachineRunId
import net.corda.core.node.NodeInfo
import net.corda.core.transactions.SignedTransaction

/**
 * Single access point for all state of a particular Corda node that is exposed
 * via an API endpoint by Cordaptor.
 *
 * Different modules implement this interface in a different way depending
 * on the nature of their interaction with the underlying node. Different caching
 * strategies also change the way the implementation behaves.
 */
interface CordaNodeState {

  val nodeInfo: NodeInfo

  fun <T : ContractState> findStateByRef(stateRef: StateRef, clazz: Class<T>): StateAndRef<T>?

  fun findTransactionByHash(hash: SecureHash): SignedTransaction?

  fun <T : ContractState> countStates(query: CordaStateQuery<T>): Int

  fun <T : Any> aggregateFungibleState(query: CordaStateQuery<FungibleState<T>>, clazz: Class<T>): Amount<T>

  fun <T : ContractState> trackStates(query: CordaStateQuery<T>): Observable<T>

  fun initiateFlow(instruction: CordaFlowInstruction): Observable<CordaFlowStatus>

  fun getFlowStatus(flowRunId: StateMachineRunId): CordaFlowStatus
}

data class CordaFlowStatus(
    val flowClassName: String,
    val flowRunId: StateMachineRunId
)

data class CordaFlowInstruction(
    val flowClassName: String
)

data class CordaStateQuery<T : ContractState>(
    val contractStateType: Class<T>
)
