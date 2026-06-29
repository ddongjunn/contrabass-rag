package com.okestro.ragbot.resource.application

import com.okestro.ragbot.resource.domain.MetricPattern
import com.okestro.ragbot.resource.domain.MetricSample
import com.okestro.ragbot.resource.domain.ResourceQuery

object ResourceAnswerTemplate {

    private val metricLabel = mapOf(
        MetricPattern.INSTANCE_CPU        to "CPU 사용률",
        MetricPattern.INSTANCE_MEMORY     to "메모리 사용률",
        MetricPattern.INSTANCE_NETWORK_RX to "네트워크 수신량",
        MetricPattern.INSTANCE_NETWORK_TX to "네트워크 송신량",
        MetricPattern.INSTANCE_DISK_READ  to "디스크 읽기량",
        MetricPattern.INSTANCE_DISK_WRITE to "디스크 쓰기량",
    )

    fun build(query: ResourceQuery, samples: List<MetricSample>): String {
        if (samples.isEmpty()) return "현재 조건에 해당하는 인스턴스를 찾을 수 없습니다."

        val label = metricLabel[query.metric] ?: query.metric.name
        val direction = if (query.sort == ResourceQuery.Sort.DESC) "높은" else "낮은"
        val projectPart = query.project?.let { " (프로젝트: $it)" } ?: ""
        val instancePart = query.instanceName?.let { " ($it)" } ?: ""

        val header = "${label}이 ${direction} 인스턴스${projectPart}${instancePart}:\n"
        val lines = samples.mapIndexed { i, s ->
            val formatted = formatValue(s.value, s.unit)
            val project = s.projectName?.let { " [$it]" } ?: ""
            "  ${i + 1}. ${s.instanceName}$project — $formatted"
        }

        return header + lines.joinToString("\n")
    }

    private fun formatValue(value: Double, unit: String): String = when (unit) {
        "%" -> "${"%.1f".format(value)}%"
        "B/s" -> formatBytes(value)
        "req/s" -> "${"%.1f".format(value)} req/s"
        else -> "${"%.2f".format(value)} $unit"
    }

    private fun formatBytes(bytesPerSec: Double): String = when {
        bytesPerSec >= 1_073_741_824 -> "${"%.2f".format(bytesPerSec / 1_073_741_824)} GB/s"
        bytesPerSec >= 1_048_576     -> "${"%.2f".format(bytesPerSec / 1_048_576)} MB/s"
        bytesPerSec >= 1_024         -> "${"%.1f".format(bytesPerSec / 1_024)} KB/s"
        else                         -> "${"%.0f".format(bytesPerSec)} B/s"
    }
}
