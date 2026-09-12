package com.cla.clip.feature.torrent.search.ui.details

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 详情图片子 Composition 与 LazyColumn 身份策略回归。 */
class TorrentDetailsImagePresentationPolicyTest {
    /** 目的地 STOP 后必须移除图片子 Composition，START 后才能重新创建。 */
    @Test
    fun imageCompositionFollowsDestinationActiveState() {
        assertTrue(TorrentDetailsImagePresentationPolicy.shouldComposeImages(destinationActive = true))
        assertFalse(TorrentDetailsImagePresentationPolicy.shouldComposeImages(destinationActive = false))
    }

    /** 相同 URL 同时出现在正文和截图时必须具有不同稳定 key。 */
    @Test
    fun sameUrlInDifferentSectionsUsesDifferentStableKeys() {
        /** imageUrl 模拟正文与截图数组复用同一规范化地址。 */
        val imageUrl = "https://allowed.example/shared.jpg"
        /** descriptionKey 是正文图区段的稳定身份。 */
        val descriptionKey = TorrentDetailsImagePresentationPolicy.lazyItemKey(
            TorrentDetailsImageSection.DESCRIPTION,
            imageUrl,
        )
        /** screenshotKey 是截图区段的稳定身份。 */
        val screenshotKey = TorrentDetailsImagePresentationPolicy.lazyItemKey(
            TorrentDetailsImageSection.SCREENSHOT,
            imageUrl,
        )

        assertNotEquals(descriptionKey, screenshotKey)
    }
}
