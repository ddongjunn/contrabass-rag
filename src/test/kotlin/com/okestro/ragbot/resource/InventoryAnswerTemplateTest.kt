package com.okestro.ragbot.resource

import com.okestro.ragbot.resource.application.InventoryAnswerTemplate
import com.okestro.ragbot.resource.domain.InventoryFilters
import com.okestro.ragbot.resource.domain.InventoryKind
import com.okestro.ragbot.resource.domain.InventoryResult
import com.okestro.ragbot.resource.domain.InventoryRow
import kotlin.test.Test
import kotlin.test.assertTrue

/** DB-3: 조회 결과 → 한국어 답변 + 출처(대상·필터·건수) 순수 렌더링 검증(LLM·DB 불필요). */
class InventoryAnswerTemplateTest {

    @Test
    fun `COUNT — 건수와 조건을 답변·출처에 표기`() {
        val answer = InventoryAnswerTemplate.render(
            InventoryResult(
                kind = InventoryKind.VOLUME,
                rows = emptyList(),
                total = 3,
                appliedFilters = InventoryFilters(projectId = "prod"),
            )
        )

        assertTrue(answer.contains("볼륨 개수는 3건"), answer)
        assertTrue(answer.contains("project=prod"), answer)
        assertTrue(answer.contains("출처: 대상=볼륨"), answer)
        assertTrue(answer.contains("건수=3건"), answer)
    }

    @Test
    fun `LIST — 행 목록을 이름·속성으로 표기`() {
        val answer = InventoryAnswerTemplate.render(
            InventoryResult(
                kind = InventoryKind.INSTANCE,
                rows = listOf(InventoryRow("u-1", "vm-a", mapOf("status" to "SHUTOFF", "power_state" to null))),
                total = 1,
                appliedFilters = InventoryFilters(status = "ACTIVE", statusOp = InventoryFilters.Op.NEQ),
            )
        )

        assertTrue(answer.contains("인스턴스 1건"), answer)
        assertTrue(answer.contains("- vm-a (status=SHUTOFF)"), answer)
        assertTrue(answer.contains("status≠ACTIVE"), "NEQ는 ≠로 표기")
    }

    @Test
    fun `결과 0건 — 없음 메시지와 필터 없음 출처`() {
        val answer = InventoryAnswerTemplate.render(
            InventoryResult(InventoryKind.INSTANCE, emptyList(), 0, InventoryFilters())
        )

        assertTrue(answer.contains("찾지 못했습니다"), answer)
        assertTrue(answer.contains("필터=없음"), answer)
    }
}
