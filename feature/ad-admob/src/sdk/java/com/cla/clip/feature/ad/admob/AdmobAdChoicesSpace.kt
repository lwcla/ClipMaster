package com.cla.clip.feature.ad.admob

import android.content.Context
import android.graphics.Paint
import android.graphics.drawable.BitmapDrawable
import android.view.View
import com.google.android.libraries.ads.mobile.sdk.common.AdChoicesInfo
import kotlin.math.ceil

/**
 * 标准 SDK 左下角选项的透明内容预算，只测量公开素材，不绘制或接管选项。
 * 默认覆盖层没有公开的实测外框；此预算不能替代真实 SDK 展开验收。
 */
internal class AdmobAdChoicesSpace(
    /** 资源与字体配置跟随当前卡片上下文，不持有广告请求。 */
    context: Context,
    /** 本次广告的公开选项文字、图片及像素/dp 比例；缺失时不留位。 */
    info: AdChoicesInfo?,
) : View(context) {
    init {
        visibility = if (AdmobNativeLayoutPolicy.requiresAdChoicesSpace(info?.images?.size, info?.text)) VISIBLE else GONE
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        isClickable = false
        isFocusable = false
        // 图标兜底只用于缺失有效图片尺寸，边距为内容留少量余量，不设整行高度。
        val fallbackIcon = resources.getDimensionPixelSize(R.dimen.ad_admob_choices_fallback_icon)
        val inset = resources.getDimensionPixelSize(R.dimen.ad_admob_choices_padding)
        // 位图读取原始像素，避免 intrinsic 已经换过目标 density；其他 Drawable 只能按固有尺寸估算。
        // 多个选项图像可能是不同标识或帧，imageWidth/imageHeight 分别取换算后的最大宽高。
        val imageWidth = info?.images?.maxOfOrNull { image ->
            imageDimension((image.drawable as? BitmapDrawable)?.bitmap?.width ?: image.drawable?.intrinsicWidth, image.scale)
        } ?: 0
        val imageHeight = info?.images?.maxOfOrNull { image ->
            imageDimension((image.drawable as? BitmapDrawable)?.bitmap?.height ?: image.drawable?.intrinsicHeight, image.scale)
        } ?: 0
        // 选项文本使用已有身份字号测量；没有文本时不保留文字列和列间距。
        val text = info?.text.orEmpty().takeIf { it.isNotBlank() }.orEmpty()
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = resources.getDimension(R.dimen.ad_admob_identity_text) }
        val textWidth = if (text.isEmpty()) 0 else ceil(paint.measureText(text).toDouble()).toInt()
        val textHeight = if (text.isEmpty()) 0 else paint.fontMetricsInt.let { it.bottom - it.top }
        val textGap = if (text.isEmpty()) 0 else resources.getDimensionPixelSize(R.dimen.ad_admob_gap_small)
        minimumWidth = (if (imageWidth == 0) fallbackIcon else imageWidth) + textGap + textWidth + inset * 2
        minimumHeight = maxOf(if (imageHeight == 0) fallbackIcon else imageHeight, textHeight) + inset * 2
    }

    /** 公开 scale 是像素/dp；非法比例只保留已知像素，未知尺寸由图标兜底承接。 */
    private fun imageDimension(pixels: Int?, scale: Double): Int {
        if (pixels == null || pixels <= 0) return 0
        if (!scale.isFinite() || scale <= 0.0) return pixels
        return ceil(pixels / scale * resources.displayMetrics.density).toInt().coerceAtLeast(1)
    }
}
