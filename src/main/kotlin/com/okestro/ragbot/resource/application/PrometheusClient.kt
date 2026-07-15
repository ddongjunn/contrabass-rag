package com.okestro.ragbot.resource.application

import com.okestro.ragbot.resource.domain.LabeledSample
import com.okestro.ragbot.resource.domain.MetricSample

interface PrometheusClient {
    fun query(promql: String, unit: String): List<MetricSample>

    /**
     * 라벨을 보존해 반환한다(1b 위젯용). query()는 instance_name/domain이 없는 시계열을
     * 버리므로 status·tenant 라벨 집계나 라벨 없는 스칼라(count)를 받을 수 없다.
     */
    fun queryLabeled(promql: String): List<LabeledSample>
}
