package com.cla.clip.feature.ad.admob

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.InsetDrawable
import android.graphics.drawable.RippleDrawable
import android.widget.ImageButton
import android.widget.ImageView
import com.google.android.libraries.ads.mobile.sdk.nativead.NativeAdView

/** 独立宿主关闭控件，图形与热区自洽，不依赖 CTA 轮廓或 SDK 点击监听。 */
internal class AdmobDismissControl(
    /** 控件随广告 View 释放，不持有额外页面资源。 */
    context: Context,
) : ImageButton(context, null, 0) {
    init {
        id = R.id.ad_admob_dismiss
        /** 热区、图形和顶部偏移均由本控件资源决定。 */
        val touch = resources.getDimensionPixelSize(R.dimen.ad_admob_touch_size)
        val icon = resources.getDimensionPixelSize(R.dimen.ad_admob_close_icon)
        val top = resources.getDimensionPixelSize(R.dimen.ad_admob_close_top)
        setImageResource(R.drawable.ad_admob_ic_close)
        scaleType = ImageView.ScaleType.FIT_CENTER
        setPadding(touch - icon, top, 0, touch - icon - top)
        contentDescription = context.getString(R.string.ad_admob_dismiss)
        background = null
        isEnabled = false
        isFocusable = false
    }

    /** 即使无障碍直接调用，也不能触发已禁用或已解绑的关闭操作。 */
    override fun performClick(): Boolean = isEnabled && super.performClick()

    /** 只绘制图形附近的反馈，48dp 触摸区域保持完整。 */
    fun applyColor(color: Int) {
        imageTintList = ColorStateList.valueOf(color)
        /** 圆形反馈以可见叉号中心定位，与别的 View 无布局耦合。 */
        val touch = resources.getDimensionPixelSize(R.dimen.ad_admob_touch_size)
        val icon = resources.getDimensionPixelSize(R.dimen.ad_admob_close_icon)
        val top = resources.getDimensionPixelSize(R.dimen.ad_admob_close_top)
        val feedback = resources.getDimensionPixelSize(R.dimen.ad_admob_close_feedback)
        val extra = (feedback - icon) / 2
        val mask = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.WHITE) }
        background = InsetDrawable(RippleDrawable(ColorStateList.valueOf((color and 0x00FFFFFF) or 0x24000000), null, mask),
            touch - icon - extra, top - extra, -extra, touch - top - icon - extra)
        // 替换背景后重新确定图标内距，防止 Drawable 自带 padding 改变可见对齐。
        setPadding(touch - icon, top, 0, touch - icon - top)
    }

    companion object {
        /** 主线程同步最新许可；暂停或解绑直接清空监听，避免缓存 View 引用旧页面。 */
        fun update(root: NativeAdView, enabled: Boolean, onDismiss: (() -> Unit)? = null) {
            root.findViewById<AdmobDismissControl>(R.id.ad_admob_dismiss)?.apply {
                isEnabled = enabled && onDismiss != null
                isFocusable = isEnabled
                // 实际触发时调用方仍会复核实时输入门；禁用时不保留旧回调。
                setOnClickListener(if (isEnabled) OnClickListener { onDismiss?.invoke() } else null)
            }
        }
    }
}
