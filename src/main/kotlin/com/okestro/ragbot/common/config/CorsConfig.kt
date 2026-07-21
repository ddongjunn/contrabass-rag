package com.okestro.ragbot.common.config

import org.springframework.context.annotation.Configuration
import org.springframework.web.servlet.config.annotation.CorsRegistry
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer

/**
 * app.cors.allowed-origins가 비어있으면(기본값) 매핑을 등록하지 않는다 — 브라우저 크로스오리진
 * 호출이 지금처럼 차단되는 안전한 기본 동작을 유지한다(불변식 7: 하드코딩 금지, yml로만 조정).
 */
@Configuration
class CorsConfig(private val props: AppProperties) : WebMvcConfigurer {

    override fun addCorsMappings(registry: CorsRegistry) {
        if (props.cors.allowedOrigins.isEmpty()) return
        registry.addMapping("/api/chat")
            .allowedOrigins(*props.cors.allowedOrigins.toTypedArray())
            .allowedMethods("POST")
            .allowedHeaders("Content-Type")
        // type="module" 스크립트(및 import로 딸려오는 하위 모듈)는 크로스오리진이면 CORS 헤더가 없으면
        // 브라우저가 로드 자체를 막는다 — 포털이 이 오리진에서 위젯 스크립트를 주입하므로 별도로 열어야 한다.
        registry.addMapping("/chat-widget/**")
            .allowedOrigins(*props.cors.allowedOrigins.toTypedArray())
            .allowedMethods("GET")
    }
}
