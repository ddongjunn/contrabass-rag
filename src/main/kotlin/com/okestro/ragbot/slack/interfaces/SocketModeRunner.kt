package com.okestro.ragbot.slack.interfaces

import com.okestro.ragbot.chat.application.ChatCommand
import com.okestro.ragbot.chat.application.ChatService
import com.okestro.ragbot.chat.domain.ConversationMessage
import com.okestro.ragbot.common.config.AppProperties
import com.okestro.ragbot.slack.application.SlackResponseService
import com.slack.api.bolt.App
import com.slack.api.bolt.AppConfig
import com.slack.api.bolt.socket_mode.SocketModeApp
import com.slack.api.methods.MethodsClient
import com.slack.api.model.event.AppMentionEvent
import com.slack.api.model.event.MessageEvent
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
 * Slack Socket Mode 수신기: 채널 app_mention + DM 수신 → 즉시 ack → 가상스레드에서 비동기 처리.
 * 멘션이 스레드 안에 있으면 conversations.replies로 이전 메시지를 조회해 ChatCommand.history에 담는다.
 * 루트 메시지(첫 멘션)는 히스토리 없이 처리(단발성과 동일).
 */
@Component
class SocketModeRunner(
    @Value("\${SLACK_BOT_TOKEN:}") private val botToken: String,
    @Value("\${SLACK_APP_TOKEN:}") private val appToken: String,
    private val chatService: ChatService,
    private val slackResponseService: SlackResponseService,
    private val props: AppProperties,
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
            if (event.botId != null) {
                log.info("slack drop bot message botId={} channel={}", event.botId, event.channel)
                return@event ctx.ack()
            }
            val question = event.text.orEmpty().replace(mentionRegex, "").trim()
            val userId = event.user ?: "unknown"
            val channel = event.channel
            val currentTs = event.ts
            val threadTs = event.threadTs ?: event.ts
            val client = ctx.client()
            log.info("slack app_mention user={} channel={} thread={} len={}", userId, channel, threadTs, question.length)
            executor.submit { handle(question, userId, channel, threadTs, currentTs, client) }
            ctx.ack()
        }

        app.event(MessageEvent::class.java) { payload, ctx ->
            val event = payload.event
            if (event.botId != null) return@event ctx.ack()
            if (event.channelType != "im") return@event ctx.ack()
            val question = event.text.orEmpty().replace(mentionRegex, "").trim()
            val userId = event.user ?: "unknown"
            val currentTs = event.ts
            val threadTs = event.threadTs ?: event.ts
            val client = ctx.client()
            log.info("slack dm user={} channel={} len={}", userId, event.channel, question.length)
            executor.submit { handle(question, userId, event.channel, threadTs, currentTs, client) }
            ctx.ack()
        }

        runCatching { SocketModeApp(appToken, app, SocketModeClient.Backend.JavaWebSocket).also { it.startAsync() } }
            .onSuccess { socketModeApp = it; log.info("Slack Socket Mode 시작됨") }
            .onFailure { log.error("Slack Socket Mode 시작 실패", it) }
    }

    private fun handle(
        question: String,
        userId: String,
        channel: String,
        threadTs: String,
        currentTs: String,
        client: MethodsClient,
    ) {
        try {
            val history = if (currentTs != threadTs) fetchThreadHistory(client, channel, threadTs, currentTs)
                          else emptyList()
            log.info("slack handle user={} historySize={}", userId, history.size)
            val result = chatService.handle(ChatCommand(question = question, userId = userId, history = history))
            slackResponseService.post(channel, threadTs, result.answer, result.sources)
        } catch (e: Exception) {
            log.error("slack 처리 실패 user={}", userId, e)
            slackResponseService.post(channel, threadTs, "일시적으로 응답할 수 없습니다. 잠시 후 다시 시도해 주세요.", emptyList())
        }
    }

    private fun fetchThreadHistory(
        client: MethodsClient,
        channel: String,
        threadTs: String,
        currentTs: String,
    ): List<ConversationMessage> {
        val want = (props.router.historyTurns - 1).coerceAtLeast(0)
        if (want == 0) return emptyList()

        return try {
            val result = client.conversationsReplies { req ->
                req.channel(channel).ts(threadTs).limit(want + 10)
            }
            if (!result.isOk) {
                log.warn("conversations.replies 실패: error={}", result.error)
                return emptyList()
            }
            result.messages
                ?.filter { it.ts != currentTs }       // 현재 질문 제외 (DefaultChatService가 추가)
                ?.takeLast(want)
                ?.mapNotNull { msg ->
                    val text = msg.text.orEmpty().replace(mentionRegex, "").trim()
                    if (text.isBlank()) null
                    else ConversationMessage(
                        role = if (msg.botId != null) ConversationMessage.Role.ASSISTANT
                               else ConversationMessage.Role.USER,
                        content = text,
                    )
                }
                ?: emptyList()
        } catch (e: Exception) {
            log.warn("스레드 히스토리 조회 실패: {}", e.message)
            emptyList()
        }
    }

    @PreDestroy
    fun stop() {
        socketModeApp?.let { app -> runCatching { app.stop() }.onFailure { log.warn("Socket Mode 종료 실패", it) } }
        executor.shutdown()
    }
}
