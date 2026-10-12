package com.cla.clip.feature.ad.admob

import org.junit.Assert.*
import org.junit.Test

/** 保护媒体和正文展示契约，以及缺少广告选项内容时不能保留多余空白。 */
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

    /** 没有选项信息时声明列应与广告图左对齐，不保留旧的固定缩进。 */
    @Test fun missingAdChoicesContentDoesNotReserveSpace() {
        assertFalse(AdmobNativeLayoutPolicy.requiresAdChoicesSpace(null, null))
    }

    /** 空图标列表及空白选项文字不能产生独立占位。 */
    @Test fun emptyAdChoicesContentDoesNotReserveSpace() {
        assertFalse(AdmobNativeLayoutPolicy.requiresAdChoicesSpace(0, null))
        assertFalse(AdmobNativeLayoutPolicy.requiresAdChoicesSpace(0, " \n\t"))
    }

    /** 只有图标时仍须为 SDK 选项保留位置，不能移除其显示空间。 */
    @Test fun adChoicesImageReservesSpaceWithoutText() {
        assertTrue(AdmobNativeLayoutPolicy.requiresAdChoicesSpace(1, null))
    }

    /** 只有文字的选项内容同样需要保留，不能仅按图片数量判断。 */
    @Test fun adChoicesTextReservesSpaceWithoutImage() {
        assertTrue(AdmobNativeLayoutPolicy.requiresAdChoicesSpace(0, "选项"))
    }

    /** 图标与文字同时存在时保持显示空间，不重复累计固定预留。 */
    @Test fun completeAdChoicesContentReservesSpace() {
        assertTrue(AdmobNativeLayoutPolicy.requiresAdChoicesSpace(1, "选项"))
    }
}
