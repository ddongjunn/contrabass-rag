package com.okestro.ragbot.resource.domain

sealed class ResourceExtraction {
    data class Resolved(val query: ResourceQuery) : ResourceExtraction()
    data class NeedsClarification(val message: String) : ResourceExtraction()
}
