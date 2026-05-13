package com.cla.clip.base.general.utils

import android.graphics.Bitmap
import androidx.core.graphics.ColorUtils
import androidx.palette.graphics.Palette

/**
 * 从 Bitmap 中提取适合 UI 展示的主色。
 *
 * 优先选择鲜明且不过亮的调色板颜色；如果只能拿到偏灰或偏亮颜色，会做轻微压暗/增饱和处理，避免列表头像背景过浅看不清。
 */
fun Bitmap.extractUsableColor() = runCatching {
    val palette = Palette.from(this).generate()

    val candidates = listOfNotNull(
        palette.vibrantSwatch?.rgb,
        palette.darkVibrantSwatch?.rgb,
        palette.mutedSwatch?.rgb,
        palette.darkMutedSwatch?.rgb,
        palette.dominantSwatch?.rgb,
        palette.lightVibrantSwatch?.rgb,
        palette.lightMutedSwatch?.rgb,
    )

    candidates.firstOrNull { color ->
        !isTooLight(color) && !isTooLowSaturation(color)
    } ?: candidates.firstOrNull()?.let(::adjustColorIfNeeded)
}.getOrNull()

/** 是否太亮 比如接近白色、浅黄、浅粉这种 */
private fun isTooLight(color: Int): Boolean {
    return ColorUtils.calculateLuminance(color) > 0.80
}

/** 是否饱和度太低 比如灰白、米白、很淡的灰蓝。 */
private fun isTooLowSaturation(color: Int): Boolean {
    val hsl = FloatArray(3)
    ColorUtils.colorToHSL(color, hsl)
    return hsl[1] < 0.20f
}

/** 对过浅或过灰的颜色做轻微修正，保证用于 UI 背景时仍有足够识别度。 */
private fun adjustColorIfNeeded(color: Int): Int {
    val hsl = FloatArray(3)
    ColorUtils.colorToHSL(color, hsl)

    // 太灰的话，稍微提高饱和度
    if (hsl[1] < 0.28f) hsl[1] = 0.28f
    // 太亮的话，压暗一点
    if (hsl[2] > 0.70f) hsl[2] = 0.55f

    return ColorUtils.HSLToColor(hsl)
}

/**
 * 将 ARGB Int 颜色转为十六进制字符串。
 *
 * @param includeAlpha 是否包含透明度；false 时输出 #RRGGBB，true 时输出 #AARRGGBB。
 */
fun Int.toColorString(includeAlpha: Boolean = false) = if (includeAlpha) {
    String.format("#%08X", this)   // 例：#FF3A7BD5
} else {
    String.format("#%06X", 0xFFFFFF and this)  // 例：#3A7BD5
}
