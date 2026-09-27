package com.cla.clip.feature.ad.admob

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.google.android.libraries.ads.mobile.sdk.nativead.NativeAd
import com.google.android.libraries.ads.mobile.sdk.nativead.NativeAdView

/** 两种紧凑布局共用的素材控件；负责展示及资产注册，不持有请求。 */
internal class AdmobNativeAssets(
    /** 页面上下文随 NativeAdView 一起释放。 */
    private val context: Context,
    /** 已加载素材，禁止将内容写入日志。 */
    private val ad: NativeAd,
    /** 全部素材注册在 SDK 同一个容器内。 */
    private val root: NativeAdView,
) {
    /** 向上取整，避免非整数密度下小于视频 120dp 下限。 */
    fun dp(value: Int): Int = kotlin.math.ceil(value * context.resources.displayMetrics.density).toInt()

    /** 创建内容行/列，背景不安装点击监听。 */
    fun group(orientation: Int): LinearLayout = LinearLayout(context).apply {
        this.orientation = orientation
        gravity = Gravity.CENTER_VERTICAL
    }

    /** 正数宽高使用 dp，负值保留 Android 布局常量语义。 */
    fun params(width: Int, height: Int, weight: Float = 0f): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(if (width < 0) width else dp(width), if (height < 0) height else dp(height), weight)

    /** 标题完整换行，不为固定高度裁掉广告声明。 */
    fun headline(): TextView = label(ad.headline, 15f).apply {
        setTypeface(typeface, Typeface.BOLD)
        root.headlineView = this
    }

    /** 图标缺失时不占位；提供时保持原始比例并注册。 */
    fun icon(size: Int): ImageView = ImageView(context).apply {
        setImageDrawable(ad.icon?.drawable)
        scaleType = ImageView.ScaleType.FIT_CENTER
        visibility = if (ad.icon == null) View.GONE else View.VISIBLE
        layoutParams = params(size, size).apply { marginEnd = dp(8) }
        root.iconView = this
    }

    /** 长正文整体省略，短正文完整显示。 */
    fun body(): TextView = label(AdmobNativeLayoutPolicy.compactBody(ad.body), 12f).apply {
        visibility = if (text.isNullOrBlank()) View.GONE else View.VISIBLE
        root.bodyView = this
    }

    /** 广告标记与可选广告主共用一列；标记不注册为点击素材。 */
    fun attribution(includeAdvertiser: Boolean): LinearLayout = group(LinearLayout.VERTICAL).apply {
        gravity = Gravity.START or Gravity.CENTER_VERTICAL
        addView(label(context.getString(R.string.ad_admob_attribution), 11f).apply { tag = ATTRIBUTION_TAG })
        if (includeAdvertiser && !ad.advertiser.isNullOrBlank()) {
            addView(label(ad.advertiser, 11f).apply { root.advertiserView = this })
        }
    }

    /** CTA 保留完整文字及 SDK 点击处理，长文字可增加高度。 */
    fun action(): Button = Button(context).apply {
        text = ad.callToAction
        textSize = 13f
        isAllCaps = false
        includeFontPadding = false
        minWidth = 0
        minimumWidth = 0
        minHeight = dp(40)
        minimumHeight = dp(40)
        setPadding(dp(12), dp(6), dp(12), dp(6))
        visibility = if (ad.callToAction.isNullOrBlank()) View.GONE else View.VISIBLE
        root.callToActionView = this
    }

    /** 文本遵循系统字体缩放，颜色由 Compose 当前主题同步。 */
    private fun label(value: String?, size: Float): TextView = TextView(context).apply {
        text = value
        textSize = size
        includeFontPadding = false
    }

    companion object {
        /** 用于定位非 SDK 素材的广告标记，不包含广告内容。 */
        private const val ATTRIBUTION_TAG = "admob_attribution"

        /** 同步主题与宿主卡片描边，只更新已有 View，不重新注册素材或请求广告。 */
        fun applyColors(root: NativeAdView, surface: Int, foreground: Int, muted: Int, primary: Int, onPrimary: Int, outline: Int) {
            /** 密度用于对齐宿主卡片的 10dp 圆角和 1dp 描边，不裁切 SDK 浮层。 */
            val density = root.resources.displayMetrics.density
            root.background = GradientDrawable().apply {
                setColor(surface)
                cornerRadius = 10 * density
                setStroke(kotlin.math.ceil(density).toInt(), outline)
            }
            (root.headlineView as? TextView)?.setTextColor(foreground)
            (root.bodyView as? TextView)?.setTextColor(muted)
            (root.advertiserView as? TextView)?.setTextColor(muted)
            root.findViewWithTag<TextView>(ATTRIBUTION_TAG)?.setTextColor(muted)
            (root.callToActionView as? Button)?.apply {
                setTextColor(onPrimary)
                backgroundTintList = ColorStateList.valueOf(primary)
            }
        }
    }
}
