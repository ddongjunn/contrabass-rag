package com.okestro.ragbot.common.web

import io.github.resilience4j.circuitbreaker.CallNotPermittedException
import org.slf4j.LoggerFactory
import org.springframework.ai.retry.TransientAiException
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.client.ResourceAccessException

/** 전역 예외 처리 — 미처리 예외를 안전한 본문으로 변환하고 로깅한다. */
@RestControllerAdvice
class GlobalExceptionHandler {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * OpenAI 장애/타임아웃(재시도 소진) 또는 CircuitBreaker 오픈 → 503 + 안내.
     * 비싼 호출이 이미 실패/차단된 상태라 추가 비용 없음.
     */
    @ExceptionHandler(TransientAiException::class, ResourceAccessException::class, CallNotPermittedException::class)
    fun handleUpstreamUnavailable(e: Exception): ResponseEntity<Map<String, Any>> {
        log.warn("OpenAI 호출 불가(재시도 소진/서킷 오픈): {}", e.javaClass.simpleName)
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
            .body(mapOf("answer" to "일시적으로 응답할 수 없습니다. 잠시 후 다시 시도해 주세요.", "sources" to emptyList<String>()))
    }

    @ExceptionHandler(Exception::class)
    fun handle(e: Exception): ResponseEntity<Map<String, String>> {
        log.error("unhandled exception", e)
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
            .body(mapOf("error" to "internal_error"))
    }
}
