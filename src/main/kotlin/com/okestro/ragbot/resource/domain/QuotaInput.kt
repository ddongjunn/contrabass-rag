package com.okestro.ragbot.resource.domain

/**
 * quota_gauge 위젯 입력 한 항목. 서비스가 openstack_*_limits_* 의 max/used를 tenant로 짝지어 만든다.
 *
 * QuotaGaugeWidget에는 tenant 필드가 없다(한 테넌트의 vCPU/메모리/디스크를 보여주는 위젯) —
 * 어느 테넌트인지는 서비스가 정하고, 빌더는 짝지어진 값만 받아 변환한다.
 *
 * @param max 음수면 무제한(실관측 -1). quotaItem()이 quota/ratio/severity=null 처리한다.
 */
data class QuotaInput(
    val resource: String,
    val used: Double,
    val max: Double,
)
