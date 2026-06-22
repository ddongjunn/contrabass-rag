package com.okestro.ragbot.routing

import com.okestro.ragbot.routing.application.LlmClient
import com.okestro.ragbot.routing.application.LlmRequest

/** LLM 응답이 "임의로 온다"고 가정하고, 테스트가 지정한 static 응답을 그대로 돌려주는 스텁. */
class StubLlmClient(var response: String) : LlmClient {
    var lastRequest: LlmRequest? = null
    override fun complete(request: LlmRequest): String {
        lastRequest = request
        return response
    }
}
