package com.okestro.ragbot.chat.interfaces

import com.okestro.ragbot.chat.application.StubChatService
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

/** Phase 1 DoD: POST /api/chat → 스텁 응답. 외부 의존(OpenAI/DB) 없이 웹 슬라이스만 검증. */
@WebMvcTest(ChatController::class)
@Import(StubChatService::class)
class ChatControllerTest(
    @Autowired val mockMvc: MockMvc,
) {
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
}
