package com.okestro.ragbot.chat.interfaces

import com.okestro.ragbot.chat.application.ChatCommand
import com.okestro.ragbot.chat.application.ChatResult
import com.okestro.ragbot.chat.application.ChatService
import com.okestro.ragbot.chat.application.StubChatService
import com.okestro.ragbot.chat.domain.ConversationMessage
import com.okestro.ragbot.common.config.AppProperties
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import kotlin.test.assertEquals

/** Phase 1 DoD: POST /api/chat → 스텁 응답. 외부 의존(OpenAI/DB) 없이 웹 슬라이스만 검증. */
@WebMvcTest(ChatController::class)
@Import(StubChatService::class, ChatControllerTest.TestConfig::class)
class ChatControllerTest(
    @Autowired val mockMvc: MockMvc,
) {
    @TestConfiguration
    @EnableConfigurationProperties(AppProperties::class)
    class TestConfig

    @Test
    fun `returns stub answer for posted question`() {
        mockMvc.perform(
            post("/api/chat")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"question":"hello","userId":"u1"}"""),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.answer").value("stub: hello"))
            .andExpect(jsonPath("$.sources").isArray)
    }

    @Test
    fun `project 필드가 있으면 ChatCommand로 전달된다`() {
        var received: ChatCommand? = null
        val capturing = object : ChatService {
            override fun handle(command: ChatCommand): ChatResult {
                received = command
                return ChatResult(answer = "ok", sources = emptyList())
            }
        }
        val controller = ChatController(capturing, AppProperties())

        controller.chat(ChatRequest(question = "쿼터 얼마나 썼어?", userId = "u1", project = "AUTOTEST"))

        assertEquals("AUTOTEST", received?.project)
    }

    @Test
    fun `project가 없으면 null로 전달된다(하위호환)`() {
        var received: ChatCommand? = null
        val capturing = object : ChatService {
            override fun handle(command: ChatCommand): ChatResult {
                received = command
                return ChatResult(answer = "ok", sources = emptyList())
            }
        }
        val controller = ChatController(capturing, AppProperties())

        controller.chat(ChatRequest(question = "질문", userId = "u1"))

        assertEquals(null, received?.project)
    }

    private fun capturing(receive: (ChatCommand) -> Unit) = object : ChatService {
        override fun handle(command: ChatCommand): ChatResult {
            receive(command)
            return ChatResult(answer = "ok", sources = emptyList())
        }
    }

    @Test
    fun `history가 ConversationMessage로 매핑되어 ChatCommand에 실린다`() {
        var received: ChatCommand? = null
        val controller = ChatController(
            capturing { received = it },
            AppProperties(router = AppProperties.Router(historyTurns = 3)),
        )

        controller.chat(
            ChatRequest(
                question = "admin 프로젝트만 보여줘", userId = "u1",
                history = listOf(
                    ChatRequest.HistoryMessage("user", "CPU 사용률 TopN"),
                    ChatRequest.HistoryMessage("assistant", "CPU 사용률이 높은 인스턴스입니다"),
                ),
            ),
        )

        assertEquals(2, received?.history?.size)
        assertEquals(ConversationMessage.Role.USER, received?.history?.first()?.role)
        assertEquals(ConversationMessage.Role.ASSISTANT, received?.history?.last()?.role)
        assertEquals("CPU 사용률이 높은 인스턴스입니다", received?.history?.last()?.content)
    }

    @Test
    fun `history는 Slack과 동일하게 historyTurns-1개로 잘린다 - 최근 것만 남는다`() {
        var received: ChatCommand? = null
        val controller = ChatController(
            capturing { received = it },
            AppProperties(router = AppProperties.Router(historyTurns = 2)),
        )

        controller.chat(
            ChatRequest(
                question = "q", userId = "u1",
                history = listOf(
                    ChatRequest.HistoryMessage("user", "옛날 질문"),
                    ChatRequest.HistoryMessage("assistant", "옛날 답"),
                    ChatRequest.HistoryMessage("assistant", "직전 답"),
                ),
            ),
        )

        assertEquals(listOf("직전 답"), received?.history?.map { it.content })
    }

    @Test
    fun `빈 content는 걸러지고 모르는 role은 USER로 취급된다`() {
        var received: ChatCommand? = null
        val controller = ChatController(capturing { received = it }, AppProperties())

        controller.chat(
            ChatRequest(
                question = "q", userId = "u1",
                history = listOf(
                    ChatRequest.HistoryMessage("assistant", "  "),
                    ChatRequest.HistoryMessage("system", "이상한 role"),
                ),
            ),
        )

        assertEquals(1, received?.history?.size)
        assertEquals(ConversationMessage.Role.USER, received?.history?.first()?.role)
    }

    @Test
    fun `history가 없으면 빈 목록(하위호환)`() {
        var received: ChatCommand? = null
        val controller = ChatController(capturing { received = it }, AppProperties())

        controller.chat(ChatRequest(question = "q", userId = "u1"))

        assertEquals(emptyList(), received?.history)
    }
}
