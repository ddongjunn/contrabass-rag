package com.okestro.ragbot.common.config

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * 튜닝값 단일 소스. application.yml 의 `app.*` 를 그대로 매핑한다(하드코딩 금지).
 * 값 변경 = yml 한 줄(+재기동). 시크릿은 여기 두지 않는다(환경변수만).
 */
@ConfigurationProperties(prefix = "app")
data class AppProperties(
    val llm: Llm = Llm(),
    val openai: Openai = Openai(),
    val retrieval: Retrieval = Retrieval(),
    val cache: Cache = Cache(),
    val guard: Guard = Guard(),
    val slack: Slack = Slack(),
    val router: Router = Router(),
    val resource: Resource = Resource(),
) {
    data class Llm(
        val provider: String = "openai",
    )

    data class Openai(
        val chatModel: String = "gpt-4o-mini",
        val embeddingModel: String = "text-embedding-3-small", // 1536, 색인과 동일 고정(불변식)
        val embeddingDim: Int = 1536,
    )

    data class Retrieval(
        val topK: Int = 5,
        val minScore: Double? = null,        // 2차 튜닝(미설정 시 0건 차단만)
        val table: String = "documents",     // 읽기 전용
        val sourceKeys: SourceKeys = SourceKeys(),
    ) {
        data class SourceKeys(
            val title: String = "title",
            val page: String = "page",
            val chunk: String = "chunk_index",
        )
    }

    data class Cache(
        val enabled: Boolean = true,
        val cosineThreshold: Double = 0.95,
        val table: String? = null,           // 이 앱 소유 — 확정 시 기입(Phase 4)
    )

    data class Guard(
        val maxQuestionLen: Int = 1000,
        val ratePerMin: Int = 5,             // 사용자(Slack user_id)별 분당 한도
        val moderation: Moderation = Moderation(),
        val bannedWordsPath: String = "classpath:banned-words.txt",
    ) {
        data class Moderation(
            val enabled: Boolean = true,
            val model: String = "omni-moderation-latest",
        )
    }

    data class Slack(
        val mode: String = "socket",         // 1차 고정
    )

    data class Router(
        val model: String = "gpt-4o-mini",   // 라우팅용 작고 빠른 모델 (교체 가능)
        val temperature: Double = 0.0,
        val minConfidence: Double = 0.5,      // 미만 → CLARIFY 폴백
        val historyTurns: Int = 2,            // 라우터가 LLM에 넘기는 최근 메시지 수(현재 질문 포함)
    )

    data class Resource(
        val extractionModel: String = "gpt-4o-mini",
        val temperature: Double = 0.0,
        val minConfidence: Double = 0.5,      // 미만 → NeedsClarification 폴백
        val defaultWindow: String = "5m",
        val defaultTopN: Int = 5,
        val prometheus: Prometheus = Prometheus(),
        val catalog: Map<String, CatalogEntryConfig> = emptyMap(),
    ) {
        data class Prometheus(
            val baseUrl: String = "",         // env PROMETHEUS_URL (R3에서 필수)
            val connectTimeout: String = "3s",
            val readTimeout: String = "10s",  // 무거운 rate+조인 쿼리 고려
        )

        data class CatalogEntryConfig(
            val pattern: String = "",         // PromPattern enum name (RATIO_TOPK 등)
            val rawMetric: String = "",       // Prometheus 메트릭 이름
            val unit: String = "",
        )
    }
}
