package com.cla.clip.feature.torrent.search.provider.builtin

import com.cla.clip.feature.torrent.search.provider.builtin.testing.FakeTorrentSearchNetworkClient
import org.junit.Assert.assertEquals
import org.junit.Test

/** 固定内置 Provider 注册表的运行时快照测试，防止遗漏、重复或顺序漂移。 */
class BuiltinProviderRegistryTest {
    /** 注册表必须按本地固定 manifest 顺序一次性暴露全部 43 个 Provider。 */
    @Test
    fun createReturnsAllPinnedProvidersInStableOrder() {
        /** expectedProviderIds 是人工核对 upstream-manifest.json 后固定的稳定 ID 顺序。 */
        val expectedProviderIds = listOf(
            "anilibria",
            "animetosho",
            "anirena",
            "audiobookbay",
            "bangumimoe",
            "bitsearch",
            "blueroms",
            "bt4g",
            "btdigg",
            "btsow",
            "dmhy",
            "extdotto",
            "eztvx",
            "filemood",
            "internetarchive",
            "knaben",
            "limetorrents",
            "linuxtracker",
            "megapeer",
            "mikanproject",
            "mypornclub",
            "nekobt",
            "nyaasi",
            "oxtorrent",
            "rutorinfo",
            "subsplease",
            "sukebeinyaa",
            "thepiratebay",
            "therarbag",
            "1337x",
            "tokyotoshokan",
            "torrent9",
            "torrentdatabase",
            "torrentdownloadinfo",
            "torrentdownloads",
            "torrentkitty",
            "torrentscsv",
            "torrentz",
            "uindex",
            "xxxclub",
            "xxxtracker",
            "ytsmx",
            "0magnet",
        )
        /** actualProviderIds 从真实注册表读取，避免只检查测试替身或源码文本。 */
        val actualProviderIds = BuiltinProviderRegistry
            .create(FakeTorrentSearchNetworkClient())
            .map {
                /** provider 是注册表当前按固定顺序产出的真实内置来源。 */
                provider -> provider.descriptor.id.value
            }

        assertEquals(expectedProviderIds, actualProviderIds)
    }
}
