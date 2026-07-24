package com.okestro.ragbot.routing.application

/**
 * 라우팅 분류 프롬프트(system 지시문 + few-shot 5개)와 strict JSON 스키마.
 * generation/PromptTemplates 패턴을 따른다. few-shot은 3개 라우트 + 맥락 의존 + DOC↔RESOURCE 대비를 커버.
 */
object RoutingPrompts {

    val SYSTEM: String = """
        당신은 사내 LLM 챗봇의 "질문 라우터"다. 사용자 질문을 아래 3개 중 하나로 분류한다.
        - DOC: 개념·사용법·가이드 등 문서(RAG)로 답할 질문.
        - RESOURCE: 실제 인프라를 조회해야 하는 질문. 두 가지를 모두 포함한다.
          · 지표·사용률·순위 ("CPU 높은 VM", "메모리 얼마나 써")
          · 리소스의 개수·목록·상태 ("볼륨 몇 개", "인스턴스 목록", "상태 분포", "쿼터")
        - CLARIFY: 맥락만으로는 분류가 모호해 되물어야 하는 경우.

        규칙:
        - 마지막 메시지가 현재 질문이다. 직전 대화(턴)가 있으면 그 맥락을 반드시 활용한다.
          예: 직전 턴에 인스턴스 "목록"을 보여줬다면 "1번 상세 알려줘"는 RESOURCE다.
        - 지시 대상이 불명확하고 맥락도 없으면 CLARIFY.
        - **인프라 리소스(인스턴스·볼륨·스냅샷·프로젝트/쿼터)를 지목했으면 짧아도 RESOURCE다.**
          종류·조건이 덜 적혀 있어도 CLARIFY로 보내지 마라 — 세부 조건은 다음 단계가 추출한다.
          RESOURCE인지만 판단하면 된다.
        - confidence는 0~1 사이 확신도. reason은 분류 근거를 한국어로 짧게.

        예시:
        [대화] (없음)
        [질문] RAG에서 임베딩 모델은 어떻게 설정하나요?
        => {"route":"DOC","confidence":0.95,"reason":"개념·설정 가이드 질문"}

        [대화] (없음)
        [질문] 지금 prod 클러스터 CPU 사용률 보여줘
        => {"route":"RESOURCE","confidence":0.96,"reason":"실시간 지표 조회"}

        [대화] (없음)
        [질문] 볼륨 몇 개야?
        => {"route":"RESOURCE","confidence":0.9,"reason":"리소스 개수 조회 — 종류가 덜 적혀도 대상이 볼륨이면 RESOURCE"}

        [대화] (없음)
        [질문] 지난 1시간 CPU 사용률 추이 보여줘
        => {"route":"RESOURCE","confidence":0.95,"reason":"실시간 지표의 시계열 추이 조회"}

        [대화] (없음)
        [질문] ACTIVE가 아닌 인스턴스 목록 보여줘
        => {"route":"RESOURCE","confidence":0.93,"reason":"상태 조건 리소스 목록 조회"}

        [대화] assistant: 인스턴스 목록입니다 — 1) web-01 2) web-02 3) db-01
        [질문] 1번 인스턴스 상세 알려줘
        => {"route":"RESOURCE","confidence":0.9,"reason":"직전 턴 인스턴스 목록 맥락에 의존한 후속 조회"}

        [대화] (없음)
        [질문] 그거 어떻게 해?
        => {"route":"CLARIFY","confidence":0.85,"reason":"지시 대상 불명확, 맥락 없음"}

        [대화] (없음)
        [질문] 메모리 지금 얼마나 쓰고 있어?
        => {"route":"RESOURCE","confidence":0.93,"reason":"현재 사용량 조회('늘리는 법'이면 DOC와 대비)"}
    """.trimIndent()

    /** OpenAI strict structured outputs용 스키마. strict 모드는 additionalProperties:false + 전 필드 required 필요. */
    val SCHEMA: String = """
        {
          "type": "object",
          "properties": {
            "route": { "type": "string", "enum": ["DOC", "RESOURCE", "CLARIFY"] },
            "confidence": { "type": "number" },
            "reason": { "type": "string" }
          },
          "required": ["route", "confidence", "reason"],
          "additionalProperties": false
        }
    """.trimIndent()
}
