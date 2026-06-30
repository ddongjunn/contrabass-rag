package com.okestro.ragbot.resource.domain

/**
 * INVENTORY 조회 의도(cb_common). kind=조회 대상, mode=목록/개수, filters=조건.
 * METRIC(ResourceQuery)과 별개 트랙 — RESOURCE 추출에서 target=INVENTORY로 분기해 생성된다.
 */
data class InventoryQuery(
    val kind: InventoryKind,
    val mode: Mode = Mode.LIST,
    val filters: InventoryFilters = InventoryFilters(),
) {
    enum class Mode { LIST, COUNT }
}
