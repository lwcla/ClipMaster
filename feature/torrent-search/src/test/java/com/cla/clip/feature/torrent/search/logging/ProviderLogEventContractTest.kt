package com.cla.clip.feature.torrent.search.logging

import com.cla.clip.feature.torrent.search.model.contract.ProviderFailureReason
import com.cla.clip.feature.torrent.search.provider.ProviderId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Provider 日志封闭类型与输出白名单测试，禁止字符串槽位承载网络敏感内容。 */
class ProviderLogEventContractTest {
    /** 事件字段必须使用 ProviderId、ProviderLogStage 和 ProviderFailureReason 等封闭类型。 */
    @Test
    fun logEventUsesOnlyApprovedFieldsAndClosedProtocolTypes() {
        /** 反射得到的业务字段，排除编译器生成字段。 */
        val fields = ProviderLogEvent::class.java.declaredFields
            // field 是等待排除编译器生成成员的反射字段。
            .filterNot { field -> field.isSynthetic || field.name.startsWith("$") }
            // field 按稳定属性名建立索引，便于校验封闭字段类型。
            .associateBy { field -> field.name }
        /** 主方案允许的固定字段集合，新增字段必须先经过隐私评审。 */
        val expectedNames = setOf(
            "providerId", "operation", "stage", "resultCount", "durationMs",
            "httpStatusCode", "retried", "reason",
        )

        assertEquals(expectedNames, fields.keys)
        /** ProviderId 是 inline class，JVM 字段会擦除为 String；编译期赋值锁定 Kotlin 强类型契约。 */
        val typedProviderId: ProviderId = ProviderLogEvent(
            providerId = ProviderId("typed_fixture"), stage = ProviderLogStage.STARTED,
            resultCount = null, durationMs = null, httpStatusCode = null, retried = null, reason = null,
        ).providerId
        assertEquals("typed_fixture", typedProviderId.value)
        assertEquals(ProviderLogOperation::class.java, fields.getValue("operation").type)
        assertEquals(ProviderLogStage::class.java, fields.getValue("stage").type)
        assertEquals(ProviderFailureReason::class.java, fields.getValue("reason").type)
        assertEquals(Boolean::class.javaObjectType, fields.getValue("retried").type)
    }

    /** 阶段 code 必须唯一、大小写敏感并对未知输入回退 unknown。 */
    @Test
    fun logStageCodesAreStableAndUnknownFallsBackSafely() {
        /** 手工锁定的阶段与 code，避免由生产枚举自我生成期望。 */
        val expectedStages = listOf(
            ProviderLogStage.STARTED to "started", ProviderLogStage.RESULTS to "results",
            ProviderLogStage.SKIPPED to "skipped", ProviderLogStage.FAILED to "failed",
            ProviderLogStage.COMPLETED to "completed", ProviderLogStage.UNKNOWN to "unknown",
        )

        // expected 是手工锁定的单个阶段与 code 配对。
        assertEquals(expectedStages.map { expected -> expected.first }, ProviderLogStage.entries)
        // stage 是封闭阶段，code 是手工锁定的稳定输出。
        expectedStages.forEach { (stage, code) ->
            assertEquals(code, stage.code)
            assertEquals(stage, ProviderLogStage.fromCode(code))
        }
        /** invalidCode 是必须安全回退 unknown 的非法阶段值。 */
        listOf(null, "", "STARTED", " started ", "future_stage").forEach { invalidCode ->
            assertEquals(ProviderLogStage.UNKNOWN, ProviderLogStage.fromCode(invalidCode))
        }
    }

    /** Android 日志格式只允许输出稳定 code 和低敏数值，不能包含对象或异常 message。 */
    @Test
    fun formattedLogMessageContainsOnlyStableCodesAndLowSensitivityValues() {
        /** 完整失败事件覆盖所有可空字段的非空路径。 */
        val event = ProviderLogEvent(
            providerId = ProviderId("fixture_provider"), stage = ProviderLogStage.FAILED,
            resultCount = 2, durationMs = 15L, httpStatusCode = 403,
            retried = false, reason = ProviderFailureReason.CLOUDFLARE_LOCKED,
        )
        /** 真实格式化函数的结果用于验证 Android Logger 最终可见文本。 */
        val message = event.toAndroidLogMessage()

        assertEquals(
            "providerId=fixture_provider operationCode=search stage=failed resultCount=2 durationMs=15 httpStatusCode=403 retried=false reasonCode=cloudflare_locked",
            message,
        )
        /** forbidden 是不得出现在最终 Android 日志文本中的敏感字段名。 */
        listOf("url", "query", "header", "cookie", "apiKey", "body", "payload", "Throwable").forEach { forbidden ->
            assertFalse(message.contains(forbidden, ignoreCase = true))
        }
        assertTrue(message.contains("reasonCode=cloudflare_locked"))
    }
}
