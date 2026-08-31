package com.cla.clip.feature.torrent.search.model.contract

import com.cla.clip.feature.torrent.search.provider.ProviderId
import com.cla.clip.feature.torrent.search.provider.ProviderType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 固定上游提交对应的稳定领域协议测试，防止移植时缩减分类、排序或详情字段。 */
class TorrentSearchStableContractTest {
    /** 分类必须精确保留上游十项语义、NSFW 标记和大小写敏感的安全回退。 */
    @Test
    fun categoryContractMatchesPinnedUpstreamAndFallsBackSafely() {
        /** 手工审查后的分类、稳定 code 与 NSFW 事实，不从生产枚举反推期望。 */
        val expectedCategories = listOf(
            Triple(Category.ALL, "all", false), Triple(Category.ANIME, "anime", false),
            Triple(Category.APPS, "apps", false), Triple(Category.BOOKS, "books", false),
            Triple(Category.GAMES, "games", false), Triple(Category.MOVIES, "movies", false),
            Triple(Category.MUSIC, "music", false), Triple(Category.PORN, "porn", true),
            Triple(Category.SERIES, "series", false), Triple(Category.OTHER, "other", false),
        )

        // expected 是手工锁定的单个分类三元组。
        assertEquals(expectedCategories.map { expected -> expected.first }, Category.entries)
        // category、code 与 isNsfw 分别表示分类、稳定标识和安全属性。
        expectedCategories.forEach { (category, code, isNsfw) ->
            assertEquals(code, category.code)
            assertEquals(isNsfw, category.isNsfw)
            assertEquals(category, Category.fromCode(code))
        }
        /** invalidCode 是必须回退 all 的非法分类值。 */
        listOf(null, "", "ALL", " all ", "future_category").forEach { invalidCode ->
            assertEquals(Category.ALL, Category.fromCode(invalidCode))
        }
    }

    /** 排序标准必须使用 name/seeders/peers/file_size/date，默认值固定为 seeders。 */
    @Test
    fun sortCriteriaContractMatchesPinnedUpstreamAndFallsBackToSeeders() {
        /** 手工锁定的标准顺序与 code，避免用枚举 name 形成自洽测试。 */
        val expectedCriteria = listOf(
            SortCriteria.NAME to "name", SortCriteria.SEEDERS to "seeders",
            SortCriteria.PEERS to "peers", SortCriteria.FILE_SIZE to "file_size",
            SortCriteria.DATE to "date",
        )

        // expected 是手工锁定的单个排序标准配对。
        assertEquals(expectedCriteria.map { expected -> expected.first }, SortCriteria.entries)
        // criteria 与 code 分别表示排序标准和稳定标识。
        expectedCriteria.forEach { (criteria, code) ->
            assertEquals(code, criteria.code)
            assertEquals(criteria, SortCriteria.fromCode(code))
        }
        /** invalidCode 是必须回退 seeders 的非法排序标准值。 */
        listOf(null, "", "SEEDERS", " seeders ", "future_sort").forEach { invalidCode ->
            assertEquals(SortCriteria.SEEDERS, SortCriteria.fromCode(invalidCode))
        }
    }

    /** 排序方向必须只包含升序和降序，未知持久值默认降序。 */
    @Test
    fun sortOrderContractMatchesPinnedUpstreamAndFallsBackToDescending() {
        /** 手工锁定的方向顺序与 code。 */
        val expectedOrders = listOf(SortOrder.ASCENDING to "ascending", SortOrder.DESCENDING to "descending")

        // expected 是手工锁定的单个排序方向配对。
        assertEquals(expectedOrders.map { expected -> expected.first }, SortOrder.entries)
        // order 与 code 分别表示排序方向和稳定标识。
        expectedOrders.forEach { (order, code) ->
            assertEquals(code, order.code)
            assertEquals(order, SortOrder.fromCode(code))
        }
        /** invalidCode 是必须回退 descending 的非法排序方向值。 */
        listOf(null, "", "DESCENDING", " descending ", "future_order").forEach { invalidCode ->
            assertEquals(SortOrder.DESCENDING, SortOrder.fromCode(invalidCode))
        }
    }

    /** 失败和安全 code 必须唯一可逆，未知输入采用各自保守默认值。 */
    @Test
    fun failureAndSafetyCodesRoundTripWithConservativeFallbacks() {
        /** 手工锁定的失败原因与 code，防止新增或改名被自洽测试放过。 */
        val reasons = listOf(
            ProviderFailureReason.NETWORK to "network", ProviderFailureReason.TIMEOUT to "timeout",
            ProviderFailureReason.HTTP_DENIED to "http_denied", ProviderFailureReason.PARSE_FAILURE to "parse_failure",
            ProviderFailureReason.CLOUDFLARE_LOCKED to "cloudflare_locked",
            ProviderFailureReason.SAFE_MODE_DISABLED to "safe_mode_disabled",
            ProviderFailureReason.CANCELLED to "cancelled",
            ProviderFailureReason.RESPONSE_TOO_LARGE to "response_too_large",
            ProviderFailureReason.UNKNOWN to "unknown",
        )
        /** 手工锁定的安全等级与 code，未知值仍必须保守回退 unsafe。 */
        val levels = listOf(
            ProviderSafetyLevel.SAFE to "safe",
            ProviderSafetyLevel.UNSAFE to "unsafe",
            ProviderSafetyLevel.NSFW to "nsfw",
        )

        // reasonPair 是手工锁定的单个失败原因与 code 配对。
        assertEquals(reasons.map { reasonPair -> reasonPair.first }, ProviderFailureReason.entries)
        // reasonPair 是等待 code 唯一性校验的单个失败原因配对。
        assertEquals(reasons.size, reasons.map { reasonPair -> reasonPair.second }.toSet().size)
        /** reason 与 code 分别表示失败枚举和稳定 reasonCode。 */
        reasons.forEach { (reason, code) ->
            assertEquals(code, reason.code)
            assertEquals(reason, ProviderFailureReason.fromCode(code))
        }
        /** invalidCode 是必须回退 unknown 的非法失败原因值。 */
        listOf(null, "", "NETWORK", " network ", "future_failure").forEach { invalidCode ->
            assertEquals(ProviderFailureReason.UNKNOWN, ProviderFailureReason.fromCode(invalidCode))
        }
        // levelPair 是手工锁定的单个安全等级与 code 配对。
        assertEquals(levels.map { levelPair -> levelPair.first }, ProviderSafetyLevel.entries)
        // levelPair 是等待 code 唯一性校验的单个安全等级配对。
        assertEquals(levels.size, levels.map { levelPair -> levelPair.second }.toSet().size)
        /** level 与 code 分别表示安全等级和稳定标识。 */
        levels.forEach { (level, code) ->
            assertEquals(code, level.code)
            assertEquals(level, ProviderSafetyLevel.fromCode(code))
        }
        /** invalidCode 是必须保守回退 unsafe 的非法安全等级值。 */
        listOf(null, "", "SAFE", " safe ", "future_safety").forEach { invalidCode ->
            assertEquals(ProviderSafetyLevel.UNSAFE, ProviderSafetyLevel.fromCode(invalidCode))
        }
    }

    /** Provider 类型必须使用稳定 code，未知类型只能回退内置来源而不能注入外部配置。 */
    @Test
    fun providerTypeUsesStableCodesAndSafeBuiltinFallback() {
        /** 固定提交只区分内置来源与用户 Torznab 来源。 */
        val expectedTypes = listOf(ProviderType.BUILTIN to "builtin", ProviderType.TORZNAB to "torznab")

        // expected 是手工锁定的单个 Provider 类型配对。
        assertEquals(expectedTypes.map { expected -> expected.first }, ProviderType.entries)
        // type 与 code 分别表示 Provider 来源类型和稳定标识。
        expectedTypes.forEach { (type, code) ->
            assertEquals(code, type.code)
            assertEquals(type, ProviderType.fromCode(code))
        }
        /** invalidCode 是必须回退 builtin 的非法 Provider 类型值。 */
        listOf(null, "", "TORZNAB", " torznab ", "future_type").forEach { invalidCode ->
            assertEquals(ProviderType.BUILTIN, ProviderType.fromCode(invalidCode))
        }
    }

    /** Torrent 与详情模型必须承载固定上游能力，并明确以 epochMillis 表示时间。 */
    @Test
    fun torrentModelsKeepCompletePinnedUpstreamFieldsWithEpochMillis() {
        /** 合成 Provider ID 只用于验证强类型关联，不对应真实站点。 */
        val providerId = ProviderId("fixture_provider")
        /** 完整搜索结果覆盖固定上游字段，未来删字段会直接造成测试编译失败。 */
        val torrent = Torrent(
            infoHash = "0123456789abcdef0123456789abcdef01234567", name = "fixture", size = "1.0 GiB",
            seeders = 12u, peers = 3u, providerId = providerId, providerName = "Fixture Provider",
            uploadEpochMillis = 1_700_000_000_000L, category = Category.MOVIES,
            descriptionPageUrl = "https://example.invalid/details", magnetUri = "magnet:?xt=urn:btih:fixture",
            fileDownloadLink = "https://example.invalid/file.torrent",
        )
        /** 完整详情覆盖上游详情字段以及后续文件和 tracker 能力。 */
        val details = TorrentDetails(
            infoHash = torrent.infoHash, name = torrent.name, size = torrent.size, seeders = torrent.seeders,
            peers = torrent.peers, providerId = providerId, providerName = torrent.providerName,
            uploadEpochMillis = torrent.uploadEpochMillis, category = torrent.category, uploader = "fixture-uploader",
            lastCheckedEpochMillis = 1_700_000_010_000L, magnetUri = torrent.magnetUri.orEmpty(),
            fileDownloadLink = torrent.fileDownloadLink, description = "fixture-description",
            posterUrl = "https://example.invalid/poster.jpg",
            screenshotUrls = listOf("https://example.invalid/screenshot.jpg"),
            files = listOf(TorrentFile("fixture.bin", 4L)),
            trackers = listOf(TorrentTracker("https://tracker.invalid/announce")),
        )

        assertEquals(providerId, torrent.providerId)
        assertEquals(1_700_000_000_000L, torrent.uploadEpochMillis)
        assertFalse(torrent.isNsfw)
        assertEquals("fixture-uploader", details.uploader)
        assertEquals(1_700_000_010_000L, details.lastCheckedEpochMillis)
        assertEquals(1, details.files.size)
        assertEquals(1, details.trackers.size)
    }

    /** Provider ID 必须拒绝空值、超长值、大小写、路径和空白，保护持久化及日志关联。 */
    @Test
    fun providerIdValidatesStableCharacterSetAndLength() {
        /** 边界内的最长合法 ID，证明长度上限是包含式。 */
        val maxLengthId = "a".repeat(64)
        assertEquals(maxLengthId, ProviderId(maxLengthId).value)

        /** 每个非法样本分别覆盖空值、超长、大小写、路径、空白和连续分隔符。 */
        val invalidIds = listOf("", "a".repeat(65), "Upper", "has/slash", "has space", "two__parts", "_prefix")
        /** invalidId 是等待 ProviderId 构造校验拒绝的单个不稳定值。 */
        invalidIds.forEach { invalidId ->
            /** 是否抛出参数错误是稳定验证结果，不读取异常 message。 */
            val rejected = runCatching { ProviderId(invalidId) }.exceptionOrNull() is IllegalArgumentException
            assertTrue("Provider ID 应被拒绝：$invalidId", rejected)
        }
        assertFalse(runCatching { ProviderId("provider_01") }.isFailure)
    }
}
