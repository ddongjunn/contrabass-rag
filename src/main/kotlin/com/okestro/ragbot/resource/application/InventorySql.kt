package com.okestro.ragbot.resource.application

import com.okestro.ragbot.resource.domain.InventoryFilters
import com.okestro.ragbot.resource.domain.InventoryKind
import com.okestro.ragbot.resource.domain.InventoryQuery

/**
 * cb_common EAV 피벗 SQL 빌더(순수 함수 — Spring/DB 무관, 단위테스트 대상).
 *
 * 안전 원칙(불변식 5): resource_type 이름·SELECT/JOIN key 목록·필터 컬럼은 모두 **코드 상수(화이트리스트)** 이고,
 * 사용자·LLM 값은 오직 `?` 바인딩으로만 들어간다 → injection·환각 불가. LLM은 SQL을 만들지 않는다.
 *
 * 필터는 `(? IS NULL OR <pivot> <op> ?)` 패턴이라 null이면 무력화된다(파라미터를 두 번 바인딩).
 * kind마다 적용 가능한 필터만 HAVING에 더한다(예: 호스트=인스턴스, instanceCreateEnable=볼륨 스냅샷).
 */
object InventorySql {

    /** 빌드 결과: 실행할 SQL과 순서대로 바인딩할 파라미터. */
    data class Built(val sql: String, val params: List<Any?>)

    private const val STATUS = "status"

    /** kind → cm_resource_type.name (조회 대상 고정). */
    private val TYPE_NAME = mapOf(
        InventoryKind.INSTANCE to "OS_VM",
        InventoryKind.INSTANCE_SNAPSHOT to "OS_VM_SNAPSHOT",
        InventoryKind.VOLUME to "OS_VOLUME",
        InventoryKind.VOLUME_SNAPSHOT to "OS_VOLUME_SNAPSHOT",
    )

    /** kind → 노출(피벗)할 속성 화이트리스트. SELECT·LEFT JOIN IN 절·결과 attrs에 동일 사용. */
    private val KEYS = mapOf(
        InventoryKind.INSTANCE to listOf("status", "power_state", "hypervisor_host_name", "tenant_id"),
        InventoryKind.INSTANCE_SNAPSHOT to listOf("status", "owner", "visibility", "created_at"),
        InventoryKind.VOLUME to listOf("status", "size", "project_id", "bootable", "os_vol_host_attr_host"),
        InventoryKind.VOLUME_SNAPSHOT to listOf("status", "size", "project_id", "volume_id", "instance_create_enable"),
    )

    fun keysOf(kind: InventoryKind): List<String> = KEYS.getValue(kind)

    /**
     * kind/mode/filters로 조회 SQL을 만든다. LIST=피벗 행+LIMIT, COUNT=동일 조건 건수.
     * 파라미터 순서: [providerUuid] + 적용 필터들(status EQ/NEQ + kind별 추가) (+ LIST면 limit).
     */
    fun build(query: InventoryQuery, providerUuid: String, limit: Int): Built {
        val kind = query.kind
        val f = query.filters
        val keys = KEYS.getValue(kind)
        val typeName = TYPE_NAME.getValue(kind)

        fun pivot(key: String) = "MAX(CASE WHEN a.`key` = '$key' THEN a.value END)"

        val clauses = mutableListOf<String>()
        val params = mutableListOf<Any?>()
        fun addEq(key: String, value: Any?) {
            clauses += "(? IS NULL OR ${pivot(key)} = ?)"
            params += value; params += value
        }

        // status: statusOp에 따라 EQ 또는 NEQ 슬롯 하나만 채워진다(나머지는 null → 무력화). 모든 kind에 status 존재.
        val statusEq = f.status?.takeIf { f.statusOp == InventoryFilters.Op.EQ }
        val statusNeq = f.status?.takeIf { f.statusOp == InventoryFilters.Op.NEQ }
        addEq(STATUS, statusEq)
        clauses += "(? IS NULL OR ${pivot(STATUS)} <> ?)"
        params += statusNeq; params += statusNeq

        // hypervisor host: 인스턴스에만 해당 컬럼 존재.
        if ("hypervisor_host_name" in keys) addEq("hypervisor_host_name", f.hypervisorHostName)

        // project: 인스턴스=tenant_id, 볼륨/볼륨스냅샷=project_id 로 매핑.
        val projectKey = when {
            "tenant_id" in keys -> "tenant_id"
            "project_id" in keys -> "project_id"
            else -> null
        }
        if (projectKey != null) addEq(projectKey, f.projectId)

        // instanceCreateEnable: 볼륨 스냅샷 전용. EAV value는 문자열이라 'true'/'false'로 비교.
        if ("instance_create_enable" in keys) addEq("instance_create_enable", f.instanceCreateEnable?.toString())

        val having = "HAVING " + clauses.joinToString("\n   AND ")
        val from = """
            FROM cm_resource r
            JOIN cm_resource_type t ON t.id = r.resource_type_id AND t.name = '$typeName'
            JOIN cm_provider p      ON p.id = r.provider_id      AND p.uuid = ?
            LEFT JOIN cm_resource_attr a
                   ON a.resource_id = r.id
                  AND a.`key` IN (${keys.joinToString(", ") { "'$it'" }})
        """.trimIndent()

        return when (query.mode) {
            InventoryQuery.Mode.LIST -> {
                val select = keys.joinToString(",\n       ") { "${pivot(it)} AS $it" }
                val sql = """
                    SELECT r.uuid, r.resource_name,
                       $select
                    $from
                    GROUP BY r.id, r.uuid, r.resource_name
                    $having
                    ORDER BY r.resource_name
                    LIMIT ?
                """.trimIndent()
                Built(sql, listOf<Any?>(providerUuid) + params + listOf<Any?>(limit))
            }

            InventoryQuery.Mode.COUNT -> {
                val sql = """
                    SELECT count(*) FROM (
                        SELECT r.id
                        $from
                        GROUP BY r.id
                        $having
                    ) sub
                """.trimIndent()
                Built(sql, listOf<Any?>(providerUuid) + params)
            }
        }
    }
}
