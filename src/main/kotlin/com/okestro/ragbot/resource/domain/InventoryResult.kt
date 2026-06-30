package com.okestro.ragbot.resource.domain

/**
 * INVENTORY 조회 결과. LIST 모드는 rows에 행을, COUNT 모드는 rows 비우고 total에 건수를 담는다.
 * appliedFilters는 답변 출처(어떤 조건이 적용됐는지)에 표기하기 위해 보존한다.
 */
data class InventoryResult(
    val kind: InventoryKind,
    val rows: List<InventoryRow>,
    val total: Int,
    val appliedFilters: InventoryFilters,
)
