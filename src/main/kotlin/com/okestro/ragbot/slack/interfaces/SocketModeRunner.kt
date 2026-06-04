package com.okestro.ragbot.slack.interfaces

import com.slack.api.bolt.App
import com.slack.api.bolt.AppConfig
import com.slack.api.bolt.socket_mode.SocketModeApp
import com.slack.api.model.event.AppMentionEvent
import jakarta.annotation.PreDestroy
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component

/**
 * Slack Socket Mode 수신기(6-a): `app_mention` 수신 → 즉시 ack → 봇 메시지(`bot_id`)는 드롭(무한 루프·비용 폭주 차단).
 * 토큰은 환경변수만(SLACK_BOT_TOKEN/SLACK_APP_TOKEN). 미설정 시 Slack 비활성(REST `/api/chat`는 그대로 동작).
 * 6-b에서 ChatService 비동기 호출·답변(출처) 게시로 확장한다(현재는 수신 확인용 고정 응답).
 */
@Component
class SocketModeRunner(
    @Value("\${SLACK_BOT_TOKEN:}") private val botToken: String,
    @Value("\${SLACK_APP_TOKEN:}") private val appToken: String,
) {
    private val log = LoggerFactory.getLogger(javaClass)
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
            log.info("slack app_mention user={} channel={} ts={} len={}", event.user, event.channel, event.ts, event.text?.length)
            runCatching { ctx.say("✅ 멘션을 받았어요(개발 확인용). 답변 연결은 6-b 단계입니다.") }
                .onFailure { log.warn("slack say 실패", it) }
            ctx.ack()
        }
        runCatching { SocketModeApp(appToken, app).also { it.startAsync() } }
            .onSuccess { socketModeApp = it; log.info("Slack Socket Mode 시작됨") }
            .onFailure { log.error("Slack Socket Mode 시작 실패", it) }
    }

    @PreDestroy
    fun stop() {
        socketModeApp?.let { app -> runCatching { app.stop() }.onFailure { log.warn("Socket Mode 종료 실패", it) } }
    }
}
