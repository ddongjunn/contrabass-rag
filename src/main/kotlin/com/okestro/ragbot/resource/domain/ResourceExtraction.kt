package com.okestro.ragbot.resource.domain

sealed class ResourceExtraction {
    /** METRIC(Prometheus) 트랙 결과. */
    data class Resolved(val query: ResourceQuery) : ResourceExtraction()

    /** INVENTORY(cb_common DB) 트랙 결과. */
    data class InventoryResolved(val query: InventoryQuery) : ResourceExtraction()

    data class NeedsClarification(val message: String) : ResourceExtraction()
}
