package com.okestro.ragbot.resource.application

import com.okestro.ragbot.resource.domain.InventoryQuery
import com.okestro.ragbot.resource.domain.InventoryResult

/**
 * cb_common(읽기 전용) INVENTORY 조회 포트. 구현은 화이트리스트 템플릿 + 파라미터 바인딩만 사용(자유 SQL 금지).
 * providerUuid는 cm_provider.uuid 범위 한정(불변 컨텍스트), limit은 LIST 상한.
 */
interface InventoryRepository {
    fun find(query: InventoryQuery, providerUuid: String, limit: Int): InventoryResult
}
