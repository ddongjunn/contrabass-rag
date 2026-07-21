package com.okestro.ragbot.common.config

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import org.springframework.web.servlet.config.annotation.CorsRegistry

class CorsConfigTest {

    @Test
    fun `allowed-origins가 설정되면 api-chat에 CORS 매핑이 등록된다`() {
        val props = AppProperties(cors = AppProperties.Cors(allowedOrigins = listOf("http://localhost:5173")))
        val registry = CorsRegistry()

        CorsConfig(props).addCorsMappings(registry)

        val config = registry.corsConfigurations["/api/chat"]
        assertEquals(listOf("http://localhost:5173"), config?.allowedOrigins)
        assertEquals(listOf("POST"), config?.allowedMethods)
    }

    @Test
    fun `allowed-origins가 설정되면 chat-widget 정적 리소스에도 CORS 매핑이 등록된다`() {
        // type="module" 스크립트(및 그 하위 import)는 크로스오리진이면 CORS가 없으면 브라우저가 로드 자체를
        // 차단한다 — 포털이 이 오리진에서 위젯 스크립트를 주입하므로 /api/chat과 별개로 필요하다.
        val props = AppProperties(cors = AppProperties.Cors(allowedOrigins = listOf("http://localhost:5173")))
        val registry = CorsRegistry()

        CorsConfig(props).addCorsMappings(registry)

        val config = registry.corsConfigurations["/chat-widget/**"]
        assertEquals(listOf("http://localhost:5173"), config?.allowedOrigins)
        assertEquals(listOf("GET"), config?.allowedMethods)
    }

    @Test
    fun `allowed-origins가 비어있으면 매핑을 등록하지 않는다(기본값 안전)`() {
        val props = AppProperties()
        val registry = CorsRegistry()

        CorsConfig(props).addCorsMappings(registry)

        assertNull(registry.corsConfigurations["/api/chat"])
        assertNull(registry.corsConfigurations["/chat-widget/**"])
    }

    /** Kotlin extension to access the protected corsConfigurations property via reflection */
    private val CorsRegistry.corsConfigurations: MutableMap<String?, org.springframework.web.cors.CorsConfiguration?>
        get() {
            val method = CorsRegistry::class.java.getDeclaredMethod("getCorsConfigurations")
            method.isAccessible = true
            @Suppress("UNCHECKED_CAST")
            return method.invoke(this) as MutableMap<String?, org.springframework.web.cors.CorsConfiguration?>
        }
}
