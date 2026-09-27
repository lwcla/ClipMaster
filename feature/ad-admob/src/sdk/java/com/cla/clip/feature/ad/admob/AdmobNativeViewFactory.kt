package com.cla.clip.feature.ad.admob

import android.content.Context
import android.util.Log
import android.view.Gravity
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.ImageView
import android.widget.LinearLayout
import com.google.android.libraries.ads.mobile.sdk.nativead.MediaView
import com.google.android.libraries.ads.mobile.sdk.nativead.NativeAd
import com.google.android.libraries.ads.mobile.sdk.nativead.NativeAdView

/** 所有 AdMob 广告位共用的布局入口，SDK 独占素材点击和曝光。 */
internal object AdmobNativeViewFactory {
    /** 主线程加载完成后、挂载前选择版式；失败时销毁半成品并向会话传播。 */
    fun create(context: Context, ad: NativeAd): NativeAdView {
        /** 未知类型保守保留媒体，不在显示后切换布局。 */
        val hasVideo = ad.mediaContent?.hasVideoContent
        /** 纯策略不依赖 SDK，单测保护视频和未知媒体的展示契约。 */
        val requiresMedia = AdmobNativeLayoutPolicy.requiresMedia(hasVideo)
        /** 原生根容器不裁切 SDK 的 AdChoices 浮层。 */
        val root = NativeAdView(context)
        try {
            /** 素材构造集中复用，入口只编排布局。 */
            val assets = AdmobNativeAssets(context, ad, root)
            /** A1 创建真实媒体，A2 使用 SDK 支持的可空媒体参数。 */
            val media = if (requiresMedia) createMediaCard(context, root, assets) else {
                createSummaryCard(root, assets)
                null
            }
            root.registerNativeAd(ad, media)
            if (BuildConfig.DEBUG) Log.d("AdmobLayout", "layout=${if (requiresMedia) "A1" else "A2"} hasVideo=$hasVideo")
            return root
        } catch (failure: Exception) {
            root.destroy()
            throw failure
        }
    }

    /** A1 横排：120dp 媒体和右侧素材栏，长标题/按钮允许自然增高。 */
    private fun createMediaCard(context: Context, root: NativeAdView, assets: AdmobNativeAssets): MediaView {
        /** 左右和顶部 14dp 留出素材空间，底部 5dp 保持紧凑，不固定整卡高度。 */
        val content = assets.group(LinearLayout.HORIZONTAL).apply {
            setPadding(assets.dp(14), assets.dp(14), assets.dp(14), assets.dp(5))
        }
        root.addView(content, assets.params(MATCH_PARENT, WRAP_CONTENT))
        /** SDK 按比例显示视频/图片，不拉伸或裁掉主体。 */
        val media = MediaView(context).apply { imageScaleType = ImageView.ScaleType.FIT_CENTER }
        content.addView(media, assets.params(120, 120).apply { marginEnd = assets.dp(10) })
        /** 右栏占剩余宽度，首行末端独立留出 AdChoices 区域。 */
        val details = assets.group(LinearLayout.VERTICAL).apply { gravity = Gravity.START }
        content.addView(details, assets.params(0, WRAP_CONTENT, 1f))
        details.addView(assets.attribution(false).apply {
            setPadding(0, 0, assets.dp(32), 0)
            minimumHeight = assets.dp(24)
        }, assets.params(MATCH_PARENT, WRAP_CONTENT))
        /** 必需图标和标题同排，省略可选正文控制总高。 */
        val heading = assets.group(LinearLayout.HORIZONTAL)
        heading.addView(assets.icon(32))
        heading.addView(assets.headline(), assets.params(0, WRAP_CONTENT, 1f))
        details.addView(heading, assets.params(MATCH_PARENT, WRAP_CONTENT))
        details.addView(assets.action(), assets.params(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = assets.dp(4) })
        return media
    }

    /** A2 图标摘要：上方内容和下方广告标记/CTA 共用紧凑容器，不创建主媒体。 */
    private fun createSummaryCard(root: NativeAdView, assets: AdmobNativeAssets) {
        /** 左右和顶部 14dp 留出素材空间，底部 5dp 保持紧凑，缺失可选内容不额外占位。 */
        val content = assets.group(LinearLayout.VERTICAL).apply {
            setPadding(assets.dp(14), assets.dp(14), assets.dp(14), assets.dp(5))
        }
        root.addView(content, assets.params(MATCH_PARENT, WRAP_CONTENT))
        /** 标题区域末端为 SDK 默认右上角 AdChoices 留出空间。 */
        val heading = assets.group(LinearLayout.HORIZONTAL).apply { setPadding(0, 0, assets.dp(32), 0) }
        heading.addView(assets.icon(56))
        /** 标题和正文按实际行数测量，不固定高度或截断。 */
        val text = assets.group(LinearLayout.VERTICAL).apply { gravity = Gravity.START }
        text.addView(assets.headline(), assets.params(MATCH_PARENT, WRAP_CONTENT))
        text.addView(assets.body(), assets.params(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = assets.dp(4) })
        heading.addView(text, assets.params(0, WRAP_CONTENT, 1f))
        content.addView(heading, assets.params(MATCH_PARENT, WRAP_CONTENT))
        /** 底行均分宽度，长 CTA 可换行，广告主不挤占按钮。 */
        val footer = assets.group(LinearLayout.HORIZONTAL)
        footer.addView(assets.attribution(true), assets.params(0, WRAP_CONTENT, 1f).apply { marginEnd = assets.dp(8) })
        footer.addView(assets.action(), assets.params(0, WRAP_CONTENT, 1f))
        content.addView(footer, assets.params(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = assets.dp(8) })
    }
}
