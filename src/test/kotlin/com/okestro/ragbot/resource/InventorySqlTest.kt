package com.okestro.ragbot.resource

import com.okestro.ragbot.resource.application.InventorySql
import com.okestro.ragbot.resource.domain.InventoryFilters
import com.okestro.ragbot.resource.domain.InventoryQuery
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * DB-2: cb_common 인스턴스(OS_VM) 피벗 SQL 빌더 순수 단위테스트(DB 불필요).
 * 화이트리스트 SQL 골격 + `?` 바인딩 순서/무력화(null) 검증.
 */
class InventorySqlTest {

    private val provider = "prov-1"
    private val limit = 50

    private fun list(filters: InventoryFilters) =
        InventorySql.instances(filters, InventoryQuery.Mode.LIST, provider, limit)

    private fun count(filters: InventoryFilters) =
        InventorySql.instances(filters, InventoryQuery.Mode.COUNT, provider, limit)

    @Test
    fun `LIST 무필터 — SQL 골격과 파라미터 순서`() {
        val built = list(InventoryFilters())

        assertTrue(built.sql.contains("t.name = 'OS_VM'"), "OS_VM 타입 고정")
        assertTrue(built.sql.contains("p.uuid = ?"), "provider uuid 바인딩")
        assertTrue(built.sql.contains("ORDER BY r.resource_name"))
        assertTrue(built.sql.contains("LIMIT ?"))
        // [provider] + HAVING(8) + [limit] = 10
        assertEquals(10, built.params.size)
        assertEquals(provider, built.params.first())
        assertEquals(limit, built.params.last())
        // 무필터면 HAVING 파라미터는 모두 null → 모든 조건 무력화
        assertTrue(built.params.subList(1, 9).all { it == null })
    }

    @Test
    fun `status EQ — EQ 슬롯만 채워지고 NEQ는 null`() {
        val built = list(InventoryFilters(status = "ACTIVE", statusOp = InventoryFilters.Op.EQ))
        // havingParams: [statusEq, statusEq, statusNeq, statusNeq, host, host, project, project]
        assertEquals("ACTIVE", built.params[1])
        assertEquals("ACTIVE", built.params[2])
        assertEquals(null, built.params[3])
        assertEquals(null, built.params[4])
    }

    @Test
    fun `status NEQ — NEQ 슬롯만 채워지고 EQ는 null`() {
        val built = list(InventoryFilters(status = "ACTIVE", statusOp = InventoryFilters.Op.NEQ))
        assertEquals(null, built.params[1])
        assertEquals(null, built.params[2])
        assertEquals("ACTIVE", built.params[3])
        assertEquals("ACTIVE", built.params[4])
    }

    @Test
    fun `hypervisor host 필터 바인딩`() {
        val built = list(InventoryFilters(hypervisorHostName = "host-01"))
        assertEquals("host-01", built.params[5])
        assertEquals("host-01", built.params[6])
    }

    @Test
    fun `project(tenant) 필터 바인딩`() {
        val built = list(InventoryFilters(projectId = "prod"))
        assertEquals("prod", built.params[7])
        assertEquals("prod", built.params[8])
    }

    @Test
    fun `COUNT 모드 — count 래핑, LIMIT 없음, 파라미터 9개`() {
        val built = count(InventoryFilters(status = "ERROR"))
        assertTrue(built.sql.contains("count(*)"), "건수 집계")
        assertFalse(built.sql.contains("LIMIT"), "COUNT엔 LIMIT 없음")
        // [provider] + HAVING(8) = 9
        assertEquals(9, built.params.size)
        assertEquals(provider, built.params.first())
        assertEquals("ERROR", built.params[1])
    }

    @Test
    fun `SQL은 화이트리스트 key만 포함`() {
        val built = list(InventoryFilters())
        InventorySql.INSTANCE_KEYS.forEach { key ->
            assertTrue(built.sql.contains("'$key'"), "$key 화이트리스트 포함")
        }
    }
}
