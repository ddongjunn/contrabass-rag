package com.okestro.ragbot.resource.infrastructure

import com.okestro.ragbot.resource.application.InventoryRepository
import com.okestro.ragbot.resource.application.InventorySql
import com.okestro.ragbot.resource.domain.InventoryQuery
import com.okestro.ragbot.resource.domain.InventoryResult
import com.okestro.ragbot.resource.domain.InventoryRow
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository

/**
 * cb_common(MySQL, 읽기 전용) INVENTORY 리포지토리. SQL은 [InventorySql] 화이트리스트 템플릿만 사용하고
 * 사용자/LLM 값은 `?` 바인딩으로만 전달한다. inventory.enabled=true(2차 DataSource 존재) 시에만 빈 생성.
 */
@Repository
@ConditionalOnProperty(prefix = "app.resource.inventory", name = ["enabled"], havingValue = "true")
class CbCommonInventoryRepository(
    @Qualifier("cbCommonJdbcTemplate") private val jdbc: JdbcTemplate,
) : InventoryRepository {

    override fun find(query: InventoryQuery, providerUuid: String, limit: Int): InventoryResult {
        val built = InventorySql.build(query, providerUuid, limit)
        val params = built.params.toTypedArray()

        return when (query.mode) {
            InventoryQuery.Mode.COUNT -> {
                val total = jdbc.queryForObject(built.sql, Int::class.java, *params) ?: 0
                InventoryResult(query.kind, emptyList(), total, query.filters)
            }

            InventoryQuery.Mode.LIST -> {
                val keys = InventorySql.keysOf(query.kind)
                val rows = jdbc.query(built.sql, { rs, _ ->
                    InventoryRow(
                        uuid = rs.getString("uuid"),
                        name = rs.getString("resource_name"),
                        attrs = keys.associateWith { rs.getString(it) },
                    )
                }, *params)
                InventoryResult(query.kind, rows, rows.size, query.filters)
            }
        }
    }
}
