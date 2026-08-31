package com.cla.clip.feature.torrent.search.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** 稳定启动来源协议测试，防止 Intent 或持久化值依赖枚举名称与顺序。 */
class TorrentSearchLaunchSourceTest {
    /** 每个必需来源必须保持审核确认的精确 code，防止交换、删减或重命名后测试仍然自洽通过。 */
    @Test
    fun launchSourceCodesMatchStableProtocol() {
        /** 手工固定的来源到协议值映射；期望值不得从生产 code 反向生成。 */
        val expectedCodes = mapOf(
            TorrentSearchLaunchSource.Mine to "mine",
            TorrentSearchLaunchSource.ClipDetail to "clip_detail",
            TorrentSearchLaunchSource.ActionSearch to "action_search",
            TorrentSearchLaunchSource.Send to "send",
            TorrentSearchLaunchSource.ProcessText to "process_text",
            TorrentSearchLaunchSource.Unknown to "unknown",
        )
        /** 生产枚举当前暴露的完整映射；包含多余或缺失来源时 Map 比较也会失败。 */
        val actualCodes = TorrentSearchLaunchSource.entries.associateWith { source -> source.code }

        assertEquals(expectedCodes, actualCodes)
    }

    /** 每个来源必须拥有唯一 code，避免新增来源时产生无法区分的外部协议值。 */
    @Test
    fun launchSourceCodesAreUnique() {
        /** 全部协议 code；测试直接检查实际公开契约，不复制生产映射逻辑。 */
        val codes = TorrentSearchLaunchSource.entries.map { source -> source.code }

        assertEquals(codes.size, codes.toSet().size)
    }

    /** 每个已知 code 必须往返到同一来源，避免解析器遗漏新增枚举项。 */
    @Test
    fun knownLaunchSourceCodesRoundTrip() {
        TorrentSearchLaunchSource.entries.forEach { source ->
            assertSame(source, TorrentSearchLaunchSource.fromCode(source.code))
        }
    }

    /** 未知、空白、大小写错误和 null 输入必须回退到 Unknown，保证外部 Intent 不会崩溃或被误判。 */
    @Test
    fun unknownLaunchSourceCodesFallBackSafely() {
        /** 合成的未知协议输入；不包含真实用户数据或外部来源内容。 */
        val unknownCodes = listOf(null, "", " ", "MINE", "future_source")

        assertTrue(unknownCodes.all { code ->
            TorrentSearchLaunchSource.fromCode(code) === TorrentSearchLaunchSource.Unknown
        })
    }
}
