package com.okestro.ragbot.resource.application

import com.okestro.ragbot.resource.domain.ResourceExtraction
import com.okestro.ragbot.chat.domain.ConversationMessage

interface MetricQueryExtractor {
    fun extract(history: List<ConversationMessage>): ResourceExtraction
}
