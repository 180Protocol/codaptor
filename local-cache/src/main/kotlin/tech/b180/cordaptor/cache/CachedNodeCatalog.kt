package tech.b180.cordaptor.cache

import tech.b180.cordaptor.corda.CordaNodeCatalog
import tech.b180.cordaptor.corda.CordaNodeCatalogInner
import tech.b180.cordaptor.corda.CordappInfo

class CachedNodeCatalog(private val delegate: CordaNodeCatalogInner) : CordaNodeCatalog {

  override val cordapps: Collection<CordappInfo>
    get() = delegate.cordapps
}