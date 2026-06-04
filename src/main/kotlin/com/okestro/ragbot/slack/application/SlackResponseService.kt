package com.okestro.ragbot.slack.application

/** Slack 채널 스레드(thread_ts)에 답변+출처를 게시. */
interface SlackResponseService {
    fun post(channel: String, threadTs: String, answer: String, sources: List<String>)
}
