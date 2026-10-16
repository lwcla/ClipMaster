package com.cla.clip.feature.ad.admob

import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.InsetDrawable
import android.graphics.drawable.RippleDrawable
import android.view.Gravity
import android.widget.Button
import android.widget.TextView
import com.google.android.libraries.ads.mobile.sdk.nativead.NativeAdView

/** 卡片和素材的主题样式，只更新已有 View；不改变 SDK 注册和请求身份。 */
internal object AdmobNativeCardStyle {
    /** 统一自绘 CTA 的显色区及向上热区，避免系统 Button 透明内距影响对齐。 */
    fun applyAction(button: Button, primary: Int, onPrimary: Int) {
        /** 资源分别表示显色背景、透明上沿及文字内距，单位均为像素。 */
        val resources = button.resources
        val inset = resources.getDimensionPixelSize(R.dimen.ad_admob_action_hit_inset)
        val horizontal = resources.getDimensionPixelSize(R.dimen.ad_admob_action_padding_horizontal)
        val vertical = resources.getDimensionPixelSize(R.dimen.ad_admob_action_padding_vertical)
        val face = GradientDrawable().apply {
            setColor(primary)
            cornerRadius = resources.getDimension(R.dimen.ad_admob_action_radius)
        }
        button.backgroundTintList = null
        button.stateListAnimator = null
        button.background = InsetDrawable(RippleDrawable(
            ColorStateList.valueOf((onPrimary and 0x00FFFFFF) or 0x24000000), face, null), 0, inset, 0, 0)
        button.setPadding(horizontal, inset + vertical, horizontal, vertical)
        button.minHeight = resources.getDimensionPixelSize(R.dimen.ad_admob_action_height) + inset
        button.minimumHeight = button.minHeight
        button.gravity = Gravity.CENTER
        button.setTextColor(onPrimary)
    }

    /** Compose 传入主题颜色，重复相同颜色不会重设背景或引发额外测量。 */
    fun applyColors(root: NativeAdView, surface: Int, foreground: Int, muted: Int, primary: Int, onPrimary: Int, outline: Int) {
        /** 仅缓存低敏颜色值，生命周期与根 View 一致。 */
        val palette = intArrayOf(surface, foreground, muted, primary, onPrimary, outline)
        if ((root.getTag(R.id.ad_admob_style_colors) as? IntArray)?.contentEquals(palette) == true) return
        root.setTag(R.id.ad_admob_style_colors, palette)
        root.background = GradientDrawable().apply {
            setColor(surface)
            cornerRadius = root.resources.getDimension(R.dimen.ad_admob_card_radius)
            setStroke(root.resources.getDimensionPixelSize(R.dimen.ad_admob_border), outline)
        }
        (root.headlineView as? TextView)?.setTextColor(foreground)
        (root.bodyView as? TextView)?.setTextColor(muted)
        (root.advertiserView as? TextView)?.setTextColor(muted)
        root.findViewById<TextView>(R.id.ad_admob_attribution)?.apply {
            setTextColor(muted)
            background = GradientDrawable().apply {
                setColor(Color.TRANSPARENT)
                cornerRadius = resources.getDimension(R.dimen.ad_admob_badge_radius)
                setStroke(resources.getDimensionPixelSize(R.dimen.ad_admob_border), muted)
            }
        }
        root.findViewById<AdmobDismissControl>(R.id.ad_admob_dismiss)?.applyColor(muted)
        (root.callToActionView as? Button)?.let { applyAction(it, primary, onPrimary) }
    }
}
