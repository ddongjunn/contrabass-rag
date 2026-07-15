package com.okestro.ragbot.resource

import com.okestro.ragbot.resource.application.StatusAnswerTemplate
import com.okestro.ragbot.resource.domain.StatusDonutWidget
import com.okestro.ragbot.resource.domain.StatusSegment
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * status_donut 평문 answer(순수 함수, LLM 무호출). 불변식: 위젯이 있어도 평문은 항상 함께 나간다
 * (Slack·스크린리더 폴백). 위젯과 같은 숫자를 써서 드리프트를 막는다.
 */
class StatusAnswerTemplateTest {

    private fun widget(vararg segs: Pair<String, Int>) = StatusDonutWidget(
        label = "인스턴스",
        total = segs.sumOf { it.second },
        segments = segs.map { (s, c) -> StatusSegment(s, c, "good") },
        empty = segs.isEmpty(),
    )

    @Test
    fun `총계와 상태별 내역을 한 문장으로`() {
        // 라이브 실측(2026-07-15): ACTIVE=121, SHUTOFF=5, ERROR=1
        val answer = StatusAnswerTemplate.render(widget("ACTIVE" to 121, "SHUTOFF" to 5, "ERROR" to 1))
        assertEquals("인스턴스 127대의 상태 분포입니다 — ACTIVE 121대, SHUTOFF 5대, ERROR 1대.", answer)
    }

    @Test
    fun `empty면 못 찾았다고 답한다`() {
        // 조사는 이(가) 형태 — label이 "볼륨"이면 "볼륨가"가 되어 틀린다(받침 유무에 따라 이/가).
        // 기존 InventoryAnswerTemplate의 "을(를)" 관례를 따른다.
        assertEquals("조회된 인스턴스이(가) 없습니다.", StatusAnswerTemplate.render(widget()))
    }

    @Test
    fun `상태 하나뿐이어도 자연스럽게`() {
        assertEquals("인스턴스 3대의 상태 분포입니다 — ACTIVE 3대.", StatusAnswerTemplate.render(widget("ACTIVE" to 3)))
    }

    @Test
    fun `label을 따라간다 - 인스턴스가 아닐 수도`() {
        val w = StatusDonutWidget("볼륨", 3, listOf(StatusSegment("available", 3, "good")))
        assertTrue(StatusAnswerTemplate.render(w).startsWith("볼륨 3대"))
    }
}
