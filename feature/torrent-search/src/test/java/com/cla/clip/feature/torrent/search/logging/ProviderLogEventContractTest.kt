package com.cla.clip.feature.torrent.search.logging

import org.junit.Assert.assertEquals
import org.junit.Test

/** Provider 日志事件白名单测试，禁止网络敏感数据被纳入事件模型。 */
class ProviderLogEventContractTest {
    /** 结构化事件仅能持有主方案允许的低敏字段。 */
    @Test
    fun logEventExposesOnlyApprovedLowSensitivityFields() {
        /** 反射得到的业务字段名；排除 synthetic 与 Compose `$stable` 等编译器字段，新增业务字段仍会触发失败。 */
        val fieldNames = ProviderLogEvent::class.java.declaredFields
            .filterNot { field -> field.isSynthetic || field.name.startsWith("$") }
            .map { field -> field.name }
            .toSet()
        /** 主方案允许的固定字段集合；额外字段必须经隐私评审和测试更新后才能加入。 */
        val expectedFieldNames = setOf(
            "providerId",
            "stage",
            "resultCount",
            "durationMs",
            "httpStatusCode",
            "retryable",
            "reasonCode",
        )

        assertEquals(expectedFieldNames, fieldNames)
    }
}
