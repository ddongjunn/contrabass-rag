package com.okestro.ragbot.resource.infrastructure

import com.okestro.ragbot.common.config.AppProperties
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.jdbc.DataSourceBuilder
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Primary
import org.springframework.jdbc.core.JdbcTemplate
import javax.sql.DataSource

/**
 * INVENTORY(cb_common, MySQL) 2차 DataSource 구성. `app.resource.inventory.enabled=true`일 때만 활성화된다
 * (미설정 시 빈 미생성 → 로컬/CI는 documents(PG) 단일 DataSource 자동구성 그대로 부팅).
 *
 * 주의: DataSource 빈을 직접 정의하면 Spring Boot의 단일 DataSource 자동구성이 백오프된다.
 * 따라서 활성화 시 documents(PG) DataSource·JdbcTemplate을 **@Primary**로 명시해 기존 pgvector·문서검색이 그대로 동작하게 한다.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "app.resource.inventory", name = ["enabled"], havingValue = "true")
class CbCommonDataSourceConfig {

    // --- documents (PostgreSQL, 기존 @Primary 보존) ---
    @Bean
    @Primary
    @ConfigurationProperties("spring.datasource")
    fun dataSourceProperties(): DataSourceProperties = DataSourceProperties()

    @Bean
    @Primary
    fun dataSource(properties: DataSourceProperties): DataSource =
        properties.initializeDataSourceBuilder().build()

    @Bean
    @Primary
    fun jdbcTemplate(@Qualifier("dataSource") dataSource: DataSource): JdbcTemplate =
        JdbcTemplate(dataSource)

    // --- cb_common (MySQL, 신규 — 읽기 전용 조회) ---
    @Bean(name = ["cbCommonDataSource"])
    fun cbCommonDataSource(props: AppProperties): DataSource {
        val db = props.resource.inventory.db
        return DataSourceBuilder.create()
            .url(db.url)
            .username(db.username)
            .password(db.password)
            .driverClassName(db.driverClassName)
            .build()
    }

    @Bean(name = ["cbCommonJdbcTemplate"])
    fun cbCommonJdbcTemplate(
        @Qualifier("cbCommonDataSource") dataSource: DataSource,
        props: AppProperties,
    ): JdbcTemplate {
        val jdbc = JdbcTemplate(dataSource)
        jdbc.queryTimeout = (props.resource.inventory.queryTimeoutMs / 1000).coerceAtLeast(1)
        return jdbc
    }
}
