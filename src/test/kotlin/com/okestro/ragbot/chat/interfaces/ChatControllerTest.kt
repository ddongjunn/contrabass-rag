package com.okestro.ragbot.chat.interfaces

import com.okestro.ragbot.chat.application.ChatCommand
import com.okestro.ragbot.chat.application.ChatResult
import com.okestro.ragbot.chat.application.ChatService
import com.okestro.ragbot.chat.application.StubChatService
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
        val controller = ChatController(capturing)

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
        val controller = ChatController(capturing)

        controller.chat(ChatRequest(question = "질문", userId = "u1"))

        assertEquals(null, received?.project)
    }
}
