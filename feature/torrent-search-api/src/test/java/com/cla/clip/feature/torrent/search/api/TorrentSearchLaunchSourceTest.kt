package com.cla.clip.feature.torrent.search.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** 稳定启动来源协议测试，防止 Intent 或持久化值依赖枚举名称与顺序。 */
class TorrentSearchLaunchSourceTest {
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

    /** 未知、空白和 null 输入必须回退到 Unknown，保证旧版本或第三方 Intent 不会崩溃。 */
    @Test
    fun unknownLaunchSourceCodesFallBackSafely() {
        /** 合成的未知协议输入；不包含真实用户数据或外部来源内容。 */
        val unknownCodes = listOf(null, "", "future_source")

        assertTrue(unknownCodes.all { code ->
            TorrentSearchLaunchSource.fromCode(code) === TorrentSearchLaunchSource.Unknown
        })
    }
}
