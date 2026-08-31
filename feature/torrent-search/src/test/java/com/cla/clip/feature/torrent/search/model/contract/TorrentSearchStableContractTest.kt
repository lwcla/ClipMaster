package com.cla.clip.feature.torrent.search.model.contract

import org.junit.Assert.assertEquals
import org.junit.Test

/** 稳定领域枚举协议测试，防止持久化 code 因重排、重命名或未知值而失去兼容性。 */
class TorrentSearchStableContractTest {
    /** 分类 code 必须唯一、可逆，并对未来未知值回退到全部分类。 */
    @Test
    fun categoryCodesAreUniqueRoundTripAndUnknownFallsBackToAll() {
        /** 当前全部分类，用于同时验证 code 唯一性和逐项反序列化。 */
        val categories = Category.entries

        assertEquals(categories.size, categories.map { category -> category.code }.toSet().size)
        categories.forEach { category ->
            assertEquals(category, Category.fromCode(category.code))
        }
        assertEquals(Category.ALL, Category.fromCode("future_category"))
    }

    /** 排序 code 必须唯一、可逆，并对未来未知值回退到相关度排序。 */
    @Test
    fun sortCodesAreUniqueRoundTripAndUnknownFallsBackToRelevance() {
        /** 当前全部排序方式，用于同时验证 code 唯一性和逐项反序列化。 */
        val sorts = Sort.entries

        assertEquals(sorts.size, sorts.map { sort -> sort.code }.toSet().size)
        sorts.forEach { sort ->
            assertEquals(sort, Sort.fromCode(sort.code))
        }
        assertEquals(Sort.RELEVANCE, Sort.fromCode("future_sort"))
    }

    /** 失败原因 code 必须唯一、可逆，并对未来未知值回退为 unknown。 */
    @Test
    fun failureReasonCodesAreUniqueRoundTripAndUnknownFallsBackToUnknown() {
        /** 当前全部失败原因，用于同时验证日志和跨层失败协议不会漂移。 */
        val reasons = ProviderFailureReason.entries

        assertEquals(reasons.size, reasons.map { reason -> reason.code }.toSet().size)
        reasons.forEach { reason ->
            assertEquals(reason, ProviderFailureReason.fromCode(reason.code))
        }
        assertEquals(ProviderFailureReason.UNKNOWN, ProviderFailureReason.fromCode("future_failure"))
    }

    /** 安全等级 code 必须唯一、可逆，并对未来未知值保守回退为 unsafe。 */
    @Test
    fun safetyCodesAreUniqueRoundTripAndUnknownFallsBackToUnsafe() {
        /** 当前全部安全等级，用于确保默认过滤不会被未知持久值绕过。 */
        val levels = ProviderSafetyLevel.entries

        assertEquals(levels.size, levels.map { level -> level.code }.toSet().size)
        levels.forEach { level ->
            assertEquals(level, ProviderSafetyLevel.fromCode(level.code))
        }
        assertEquals(ProviderSafetyLevel.UNSAFE, ProviderSafetyLevel.fromCode("future_safety"))
    }
}
