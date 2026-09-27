package com.cla.clip.feature.ad.admob

import org.junit.Assert.*
import org.junit.Test

/** 保护视频媒体不可省略，以及短正文必须完整展示的规则。 */
class AdmobNativeLayoutPolicyTest {
    /** 视频和未知素材均必须保留媒体区域。 */
    @Test fun videoAndUnknownKeepMedia() {
        assertTrue(AdmobNativeLayoutPolicy.requiresMedia(true))
        assertTrue(AdmobNativeLayoutPolicy.requiresMedia(null))
    }

    /** 明确非视频素材应去掉大图，避免仍然占用 A1 高度。 */
    @Test fun nonVideoOmitsMainMedia() {
        assertFalse(AdmobNativeLayoutPolicy.requiresMedia(false))
    }

    /** 正文为空、空白、多行或过长时整体省略，不截取半段声明。 */
    @Test fun unsuitableBodyIsOmitted() {
        assertNull(AdmobNativeLayoutPolicy.compactBody(null))
        assertNull(AdmobNativeLayoutPolicy.compactBody("  "))
        assertNull(AdmobNativeLayoutPolicy.compactBody("第一行\n第二行"))
        assertNull(AdmobNativeLayoutPolicy.compactBody("第一行\r第二行"))
        assertNull(AdmobNativeLayoutPolicy.compactBody("文".repeat(41)))
    }

    /** 边界文本按 Unicode 码点计数，保留原文而非 UTF-16 截断。 */
    @Test fun shortBodyIsPreservedIncludingSupplementaryCharacters() {
        assertEquals("简短介绍", AdmobNativeLayoutPolicy.compactBody("简短介绍"))
        assertEquals("文".repeat(40), AdmobNativeLayoutPolicy.compactBody("文".repeat(40)))
        assertEquals("😀".repeat(40), AdmobNativeLayoutPolicy.compactBody("😀".repeat(40)))
        assertNull(AdmobNativeLayoutPolicy.compactBody("😀".repeat(41)))
    }
}
