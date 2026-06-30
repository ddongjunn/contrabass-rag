package com.okestro.ragbot.resource

import com.okestro.ragbot.resource.application.InventorySql
import com.okestro.ragbot.resource.domain.InventoryFilters
import com.okestro.ragbot.resource.domain.InventoryKind
import com.okestro.ragbot.resource.domain.InventoryQuery
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * DB-2/DB-3: cb_common kind별 EAV 피벗 SQL 빌더 순수 단위테스트(DB 불필요).
 * 화이트리스트 SQL 골격(resource_type·key) + `?` 바인딩 순서/무력화(null) 검증.
 */
class InventorySqlTest {

    private val provider = "prov-1"
    private val limit = 50

    private fun build(
        kind: InventoryKind,
        mode: InventoryQuery.Mode = InventoryQuery.Mode.LIST,
        filters: InventoryFilters = InventoryFilters(),
    ) = InventorySql.build(InventoryQuery(kind, mode, filters), provider, limit)

    // --- INSTANCE (OS_VM) ---

    @Test
    fun `INSTANCE LIST 무필터 — SQL 골격과 파라미터 순서`() {
        val built = build(InventoryKind.INSTANCE)

        assertTrue(built.sql.contains("t.name = 'OS_VM'"), "OS_VM 타입 고정")
        assertTrue(built.sql.contains("p.uuid = ?"), "provider uuid 바인딩")
        assertTrue(built.sql.contains("ORDER BY r.resource_name"))
        assertTrue(built.sql.contains("LIMIT ?"))
        // [provider] + status EQ/NEQ(4) + host(2) + project/tenant(2) + [limit] = 10
        assertEquals(10, built.params.size)
        assertEquals(provider, built.params.first())
        assertEquals(limit, built.params.last())
        assertTrue(built.params.subList(1, 9).all { it == null }, "무필터면 HAVING 파라미터 모두 null")
    }

    @Test
    fun `INSTANCE status EQ — EQ 슬롯만 채워지고 NEQ는 null`() {
        val built = build(InventoryKind.INSTANCE, filters = InventoryFilters(status = "ACTIVE", statusOp = InventoryFilters.Op.EQ))
        assertEquals("ACTIVE", built.params[1])
        assertEquals("ACTIVE", built.params[2])
        assertEquals(null, built.params[3])
        assertEquals(null, built.params[4])
    }

    @Test
    fun `INSTANCE status NEQ — NEQ 슬롯만 채워지고 EQ는 null`() {
        val built = build(InventoryKind.INSTANCE, filters = InventoryFilters(status = "ACTIVE", statusOp = InventoryFilters.Op.NEQ))
        assertEquals(null, built.params[1])
        assertEquals(null, built.params[2])
        assertEquals("ACTIVE", built.params[3])
        assertEquals("ACTIVE", built.params[4])
    }

    @Test
    fun `INSTANCE hypervisor host 필터 바인딩`() {
        val built = build(InventoryKind.INSTANCE, filters = InventoryFilters(hypervisorHostName = "host-01"))
        assertEquals("host-01", built.params[5])
        assertEquals("host-01", built.params[6])
    }

    @Test
    fun `INSTANCE project는 tenant_id로 바인딩`() {
        val built = build(InventoryKind.INSTANCE, filters = InventoryFilters(projectId = "prod"))
        assertTrue(built.sql.contains("'tenant_id'"), "tenant_id 화이트리스트")
        assertEquals("prod", built.params[7])
        assertEquals("prod", built.params[8])
    }

    @Test
    fun `INSTANCE COUNT — count 래핑, LIMIT 없음, 파라미터 9개`() {
        val built = build(InventoryKind.INSTANCE, mode = InventoryQuery.Mode.COUNT, filters = InventoryFilters(status = "ERROR"))
        assertTrue(built.sql.contains("count(*)"))
        assertFalse(built.sql.contains("LIMIT"))
        assertEquals(9, built.params.size)
        assertEquals(provider, built.params.first())
        assertEquals("ERROR", built.params[1])
    }

    // --- VOLUME (OS_VOLUME) ---

    @Test
    fun `VOLUME — OS_VOLUME 타입, project_id로 필터, host 절 없음`() {
        val built = build(InventoryKind.VOLUME, mode = InventoryQuery.Mode.COUNT, filters = InventoryFilters(projectId = "prod"))
        assertTrue(built.sql.contains("t.name = 'OS_VOLUME'"))
        assertTrue(built.sql.contains("'project_id'"))
        assertFalse(built.sql.contains("hypervisor_host_name"), "볼륨엔 호스트 필터 없음")
        // [provider] + status(4) + project(2) = 7
        assertEquals(7, built.params.size)
        assertEquals("prod", built.params[5])
        assertEquals("prod", built.params[6])
    }

    // --- INSTANCE_SNAPSHOT (OS_VM_SNAPSHOT) ---

    @Test
    fun `INSTANCE_SNAPSHOT — status만 적용(project·host 절 없음)`() {
        val built = build(InventoryKind.INSTANCE_SNAPSHOT, mode = InventoryQuery.Mode.COUNT, filters = InventoryFilters(status = "active"))
        assertTrue(built.sql.contains("t.name = 'OS_VM_SNAPSHOT'"))
        // [provider] + status(4) = 5
        assertEquals(5, built.params.size)
        assertEquals("active", built.params[1])
    }

    // --- VOLUME_SNAPSHOT (OS_VOLUME_SNAPSHOT) ---

    @Test
    fun `VOLUME_SNAPSHOT — instanceCreateEnable는 문자열로 바인딩`() {
        val built = build(
            InventoryKind.VOLUME_SNAPSHOT,
            mode = InventoryQuery.Mode.COUNT,
            filters = InventoryFilters(status = "available", projectId = "prod", instanceCreateEnable = true),
        )
        assertTrue(built.sql.contains("t.name = 'OS_VOLUME_SNAPSHOT'"))
        assertTrue(built.sql.contains("'instance_create_enable'"))
        // [provider] + status(4) + project(2) + ice(2) = 9
        assertEquals(9, built.params.size)
        assertEquals("available", built.params[1])
        assertEquals("prod", built.params[5])
        assertEquals("true", built.params[7])
        assertEquals("true", built.params[8])
    }

    @Test
    fun `kind별 SQL은 화이트리스트 key만 포함`() {
        InventoryKind.entries.forEach { kind ->
            val built = build(kind)
            InventorySql.keysOf(kind).forEach { key ->
                assertTrue(built.sql.contains("'$key'"), "$kind: $key 화이트리스트 포함")
            }
        }
    }
}
