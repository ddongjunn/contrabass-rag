package com.okestro.ragbot.resource.domain

/**
 * INVENTORY(cb_common DB) 조회 대상. 각 값은 cm_resource_type.name에 대응한다.
 * INSTANCE=OS_VM, INSTANCE_SNAPSHOT=OS_VM_SNAPSHOT, VOLUME=OS_VOLUME, VOLUME_SNAPSHOT=OS_VOLUME_SNAPSHOT.
 */
enum class InventoryKind { INSTANCE, INSTANCE_SNAPSHOT, VOLUME, VOLUME_SNAPSHOT }
