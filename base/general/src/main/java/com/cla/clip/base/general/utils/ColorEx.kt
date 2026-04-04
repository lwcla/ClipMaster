package com.cla.clip.base.general.utils

import android.graphics.Bitmap
import androidx.core.graphics.ColorUtils
import androidx.palette.graphics.Palette

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

/** 颜色压深 */
private fun adjustColorIfNeeded(color: Int): Int {
    val hsl = FloatArray(3)
    ColorUtils.colorToHSL(color, hsl)

    // 太灰的话，稍微提高饱和度
    if (hsl[1] < 0.28f) hsl[1] = 0.28f
    // 太亮的话，压暗一点
    if (hsl[2] > 0.70f) hsl[2] = 0.55f

    return ColorUtils.HSLToColor(hsl)
}