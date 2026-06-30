package com.okestro.ragbot.resource.domain

/**
 * INVENTORY 조회 결과 한 행. attrs는 화이트리스트 key→value(피벗된 EAV 속성).
 * uuid는 cm_resource.uuid, name은 resource_name.
 */
data class InventoryRow(
    val uuid: String,
    val name: String?,
    val attrs: Map<String, String?>,
)
