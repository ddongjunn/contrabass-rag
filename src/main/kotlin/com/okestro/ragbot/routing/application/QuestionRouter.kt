package com.okestro.ragbot.routing.application

import com.okestro.ragbot.chat.domain.ConversationMessage
import com.okestro.ragbot.routing.domain.RouteDecision

/** 교체 가능한 라우터 포트. history의 마지막 원소가 현재 질문(USER)이다. */
interface QuestionRouter {
    fun route(history: List<ConversationMessage>): RouteDecision
}
