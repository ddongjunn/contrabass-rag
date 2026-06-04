package com.okestro.ragbot.slack.infrastructure

import com.okestro.ragbot.slack.application.SlackResponseService
import com.slack.api.Slack
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component

/**
 * `chat.postMessage`로 답변+출처를 원 멘션 스레드에 게시. 봇 토큰은 환경변수만(SLACK_BOT_TOKEN).
 * 출처는 'title #chunk' 문자열 목록(없으면 답변만).
 */
@Component
class SlackResponder(
    @Value("\${SLACK_BOT_TOKEN:}") botToken: String,
) : SlackResponseService {
    private val log = LoggerFactory.getLogger(javaClass)
    private val methods = Slack.getInstance().methods(botToken)

    override fun post(channel: String, threadTs: String, answer: String, sources: List<String>) {
        val text = buildString {
            append(answer)
            if (sources.isNotEmpty()) {
                append("\n\n*출처*")
                sources.forEach { append("\n• ").append(it) }
            }
        }
        runCatching {
            methods.chatPostMessage { it.channel(channel).threadTs(threadTs).text(text) }
        }.onSuccess { resp ->
            if (!resp.isOk) log.warn("chat.postMessage 실패: {}", resp.error)
        }.onFailure { log.error("chat.postMessage 예외 channel={}", channel, it) }
    }
}
