package com.okestro.ragbot.resource.application

import com.okestro.ragbot.resource.domain.InventoryFilters
import com.okestro.ragbot.resource.domain.InventoryQuery

/**
 * cb_common EAV 피벗 SQL 빌더(순수 함수 — Spring/DB 무관, 단위테스트 대상).
 *
 * 안전 원칙(불변식 5): SELECT/JOIN/key 목록·resource_type 이름은 **코드 상수(화이트리스트)** 이고,
 * 사용자·LLM 값은 오직 `?` 바인딩으로만 들어간다 → injection·환각 불가. LLM은 SQL을 만들지 않는다.
 *
 * 필터는 `(? IS NULL OR <pivot> = ?)` 패턴이라, null이면 무력화된다(파라미터를 두 번 바인딩).
 */
object InventorySql {

    /** 빌드 결과: 실행할 SQL과 순서대로 바인딩할 파라미터. */
    data class Built(val sql: String, val params: List<Any?>)

    private const val OS_VM = "OS_VM"

    /** OS_VM 피벗에서 노출하는 속성 화이트리스트(SELECT·LEFT JOIN IN 절에 동일 사용). */
    val INSTANCE_KEYS: List<String> = listOf("status", "power_state", "hypervisor_host_name", "tenant_id")

    /**
     * 인스턴스(OS_VM) 조회 SQL. mode=LIST면 피벗 행 + LIMIT, COUNT면 동일 조건 건수.
     * 파라미터 순서: [providerUuid] + HAVING(8개) (+ LIST면 limit).
     */
    fun instances(
        filters: InventoryFilters,
        mode: InventoryQuery.Mode,
        providerUuid: String,
        limit: Int,
    ): Built {
        // status는 statusOp에 따라 EQ 또는 NEQ 슬롯 하나만 채워진다(나머지는 null → 무력화).
        val statusEq = filters.status?.takeIf { filters.statusOp == InventoryFilters.Op.EQ }
        val statusNeq = filters.status?.takeIf { filters.statusOp == InventoryFilters.Op.NEQ }
        val host = filters.hypervisorHostName
        val project = filters.projectId   // 인스턴스에서는 tenant_id에 대응

        val pivot = { key: String -> "MAX(CASE WHEN a.`key` = '$key' THEN a.value END)" }

        val having = """
            HAVING (? IS NULL OR ${pivot("status")} =  ?)
               AND (? IS NULL OR ${pivot("status")} <> ?)
               AND (? IS NULL OR ${pivot("hypervisor_host_name")} = ?)
               AND (? IS NULL OR ${pivot("tenant_id")} = ?)
        """.trimIndent()

        // ? IS NULL OR ... = ? 패턴이라 각 필터값을 두 번씩 바인딩한다.
        val havingParams = listOf<Any?>(
            statusEq, statusEq,
            statusNeq, statusNeq,
            host, host,
            project, project,
        )

        val from = """
            FROM cm_resource r
            JOIN cm_resource_type t ON t.id = r.resource_type_id AND t.name = '$OS_VM'
            JOIN cm_provider p      ON p.id = r.provider_id      AND p.uuid = ?
            LEFT JOIN cm_resource_attr a
                   ON a.resource_id = r.id
                  AND a.`key` IN (${INSTANCE_KEYS.joinToString(", ") { "'$it'" }})
        """.trimIndent()

        return when (mode) {
            InventoryQuery.Mode.LIST -> {
                val select = INSTANCE_KEYS.joinToString(",\n       ") { "${pivot(it)} AS $it" }
                val sql = """
                    SELECT r.uuid, r.resource_name,
                       $select
                    $from
                    GROUP BY r.id, r.uuid, r.resource_name
                    $having
                    ORDER BY r.resource_name
                    LIMIT ?
                """.trimIndent()
                Built(sql, listOf<Any?>(providerUuid) + havingParams + listOf<Any?>(limit))
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
                Built(sql, listOf<Any?>(providerUuid) + havingParams)
            }
        }
    }
}
