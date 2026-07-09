package com.okestro.ragbot.resource.application

/**
 * 지표 값 단일 포맷터(설계 §5.2). Slack 평문(ResourceAnswerTemplate)과 웹 위젯(WidgetBuilder)이
 * 이 하나를 공유해 숫자 드리프트("91.2%" vs "91%")를 막는다. 순수 함수.
 */
object MetricValueFormatter {

    fun format(value: Double, unit: String): String = when (unit) {
        "%" -> "${"%.1f".format(value)}%"
        "B/s" -> formatBytesPerSec(value)
        "req/s" -> "${"%.1f".format(value)} req/s"
        "MB" -> formatMegabytes(value)
        else -> "${"%.2f".format(value)} $unit"
    }

    private fun formatBytesPerSec(bytesPerSec: Double): String = when {
        bytesPerSec >= 1_073_741_824 -> "${"%.2f".format(bytesPerSec / 1_073_741_824)} GB/s"
        bytesPerSec >= 1_048_576     -> "${"%.2f".format(bytesPerSec / 1_048_576)} MB/s"
        bytesPerSec >= 1_024         -> "${"%.1f".format(bytesPerSec / 1_024)} KB/s"
        else                         -> "${"%.0f".format(bytesPerSec)} B/s"
    }

    private fun formatMegabytes(mb: Double): String = when {
        mb >= 1_024 -> "${"%.0f".format(mb / 1_024)} GB"  // 51200 MB → 50 GB
        else        -> "${"%.0f".format(mb)} MB"
    }
}
