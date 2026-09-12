package com.cla.clip.feature.torrent.search.data.model

import com.cla.clip.feature.torrent.search.provider.ProviderId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/** 书签共享协议对 HTTP 规范表示长度与幂等性的回归。 */
class BookmarkContractValidatorTest {
    /** 两个 HTTP 字段都必须按百分号编码后的最终长度拒绝 Unicode 扩张。 */
    @Test
    fun normalizeRejectsUnicodeExpansionInBothHttpFields() {
        /** expandingUrl 原始字符数很短，但 OkHttp 百分号编码后超过 8192 字符。 */
        val expandingUrl = URL_PREFIX + "雪".repeat(1_000)
        /** descriptionBookmark 只让详情页地址命中扩张边界。 */
        val descriptionBookmark = bookmark().copy(descriptionPageUrl = expandingUrl)
        /** fileBookmark 只让文件地址命中同一扩张边界。 */
        val fileBookmark = bookmark().copy(fileDownloadLink = expandingUrl)

        assertNull(BookmarkContractValidator.normalize(descriptionBookmark))
        assertNull(BookmarkContractValidator.normalize(fileBookmark))
    }

    /** 最终规范 URL 恰好 8192 字符可接受，多一字符必须拒绝。 */
    @Test
    fun normalizeChecksFinalHttpUrlAtExactLengthBoundary() {
        /** exactUrl 使用 ASCII 路径使原始值与 OkHttp 最终表示都恰好命中上限。 */
        val exactUrl = URL_PREFIX + "a".repeat(BookmarkContractValidator.MAX_LINK_CHARS - URL_PREFIX.length)
        /** overUrl 只在最终规范表示增加一个字符。 */
        val overUrl = exactUrl + "a"
        /** accepted 是命中上限的规范书签。 */
        val accepted = BookmarkContractValidator.normalize(bookmark().copy(descriptionPageUrl = exactUrl))

        assertNotNull(accepted)
        assertEquals(BookmarkContractValidator.MAX_LINK_CHARS, accepted?.descriptionPageUrl?.length)
        assertNull(BookmarkContractValidator.normalize(bookmark().copy(descriptionPageUrl = overUrl)))
    }

    /** 任意成功规范化值再次规范化必须保持完全相等。 */
    @Test
    fun normalizeIsIdempotentForEverySuccessfulFixture() {
        /** exactUrl 覆盖最大允许规范表示。 */
        val exactUrl = URL_PREFIX + "a".repeat(BookmarkContractValidator.MAX_LINK_CHARS - URL_PREFIX.length)
        /** fixtures 覆盖空可选字段、普通 Unicode 百分号编码和精确长度边界。 */
        val fixtures = listOf(
            bookmark(),
            bookmark().copy(descriptionPageUrl = URL_PREFIX + "目录/雪", fileDownloadLink = URL_PREFIX + "文件/雪"),
            bookmark().copy(descriptionPageUrl = exactUrl),
        )

        fixtures.forEach { /** fixture 是待验证稳定规范表示的合成书签。 */ fixture ->
            /** first 是第一次规范化结果，所有 fixture 都应合法。 */
            val first = BookmarkContractValidator.normalize(fixture)
            assertNotNull(first)
            assertEquals(first, BookmarkContractValidator.normalize(first!!))
        }
    }

    /** 构造字段完整且不含真实业务内容的合成书签。 */
    private fun bookmark(): BookmarkedTorrent = BookmarkedTorrent(
        infoHash = HASH_A,
        name = "Synthetic",
        providerId = ProviderId("fixture"),
        providerName = "Fixture",
        bookmarkedAtEpochMillis = 42L,
    )

    /** 合成 URL 与稳定身份集中定义，避免测试依赖外部服务。 */
    companion object {
        /** URL_PREFIX 是不会发起网络的保留域 HTTPS 前缀。 */
        private const val URL_PREFIX = "https://example.invalid/"
        /** HASH_A 是合成 SHA-1 稳定身份。 */
        private const val HASH_A = "0123456789012345678901234567890123456789"
    }
}
