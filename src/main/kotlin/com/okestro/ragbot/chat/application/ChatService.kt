package com.okestro.ragbot.chat.application

/**
 * 파이프라인 오케스트레이터(단일 진입점). REST(chat/interfaces)와 Slack(slack/interfaces)이
 * 모두 이 하나를 호출한다. 임베딩→캐시→검색→생성→저장 순서는 Phase 2~4에서 채운다.
 */
interface ChatService {
    fun handle(command: ChatCommand): ChatResult
}
