package com.okestro.ragbot.chat.application

import org.springframework.stereotype.Service

/**
 * Phase 1 스텁 — 고정 에코 응답. Phase 3에서 실제 파이프라인 구현으로 교체한다.
 */
@Service
class StubChatService : ChatService {
    override fun handle(command: ChatCommand): ChatResult =
        ChatResult(answer = "stub: ${command.question}", sources = emptyList())
}
