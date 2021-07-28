package tech.b180.cordaptor.corda

import hu.akarnokd.rxjava3.interop.RxJavaInterop
import net.corda.core.contracts.ContractState
import net.corda.core.messaging.DataFeed
import net.corda.core.node.services.Vault
import net.corda.core.node.services.vault.*

// ==================================================
// Interop logic from Corda types to Cordaptor types
// ==================================================

fun <T: ContractState> Vault.Page<T>.toCordaptorPage(): CordaVaultPage<T> {
  return CordaVaultPage(states = states, statesMetadata = statesMetadata,
      stateTypes = stateTypes, totalStatesAvailable = totalStatesAvailable)
}

fun <T: ContractState> DataFeed<Vault.Page<T>, Vault.Update<T>>.toCordaptorFeed(): CordaDataFeed<T> {
  return CordaDataFeed(
      snapshot = snapshot.toCordaptorPage(),
      feed = RxJavaInterop.toV3Observable(updates)
  )
}

// ==================================================
// Interop logic from Cordaptor types to Corda types
// ==================================================

fun <T: ContractState> CordaVaultQuery<T>.toCordaQueryCriteria(locator: PartyLocator): QueryCriteria {

  // construct a list of criteria for non-empty fields and then reduce them using AND composition
  // for multi-valued fields use collection criteria fields which imply OR composition

  val criteria = mutableListOf<QueryCriteria>()
  criteria.add(
      QueryCriteria.VaultQueryCriteria(
          contractStateTypes = setOf(contractStateClass.java),
          status = stateStatus ?: Vault.StateStatus.UNCONSUMED,
          relevancyStatus = relevancyStatus ?: Vault.RelevancyStatus.ALL,
          notary = notaryNames?.map {
            locator.wellKnownPartyFromX500Name(it)
                ?: throw IllegalArgumentException("Cannot find notary with name $it")
          },
          participants = participantNames?.map {
            locator.wellKnownPartyFromX500Name(it)
                ?: throw IllegalArgumentException("Cannot find party with name $it")
          },
          timeCondition = when {
            recordedTimeIsAfter != null -> QueryCriteria.TimeCondition(
                QueryCriteria.TimeInstantType.RECORDED, Builder.greaterThanOrEqual(recordedTimeIsAfter))
            consumedTimeIsAfter != null -> QueryCriteria.TimeCondition(
                QueryCriteria.TimeInstantType.RECORDED, Builder.greaterThanOrEqual(consumedTimeIsAfter))
            else -> null
          }
      ))

  if (linearStateUUIDs?.isNotEmpty() == true || linearStateExternalIds?.isNotEmpty() == true) {
    criteria.add(QueryCriteria.LinearStateQueryCriteria(
        contractStateTypes = setOf(contractStateClass.java),
        status = stateStatus ?: Vault.StateStatus.UNCONSUMED,
        uuid = linearStateUUIDs,
        externalId = linearStateExternalIds)
    )
  }

  if (ownerNames?.isNotEmpty() == true) {
    criteria.add(QueryCriteria.FungibleAssetQueryCriteria(
        owner = ownerNames.map {
          locator.wellKnownPartyFromX500Name(it)
              ?: throw IllegalArgumentException("Cannot find party with name $it")
        }
    ))
  }

  return criteria.reduce(QueryCriteria::and)
}

fun <T: ContractState> CordaVaultQuery<T>.toCordaSort(): Sort {
  return Sort(columns = sortCriteria?.map(CordaVaultQuery.SortColumn::toCordaSortColumn) ?: emptySet())
}

fun <T: ContractState> CordaVaultQuery<T>.toCordaPageSpecification(): PageSpecification {
  return PageSpecification(pageSize = pageSize ?: MAX_PAGE_SIZE, pageNumber = pageNumber?.plus(1) ?: 1)
}

fun CordaVaultQuery.SortColumn.toCordaSortColumn(): Sort.SortColumn {
  // FIXME add support for custom schema attributes

  val standardSortAttribute = CordaVaultQuery.StandardSortAttributes.values().find {
    it.attributeName == sortAttribute
  }?.attribute
      ?: throw IllegalArgumentException("Unknown sort attribute $sortAttribute")

  return Sort.SortColumn(
      direction = direction,
      sortAttribute = SortAttribute.Standard(standardSortAttribute))
}
