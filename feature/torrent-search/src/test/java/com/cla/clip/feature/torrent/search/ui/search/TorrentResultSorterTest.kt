package com.cla.clip.feature.torrent.search.ui.search

import com.cla.clip.feature.torrent.search.model.contract.SortCriteria
import com.cla.clip.feature.torrent.search.model.contract.SortOrder
import com.cla.clip.feature.torrent.search.model.contract.Torrent
import com.cla.clip.feature.torrent.search.provider.ProviderId
import org.junit.Assert.assertEquals
import org.junit.Test

/** 结果排序器的单位、空值、方向和稳定 tie-breaker 测试。 */
class TorrentResultSorterTest {
    /** 文件大小必须按解析后的字节量级排序，未知值无论不应在降序时浮到顶部。 */
    @Test
    fun sortsFileSizesNumericallyAndKeepsUnknownLast() {
        /** torrents 混合 MB、GB 和未知展示值。 */
        val torrents = listOf(
            torrent(HASH_A, size = null),
            torrent(HASH_B, size = "900 MB"),
            torrent(HASH_C, size = "1,5 GiB"),
        )

        /** sorted 应按真实量级降序并把未知值放在最后。 */
        val sorted = TorrentResultSorter.sort(torrents, SortCriteria.FILE_SIZE, SortOrder.DESCENDING)

        assertEquals(listOf(HASH_C, HASH_B, HASH_A), sorted.map { /** torrent 是当前排序后的结果。 */ torrent -> torrent.infoHash })
    }

    /** 主字段相同必须使用 infoHash 形成确定性顺序，避免重组时列表身份抖动。 */
    @Test
    fun usesInfoHashAsStableTieBreaker() {
        /** torrents 使用相同做种数但逆序输入。 */
        val torrents = listOf(torrent(HASH_C, seeders = 5u), torrent(HASH_A, seeders = 5u))

        /** sorted 应忽略到达顺序并使用稳定 hash。 */
        val sorted = TorrentResultSorter.sort(torrents, SortCriteria.SEEDERS, SortOrder.DESCENDING)

        assertEquals(listOf(HASH_A, HASH_C), sorted.map { /** torrent 是当前确定性排序结果。 */ torrent -> torrent.infoHash })
    }

    /** 创建字段最小且不含真实内容的合成结果。 */
    private fun torrent(
        /** 合成稳定身份。 */
        infoHash: String,
        /** 可选文件大小展示文本。 */
        size: String? = null,
        /** 可选做种数。 */
        seeders: UInt? = null,
    ): Torrent {
        return Torrent(
            infoHash = infoHash,
            name = "Synthetic",
            size = size,
            seeders = seeders,
            providerId = ProviderId("fixture"),
            providerName = "Fixture",
        )
    }

    /** 合成 hash 集中定义，避免测试混入用户数据。 */
    companion object {
        /** 第一条合成 infoHash。 */
        private const val HASH_A = "0123456789012345678901234567890123456789"
        /** 第二条合成 infoHash。 */
        private const val HASH_B = "1123456789012345678901234567890123456789"
        /** 第三条合成 infoHash。 */
        private const val HASH_C = "2123456789012345678901234567890123456789"
    }
}
