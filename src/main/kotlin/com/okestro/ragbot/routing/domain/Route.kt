package com.okestro.ragbot.routing.domain

/** 질문 라우트. DOC=문서(RAG), RESOURCE=인프라 조회(Prometheus), CLARIFY=되물음(폴백 겸용). */
enum class Route { DOC, RESOURCE, CLARIFY }
