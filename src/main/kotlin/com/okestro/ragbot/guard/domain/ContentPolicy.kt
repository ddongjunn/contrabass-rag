package com.okestro.ragbot.guard.domain

import com.okestro.ragbot.common.config.AppProperties
import org.springframework.core.io.ResourceLoader
import org.springframework.stereotype.Component

/**
 * 로컬 금칙어 1차 차단(임베딩·생성 전). 사전 경로 = app.guard.banned-words-path.
 * '#' 주석·빈 줄 제외, 대소문자 무시 부분일치. (애매한 입력 2차 판별=Moderation은 5-b.)
 */
@Component
class ContentPolicy(
    props: AppProperties,
    resourceLoader: ResourceLoader,
) {
    private val bannedWords: List<String> = loadWords(props.guard.bannedWordsPath, resourceLoader)

    fun check(question: String): GuardDecision {
        val lower = question.lowercase()
        return if (bannedWords.any { lower.contains(it) }) {
            GuardDecision.Blocked("banned-word", "부적절한 표현이 포함되어 답변할 수 없습니다.")
        } else {
            GuardDecision.Allowed
        }
    }

    private fun loadWords(path: String, loader: ResourceLoader): List<String> {
        val resource = loader.getResource(path)
        if (!resource.exists()) return emptyList()
        return resource.inputStream.bufferedReader().useLines { lines ->
            lines.map { it.trim() }
                .filter { it.isNotEmpty() && !it.startsWith("#") }
                .map { it.lowercase() }
                .toList()
        }
    }
}
