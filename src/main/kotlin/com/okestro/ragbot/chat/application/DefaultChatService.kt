package com.okestro.ragbot.chat.application

import com.okestro.ragbot.common.config.AppProperties
import com.okestro.ragbot.embedding.application.EmbeddingService
import com.okestro.ragbot.generation.application.AnswerGenerationService
import com.okestro.ragbot.retrieval.application.DocumentSearchService
import com.okestro.ragbot.retrieval.domain.RetrievalPolicy
import com.okestro.ragbot.retrieval.domain.Source
import org.springframework.stereotype.Service

/**
 * 파이프라인: 질문 임베딩(1회, 불변식 2) → documents 검색 → 정책 필터 → 생성 → top-k 출처.
 * 검색 0건 시 생성 호출 없이 "관련 문서 없음"(불변식 3). 캐시·가드는 Phase 4·5에서 앞단에 삽입한다.
 */
@Service
class DefaultChatService(
    private val embeddingService: EmbeddingService,
    private val documentSearch: DocumentSearchService,
    private val retrievalPolicy: RetrievalPolicy,
    private val answerGeneration: AnswerGenerationService,
    private val props: AppProperties,
) : ChatService {
    override fun handle(command: ChatCommand): ChatResult {
        val queryVector = embeddingService.embed(command.question)
        val chunks = retrievalPolicy.apply(documentSearch.search(queryVector, props.retrieval.topK))
        if (chunks.isEmpty()) {
            return ChatResult(answer = "관련 문서를 찾지 못했습니다.", sources = emptyList())
        }
        val answer = answerGeneration.generate(command.question, chunks)
        val sources = chunks.map { Source.of(it).display() }
        return ChatResult(answer = answer, sources = sources)
    }
}
