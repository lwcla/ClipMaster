package com.cla.clip.feature.torrent.search.torznab

import com.cla.clip.feature.torrent.search.model.contract.Category
import com.cla.clip.feature.torrent.search.model.contract.ProviderSafetyLevel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Torznab 非敏感配置的稳定身份、URL 与协议 code 测试。 */
class TorznabConfigTest {
    /** 合法局域网 HTTP feed 应保留端口、路径和稳定分类/安全 code。 */
    @Test
    fun validateAcceptsLocalHttpFeedWithoutEmbeddingApiKey() {
        /** result 使用中性文档地址模拟 Jackett/Prowlarr 局域网入口。 */
        val result = TorznabConfigValidator.validate(
            rawId = "torznab_fixture",
            rawName = "  Fixture\nIndexer  ",
            rawFeedUrl = "http://192.0.2.10:9117/api/v2.0/indexers/fixture/results/torznab/",
            category = Category.BOOKS,
            safetyLevel = ProviderSafetyLevel.UNSAFE,
            hasApiKey = true,
        )

        assertTrue(result is TorznabConfigValidationResult.Valid)
        /** config 是页面、Provider 与密钥仓库共享的非敏感稳定契约。 */
        val config = (result as TorznabConfigValidationResult.Valid).config
        assertEquals(TorznabConfigId("torznab_fixture"), config.id)
        assertEquals("FixtureIndexer", config.name)
        assertEquals("http://192.0.2.10:9117/api/v2.0/indexers/fixture/results/torznab/", config.feedUrl)
        assertEquals("books", config.category.code)
        assertEquals("unsafe", config.safetyLevel.code)
        assertTrue(config.hasApiKey)
    }

    /** URL 中的凭据、query、fragment、非 HTTP scheme 或缺 host 均不得进入持久配置。 */
    @Test
    fun validateRejectsUnsafeOrSecretBearingUrls() {
        /** invalidUrls 覆盖会泄漏 API Key 或扩大网络边界的输入。 */
        val invalidUrls = listOf(
            "http://user@192.0.2.10:9117/api",
            "https://indexer.example/api?apikey=secret",
            "https://indexer.example/api#fragment",
            "ftp://indexer.example/api",
            "https:///api",
        )

        /** rawUrl 是当前应被稳定拒绝且不能进入错误 message 的地址。 */
        invalidUrls.forEach { rawUrl ->
            assertEquals(
                TorznabConfigValidationResult.Invalid(TorznabConfigFailureReason.INVALID_URL),
                TorznabConfigValidator.validate(
                    rawId = "torznab_fixture",
                    rawName = "Fixture",
                    rawFeedUrl = rawUrl,
                    category = Category.ALL,
                    safetyLevel = ProviderSafetyLevel.SAFE,
                    hasApiKey = false,
                ),
            )
        }
    }

    /** 非法稳定 ID 与空/超长名称必须分别给出可供 UI 映射的 reasonCode。 */
    @Test
    fun validateClassifiesIdAndNameFailures() {
        /** invalidIdResult 使用空白 ID 验证动态 Provider 身份不会依赖名称。 */
        val invalidIdResult = TorznabConfigValidator.validate(
            rawId = "Bad ID",
            rawName = "Fixture",
            rawFeedUrl = "https://indexer.example/api",
            category = Category.ALL,
            safetyLevel = ProviderSafetyLevel.SAFE,
            hasApiKey = false,
        )
        /** invalidNameResult 使用超过固定 80 字符的展示名。 */
        val invalidNameResult = TorznabConfigValidator.validate(
            rawId = "torznab_fixture",
            rawName = "x".repeat(81),
            rawFeedUrl = "https://indexer.example/api",
            category = Category.ALL,
            safetyLevel = ProviderSafetyLevel.SAFE,
            hasApiKey = false,
        )

        assertEquals(TorznabConfigValidationResult.Invalid(TorznabConfigFailureReason.INVALID_ID), invalidIdResult)
        assertEquals(TorznabConfigValidationResult.Invalid(TorznabConfigFailureReason.INVALID_NAME), invalidNameResult)
    }

    /** 动态配置 ID 必须保留 Torznab 命名空间，不能占用任一内置 Provider 身份。 */
    @Test
    fun validateRejectsBuiltinProviderIdNamespace() {
        /** result 使用内置 nyaa 身份模拟动态配置碰撞。 */
        val result = TorznabConfigValidator.validate(
            rawId = "nyaa",
            rawName = "Fixture",
            rawFeedUrl = "https://indexer.example/api",
            category = Category.ALL,
            safetyLevel = ProviderSafetyLevel.SAFE,
            hasApiKey = false,
        )

        assertEquals(TorznabConfigValidationResult.Invalid(TorznabConfigFailureReason.INVALID_ID), result)
    }
}
