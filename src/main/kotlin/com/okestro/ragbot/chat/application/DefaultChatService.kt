package com.okestro.ragbot.chat.application

import com.okestro.ragbot.common.config.AppProperties
import com.okestro.ragbot.embedding.application.EmbeddingService
import com.okestro.ragbot.retrieval.application.DocumentSearchService
import com.okestro.ragbot.retrieval.domain.RetrievalPolicy
import com.okestro.ragbot.retrieval.domain.Source
import org.springframework.stereotype.Service

/**
 * Phase 2-b 파이프라인: 질문 임베딩(1회, 불변식 2) → documents 검색 → 정책 필터 → top-k 출처.
 * **생성은 아직 없음**(Phase 3). 캐시·가드는 Phase 4·5에서 이 앞단에 삽입한다.
 */
@Service
class DefaultChatService(
    private val embeddingService: EmbeddingService,
    private val documentSearch: DocumentSearchService,
    private val retrievalPolicy: RetrievalPolicy,
    private val props: AppProperties,
) : ChatService {
    override fun handle(command: ChatCommand): ChatResult {
        val queryVector = embeddingService.embed(command.question)
        val chunks = retrievalPolicy.apply(documentSearch.search(queryVector, props.retrieval.topK))
        val sources = chunks.map { Source.of(it).display() }
        return ChatResult(
            answer = "(검색 전용 단계 — 생성 미연결, Phase 3) 근거 청크 ${chunks.size}건 조회됨",
            sources = sources,
        )
    }
}
