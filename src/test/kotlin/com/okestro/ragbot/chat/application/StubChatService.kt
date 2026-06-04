package com.okestro.ragbot.chat.application

/**
 * 웹 슬라이스 테스트 전용 스텁(고정 에코). 런타임 구현은 DefaultChatService(Phase 2-b).
 * @WebMvcTest 가 @Import 로 등록한다 — @Service 불필요(컴포넌트 스캔 대상 아님).
 */
class StubChatService : ChatService {
    override fun handle(command: ChatCommand): ChatResult =
        ChatResult(answer = "stub: ${command.question}", sources = emptyList())
}
