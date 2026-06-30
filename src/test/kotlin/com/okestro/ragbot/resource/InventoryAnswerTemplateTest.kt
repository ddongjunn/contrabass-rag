package com.okestro.ragbot.resource

import com.okestro.ragbot.resource.application.InventoryAnswerTemplate
import com.okestro.ragbot.resource.domain.InventoryFilters
import com.okestro.ragbot.resource.domain.InventoryKind
import com.okestro.ragbot.resource.domain.InventoryResult
import com.okestro.ragbot.resource.domain.InventoryRow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** DB-3: 조회 결과 → 한국어 답변 + 출처(대상·필터·건수) 순수 렌더링 검증(LLM·DB 불필요). */
class InventoryAnswerTemplateTest {

    @Test
    fun `COUNT — 건수와 조건을 답변·출처에 표기`() {
        val result = InventoryResult(
            kind = InventoryKind.VOLUME,
            rows = emptyList(),
            total = 3,
            appliedFilters = InventoryFilters(projectId = "prod"),
        )
        val out = InventoryAnswerTemplate.render(result)

        assertTrue(out.answer.contains("볼륨 개수는 3건"), out.answer)
        assertTrue(out.answer.contains("project=prod"), out.answer)
        assertEquals(listOf("대상: 볼륨", "필터: project=prod", "건수: 3건"), out.sources)
    }

    @Test
    fun `LIST — 행 목록을 이름·속성으로 표기`() {
        val result = InventoryResult(
            kind = InventoryKind.INSTANCE,
            rows = listOf(
                InventoryRow("u-1", "vm-a", mapOf("status" to "SHUTOFF", "power_state" to null)),
            ),
            total = 1,
            appliedFilters = InventoryFilters(status = "ACTIVE", statusOp = InventoryFilters.Op.NEQ),
        )
        val out = InventoryAnswerTemplate.render(result)

        assertTrue(out.answer.contains("인스턴스 1건"), out.answer)
        assertTrue(out.answer.contains("- vm-a (status=SHUTOFF)"), out.answer)
        assertTrue(out.answer.contains("status≠ACTIVE"), "NEQ는 ≠로 표기")
        assertEquals("필터: status≠ACTIVE", out.sources[1])
    }

    @Test
    fun `결과 0건 — 없음 메시지`() {
        val result = InventoryResult(InventoryKind.INSTANCE, emptyList(), 0, InventoryFilters())
        val out = InventoryAnswerTemplate.render(result)

        assertTrue(out.answer.contains("찾지 못했습니다"), out.answer)
        assertEquals("필터: (없음)", out.sources[1])
    }
}
