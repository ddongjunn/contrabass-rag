package com.okestro.ragbot.slack.interfaces

import com.okestro.ragbot.chat.application.ChatCommand
import com.okestro.ragbot.chat.application.ChatService
import com.okestro.ragbot.slack.application.SlackResponseService
import com.slack.api.bolt.App
import com.slack.api.bolt.AppConfig
import com.slack.api.bolt.socket_mode.SocketModeApp
import com.slack.api.model.event.AppMentionEvent
import com.slack.api.socket_mode.SocketModeClient
import jakarta.annotation.PreDestroy
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Slack Socket Mode 수신기: `app_mention` 수신 → 즉시 ack(3초 제약) → 봇 메시지(`bot_id`) 드롭(루프 차단) →
 * 가상스레드에서 비동기로 [ChatService] 실행 후 원 스레드에 답변+출처 게시([SlackResponseService]).
 * 토큰은 환경변수만(SLACK_BOT_TOKEN/SLACK_APP_TOKEN). 미설정 시 Slack 비활성(REST `/api/chat`는 그대로 동작).
 */
@Component
class SocketModeRunner(
    @Value("\${SLACK_BOT_TOKEN:}") private val botToken: String,
    @Value("\${SLACK_APP_TOKEN:}") private val appToken: String,
    private val chatService: ChatService,
    private val slackResponseService: SlackResponseService,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val mentionRegex = Regex("<@[^>]+>")
    private val executor: ExecutorService = Executors.newVirtualThreadPerTaskExecutor()
    private var socketModeApp: SocketModeApp? = null

    @EventListener(ApplicationReadyEvent::class)
    fun start() {
        if (botToken.isBlank() || appToken.isBlank()) {
            log.warn("SLACK_BOT_TOKEN/SLACK_APP_TOKEN 미설정 — Slack Socket Mode 비활성(REST만 동작)")
            return
        }
        val app = App(AppConfig.builder().singleTeamBotToken(botToken).build())
        app.event(AppMentionEvent::class.java) { payload, ctx ->
            val event = payload.event
            if (event.botId != null) {                       // 봇/자기 메시지 → 드롭(루프 차단)
                log.info("slack drop bot message botId={} channel={}", event.botId, event.channel)
                return@event ctx.ack()
            }
            val question = event.text.orEmpty().replace(mentionRegex, "").trim()
            val userId = event.user ?: "unknown"
            val channel = event.channel
            val threadTs = event.threadTs ?: event.ts        // 멘션이 최상위면 그 ts로 스레드 시작
            log.info("slack app_mention user={} channel={} len={}", userId, channel, question.length)
            executor.submit { handle(question, userId, channel, threadTs) }  // ack는 즉시, RAG는 비동기
            ctx.ack()
        }
        runCatching { SocketModeApp(appToken, app, SocketModeClient.Backend.JavaWebSocket).also { it.startAsync() } }
            .onSuccess { socketModeApp = it; log.info("Slack Socket Mode 시작됨") }
            .onFailure { log.error("Slack Socket Mode 시작 실패", it) }
    }

    private fun handle(question: String, userId: String, channel: String, threadTs: String) {
        try {
            val result = chatService.handle(ChatCommand(question = question, userId = userId))
            slackResponseService.post(channel, threadTs, result.answer, result.sources)
        } catch (e: Exception) {
            log.error("slack 처리 실패 user={}", userId, e)
            slackResponseService.post(channel, threadTs, "일시적으로 응답할 수 없습니다. 잠시 후 다시 시도해 주세요.", emptyList())
        }
    }

    @PreDestroy
    fun stop() {
        socketModeApp?.let { app -> runCatching { app.stop() }.onFailure { log.warn("Socket Mode 종료 실패", it) } }
        executor.shutdown()
    }
}
