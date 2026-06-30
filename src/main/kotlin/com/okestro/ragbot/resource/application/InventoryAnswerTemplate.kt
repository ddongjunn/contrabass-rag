package com.okestro.ragbot.resource.application

import com.okestro.ragbot.resource.domain.InventoryFilters
import com.okestro.ragbot.resource.domain.InventoryKind
import com.okestro.ragbot.resource.domain.InventoryResult

/**
 * INVENTORY 조회 결과 → 한국어 답변(순수 함수, LLM 무호출). 환각 방지(불변식 5):
 * 답변은 cb_common 조회 결과만 근거로 하고, 마지막 줄에 출처(대상·적용 필터·건수)를 표기한다.
 * METRIC의 ResourceAnswerTemplate처럼 단일 문자열을 반환해 ResourceService.Result(answer)로 감싼다.
 */
object InventoryAnswerTemplate {

    private val LABEL = mapOf(
        InventoryKind.INSTANCE to "인스턴스",
        InventoryKind.INSTANCE_SNAPSHOT to "인스턴스 스냅샷",
        InventoryKind.VOLUME to "볼륨",
        InventoryKind.VOLUME_SNAPSHOT to "볼륨 스냅샷",
    )

    fun render(result: InventoryResult): String {
        val label = LABEL.getValue(result.kind)
        val filterText = describeFilters(result.appliedFilters)
        val condSuffix = filterText?.let { " (조건: $it)" } ?: ""

        val body = when {
            result.total == 0 ->
                "조건에 맞는 ${label}을(를) 찾지 못했습니다.$condSuffix"

            result.rows.isEmpty() ->  // COUNT 모드
                "${label} 개수는 ${result.total}건입니다.$condSuffix"

            else ->  // LIST 모드
                buildString {
                    append("${label} ${result.total}건입니다$condSuffix:\n")
                    append(result.rows.joinToString("\n") { row ->
                        val name = row.name?.takeIf { it.isNotBlank() } ?: row.uuid
                        val attrs = row.attrs.entries
                            .filter { it.value != null }
                            .joinToString(", ") { "${it.key}=${it.value}" }
                        if (attrs.isBlank()) "  - $name" else "  - $name ($attrs)"
                    })
                }
        }

        val source = "(출처: 대상=$label, 필터=${filterText ?: "없음"}, 건수=${result.total}건)"
        return "$body\n$source"
    }

    /** 적용된 non-null 필터를 사람이 읽는 문자열로. 없으면 null. */
    private fun describeFilters(f: InventoryFilters): String? {
        val parts = buildList {
            f.status?.let {
                val op = if (f.statusOp == InventoryFilters.Op.NEQ) "≠" else "="
                add("status$op$it")
            }
            f.projectId?.let { add("project=$it") }
            f.hypervisorHostName?.let { add("host=$it") }
            f.instanceCreateEnable?.let { add("instanceCreateEnable=$it") }
        }
        return parts.takeIf { it.isNotEmpty() }?.joinToString(", ")
    }
}
