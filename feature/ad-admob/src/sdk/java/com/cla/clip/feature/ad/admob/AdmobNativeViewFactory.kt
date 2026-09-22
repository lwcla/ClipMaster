package com.cla.clip.feature.ad.admob

import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.google.android.libraries.ads.mobile.sdk.nativead.MediaView
import com.google.android.libraries.ads.mobile.sdk.nativead.NativeAd
import com.google.android.libraries.ads.mobile.sdk.nativead.NativeAdView

/** 真实原生素材绑定器；SDK 负责点击、曝光和右上角 AdChoices，禁止叠加自定义点击层。 */
internal object AdmobNativeViewFactory {
    /** 仅主线程创建；任何失败销毁半成品 View 并向调用方传播。 */
    fun create(context: Context, ad: NativeAd): NativeAdView {
        /** 根 View 由请求持有，生命周期结束销毁。 */
        val root = NativeAdView(context)
        try {
            /** 转换密度，保证媒体区域不小于 120dp 并留足 AdChoices 空间。 */
            val density = context.resources.displayMetrics.density
            /** 素材纵向容器，顶部预留角标空间。 */
            val content = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding((12 * density).toInt(), (28 * density).toInt(), (12 * density).toInt(), (12 * density).toInt())
            }
            root.addView(content, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            /** 明确广告归属标记，不注册为可点击素材。 */
            val attribution = TextView(context).apply { setText(R.string.ad_admob_attribution) }
            content.addView(attribution)
            /** 标题按 SDK 原样显示，不截断核心广告声明。 */
            val headline = TextView(context).apply { text = ad.headline; textSize = 18f }
            content.addView(headline)
            root.headlineView = headline
            /** 图标只在 SDK 返回时展示。 */
            val icon = ImageView(context).apply {
                setImageDrawable(ad.icon?.drawable)
                visibility = if (ad.icon == null) View.GONE else View.VISIBLE
            }
            content.addView(icon, LinearLayout.LayoutParams((48 * density).toInt(), (48 * density).toInt()))
            root.iconView = icon
            /** 媒体必须通过 SDK MediaView 呈现，以保留视频和曝光契约。 */
            val media = MediaView(context)
            content.addView(media, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (180 * density).toInt()))
            /** 可选正文为空时不留空白。 */
            val body = TextView(context).apply { text = ad.body; visibility = if (ad.body == null) View.GONE else View.VISIBLE }
            content.addView(body)
            root.bodyView = body
            /** 广告主作为独立素材注册，避免混入宿主文案。 */
            val advertiser = TextView(context).apply { text = ad.advertiser; visibility = if (ad.advertiser == null) View.GONE else View.VISIBLE }
            content.addView(advertiser)
            root.advertiserView = advertiser
            /** SDK 独占 CTA 点击，宿主不安装监听器。 */
            val action = Button(context).apply { text = ad.callToAction; visibility = if (ad.callToAction == null) View.GONE else View.VISIBLE }
            content.addView(action)
            root.callToActionView = action
            root.registerNativeAd(ad, media)
            return root
        } catch (failure: Exception) {
            root.destroy()
            throw failure
        }
    }
}
