package com.cla.clip.feature.torrent.search.data.mapper

import com.cla.clip.feature.torrent.search.model.contract.Category
import com.cla.clip.feature.torrent.search.model.contract.Torrent
import com.cla.clip.feature.torrent.search.provider.ProviderId
import org.junit.Assert.assertEquals
import org.junit.Test

/** 书签与页面 Torrent 之间纯映射的完整字段回归。 */
class BookmarkedTorrentMapperTest {
    /** 搜索结果转书签再回页面模型时必须保留全部用户可见字段。 */
    @Test
    fun torrentRoundTripPreservesAllSharedFields() {
        /** torrent 使用完整中性字段覆盖映射边界。 */
        val torrent = Torrent(
            infoHash = HASH,
            name = "Synthetic",
            size = "42 MiB",
            seeders = 7u,
            peers = 3u,
            providerId = ProviderId("fixture"),
            providerName = "Fixture",
            uploadEpochMillis = 42L,
            category = Category.BOOKS,
            descriptionPageUrl = "https://example.invalid/details",
            magnetUri = "magnet:?xt=urn:btih:$HASH",
            fileDownloadLink = "https://example.invalid/file.torrent",
        )

        /** restored 是书签快照重新映射出的页面模型。 */
        val restored = torrent.toBookmarkedTorrent().toTorrent()

        assertEquals(torrent, restored)
    }

    /** 中性合成 SHA-1 infoHash。 */
    private companion object {
        /** 唯一测试稳定身份。 */
        const val HASH = "0123456789012345678901234567890123456789"
    }
}
