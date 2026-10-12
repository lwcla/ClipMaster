package com.cla.clip.feature.ad.admob

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Outline
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.ImageButton
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

    /** 独立 48dp 宿主热区；叉号顶部对齐媒体、右侧对齐 CTA 背景，不注册为广告资产。 */
    fun dismissButton(action: Button): ImageButton = ImageButton(context).apply {
        tag = DISMISS_TAG
        layoutParams = params(48, 48)
        setImageResource(R.drawable.ad_admob_ic_close)
        /** 旧 24dp 矢量画布中的有效叉号只有 14dp，去掉透明圈后保持原可见尺寸。 */
        val iconSize = dp(14)
        /** 顶部不留内距，剩余 34dp 留在底部维持热区；避免叉号随文字垂直居中下移。 */
        val bottomInset = dp(48) - iconSize
        setPadding(dp(48) - iconSize, 0, 0, bottomInset)
        // CTA 宽度变化时重新对齐；仅修改图标内距，不更换 SDK 点击资产或缩小关闭热区。
        action.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            /** 关闭和媒体卡共用实测背景轮廓，不把 CTA 文字 padding 当作可见边缘。 */
            val bounds = actionBackgroundBounds(action)
            /** 非矩形或无背景时按控件右边缘降级，不猜 OEM 内距或使用隐藏 API。 */
            val rightInset = if (bounds != null) {
                (action.width - bounds.right).coerceIn(0, dp(48) - iconSize)
            } else 0
            // 内距未变时不触发重复测量；左右总和固定，因此图标始终维持 14dp。
            if (paddingRight != rightInset) {
                setPadding(dp(48) - iconSize - rightInset, 0, rightInset, bottomInset)
            }
        }
        scaleType = ImageView.ScaleType.FIT_CENTER
        setBackgroundResource(android.R.drawable.list_selector_background)
        contentDescription = context.getString(R.string.ad_admob_dismiss)
        isEnabled = false
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
        /** 主线程测量/布局后读取 CTA 矩形轮廓；无背景或非矩形时返回空，调用方按控件边缘降级。 */
        fun actionBackgroundBounds(action: Button): Rect? {
            /** 背景仍属于原按钮，只同步其尺寸，不替换主题或 SDK 点击资产。 */
            val background = action.background ?: return null
            /** 使用本轮测量的像素宽高，支持尚未 layout 的首次测量，避免读取旧尺寸。 */
            val width = action.measuredWidth
            val height = action.measuredHeight
            if (width <= 0 || height <= 0) return null
            /** 公开轮廓用于提取背景可见矩形，最低支持 Android 7.0，无反射依赖。 */
            val outline = Outline()
            /** 结果坐标相对 CTA 本身，仅用于布局，不持有素材内容。 */
            val bounds = Rect()
            background.setBounds(0, 0, width, height)
            background.getOutline(outline)
            return if (outline.getRect(bounds)) bounds else null
        }

        /** 用于定位非 SDK 素材的广告标记，不包含广告内容。 */
        private const val ATTRIBUTION_TAG = "admob_attribution"

        /** 定位非 SDK 点击资产的关闭控件，标签仅在本模块原生卡片内使用。 */
        private const val DISMISS_TAG = "admob_host_dismiss"

        /** 主线程回绑最新宿主回调；解绑时传入空回调，避免缓存 View 保留旧页面引用。 */
        fun updateDismissControl(root: NativeAdView, enabled: Boolean, onDismiss: () -> Unit) {
            // 按钮不改变 SDK 素材的监听；容器仍负责暂停/遮挡时的全部输入门控。
            root.findViewWithTag<ImageButton>(DISMISS_TAG)?.apply {
                isEnabled = enabled
                setOnClickListener { onDismiss() }
            }
        }

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
            root.findViewWithTag<ImageButton>(DISMISS_TAG)?.imageTintList = ColorStateList.valueOf(muted)
            (root.callToActionView as? Button)?.apply {
                setTextColor(onPrimary)
                backgroundTintList = ColorStateList.valueOf(primary)
            }
        }
    }
}
