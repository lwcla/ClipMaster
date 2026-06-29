package com.cla.clip.base.general.utils

import org.junit.Assert.assertEquals
import org.junit.Test

/** 图标 Bitmap 工具测试，保护自适应图标等无固有尺寸 Drawable 的尺寸兜底。 */
class BitmapExTest {

    @Test
    /** Drawable 固有边长为负数时，应回退到调用方允许的最大图标尺寸。 */
    fun boundedIconDimensionFallsBackWhenIntrinsicIsNegative() {
        /** 页面或 Shizuku 图标协议允许的最大边长。 */
        val maxSize = 64

        /** 计算后的安全边长；负数固有尺寸不能传给 createBitmap。 */
        val boundedDimension = boundedIconDimension(intrinsicDimension = -1, maxSize = maxSize)

        assertEquals(maxSize, boundedDimension)
    }

    @Test
    /** Drawable 固有边长为 0 时，应回退到调用方允许的最大图标尺寸。 */
    fun boundedIconDimensionFallsBackWhenIntrinsicIsZero() {
        /** 页面或 Shizuku 图标协议允许的最大边长。 */
        val maxSize = 64

        /** 计算后的安全边长；0 尺寸不能传给 createBitmap。 */
        val boundedDimension = boundedIconDimension(intrinsicDimension = 0, maxSize = maxSize)

        assertEquals(maxSize, boundedDimension)
    }

    @Test
    /** Drawable 固有边长超过上限时，应裁剪到调用方允许的最大图标尺寸。 */
    fun boundedIconDimensionCapsOversizedIntrinsic() {
        /** 页面或 Shizuku 图标协议允许的最大边长。 */
        val maxSize = 64

        /** 计算后的安全边长；超大图标只绘制到协议上限，避免 Binder 返回体积过大。 */
        val boundedDimension = boundedIconDimension(intrinsicDimension = 128, maxSize = maxSize)

        assertEquals(maxSize, boundedDimension)
    }

    @Test
    /** Drawable 固有边长已经合法且未超过上限时，应保持原尺寸。 */
    fun boundedIconDimensionKeepsSmallIntrinsic() {
        /** 页面或 Shizuku 图标协议允许的最大边长。 */
        val maxSize = 64

        /** 计算后的安全边长；小图标保持原尺寸以减少不必要放大。 */
        val boundedDimension = boundedIconDimension(intrinsicDimension = 32, maxSize = maxSize)

        assertEquals(32, boundedDimension)
    }

    @Test
    /** 调用方误传非正上限时，仍应返回至少 1px 的合法 Bitmap 边长。 */
    fun boundedIconDimensionKeepsAtLeastOnePixelWhenMaxSizeInvalid() {
        /** 非法最大边长；测试保护工具函数的防御性兜底。 */
        val invalidMaxSize = 0

        /** 计算后的安全边长；createBitmap 至少需要 1px。 */
        val boundedDimension = boundedIconDimension(intrinsicDimension = -1, maxSize = invalidMaxSize)

        assertEquals(1, boundedDimension)
    }
}
