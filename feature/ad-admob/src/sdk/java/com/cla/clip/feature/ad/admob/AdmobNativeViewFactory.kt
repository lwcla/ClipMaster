package com.cla.clip.feature.ad.admob

import android.content.Context
import android.util.Log
import android.view.View
import android.view.ViewGroup
import com.google.android.libraries.ads.mobile.sdk.nativead.MediaView
import com.google.android.libraries.ads.mobile.sdk.nativead.NativeAd
import com.google.android.libraries.ads.mobile.sdk.nativead.NativeAdView

/** 全部 AdMob 位置共用的唯一创建入口，只编排素材、布局和单次 SDK 注册。 */
internal object AdmobNativeViewFactory {
    /** 主线程挂载前完成构造，失败销毁半成品并交回既有 RenderFailed 流程。 */
    fun create(context: Context, ad: NativeAd): NativeAdView {
        /** 未知媒体类型必须保留视频所需区域，不能误走摘要分支。 */
        val requiresMedia = AdmobNativeLayoutPolicy.requiresMedia(ad.mediaContent?.hasVideoContent)
        /** SDK 左下选项与已确认版式采用物理左右，文字保留自身双向排版。 */
        val root = NativeAdView(context).apply {
            layoutDirection = View.LAYOUT_DIRECTION_LTR
            clipChildren = false
            clipToPadding = false
        }
        try {
            /** 所有素材先进入同一个根 View，再注册一次；窗口变化只重新测量。 */
            val elements = AdmobNativeAssets(context, ad, root).create(requiresMedia)
            root.addView(AdmobNativeCardLayout(context, elements),
                ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            root.registerNativeAd(ad, elements.media as? MediaView)
            if (BuildConfig.DEBUG) {
                // 只记录公开选项资产是否存在，用于区分缺失素材与覆盖层未显示，不输出素材正文或标识。
                val choicesInfo = ad.adChoicesInfo
                Log.d("AdmobLayout", "创建卡片 类型=${if (requiresMedia) "媒体" else "摘要"}" +
                    " 有图标=${ad.icon != null} 有正文=${elements.body.visibility != View.GONE}" +
                    " 有广告主=${!ad.advertiser.isNullOrBlank()} 有视频=${ad.mediaContent?.hasVideoContent}" +
                    " 有选项信息=${choicesInfo != null} 有选项图片=${!choicesInfo?.images.isNullOrEmpty()}" +
                    " 有选项文案=${!choicesInfo?.text.isNullOrBlank()}")
            }
            return root
        } catch (failure: Exception) {
            root.destroy()
            throw failure
        }
    }
}
