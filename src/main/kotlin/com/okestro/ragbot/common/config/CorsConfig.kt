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
    }
}
