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
    fun `allowed-origins가 비어있으면 매핑을 등록하지 않는다(기본값 안전)`() {
        val props = AppProperties()
        val registry = CorsRegistry()

        CorsConfig(props).addCorsMappings(registry)

        assertNull(registry.corsConfigurations["/api/chat"])
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
