package com.cla.clip.feature.torrent.search.system

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 分享、复制和磁力打开动作的纯协议测试。 */
class TorrentExternalActionFactoryTest {
    /** 分享必须保留正文并固定使用纯文本动作。 */
    @Test
    fun shareTextBuildsReviewedAction() {
        /** action 是待交给 Android 网关翻译的纯动作。 */
        val action = TorrentExternalActionFactory.shareText("magnet fixture")

        assertEquals(TorrentExternalAction.ShareText("magnet fixture"), action)
    }

    /** 合法 btih magnet 才能生成系统打开动作。 */
    @Test
    fun openMagnetRequiresValidBtih() {
        /** validMagnet 使用固定 40 位 btih。 */
        val validMagnet = "magnet:?xt=urn:btih:0123456789012345678901234567890123456789"

        assertEquals(TorrentExternalAction.OpenMagnet(validMagnet), TorrentExternalActionFactory.openMagnet(validMagnet))
        assertNull(TorrentExternalActionFactory.openMagnet("magnet:?dn=missing-hash"))
        assertNull(TorrentExternalActionFactory.openMagnet("https://example.invalid/item"))
    }

    /** 复制动作拒绝空白，避免向系统剪贴板写入无意义内容。 */
    @Test
    fun copyTextRejectsBlankValue() {
        assertEquals(TorrentExternalAction.CopyText("value"), TorrentExternalActionFactory.copyText(" value "))
        assertNull(TorrentExternalActionFactory.copyText("   "))
    }
}
