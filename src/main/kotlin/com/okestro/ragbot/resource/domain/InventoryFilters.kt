package com.okestro.ragbot.resource.domain

/**
 * INVENTORY 조회 필터. 모든 필드는 nullable — null이면 해당 필터를 무력화한다(SQL에서 `? IS NULL OR …`).
 * 사용자/LLM 값은 SQL에 직접 들어가지 않고 파라미터로만 바인딩된다(환각·injection 차단).
 */
data class InventoryFilters(
    val status: String? = null,
    val statusOp: Op = Op.EQ,
    val projectId: String? = null,
    val hypervisorHostName: String? = null,
    val instanceCreateEnable: Boolean? = null,
) {
    enum class Op { EQ, NEQ }
}
