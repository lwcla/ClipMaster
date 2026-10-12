package com.cla.clip.feature.ad.admob

import android.content.Context
import android.util.Log
import android.view.Gravity
import android.view.View
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
        /** 选项内容随已加载素材确定，禁止把缺失图标误判为绘制失败并主动隐藏 SDK 覆盖层。 */
        val adChoices = ad.adChoicesInfo
        /** 数量仅用于布局判断和低敏 debug 诊断，不记录图片地址或内容。 */
        val adChoicesImageCount = adChoices?.images?.size ?: 0
        /** 有图标或文字才留出局部空间，无内容时声明恢复与图片左对齐。 */
        val hasAdChoicesContent = AdmobNativeLayoutPolicy.requiresAdChoicesSpace(adChoicesImageCount, adChoices?.text)
        /** 原生根容器不裁切 SDK 浮层；媒体/关闭的位置按物理左右约定，文字仍保留双向排版。 */
        val root = NativeAdView(context).apply {
            // SDK BOTTOM_LEFT 是物理左侧，固定素材排布避免 RTL 将局部预留镜像到另一角落。
            layoutDirection = View.LAYOUT_DIRECTION_LTR
        }
        try {
            /** 素材构造集中复用，入口只编排布局。 */
            val assets = AdmobNativeAssets(context, ad, root)
            /** A1 创建真实媒体，A2 使用 SDK 支持的可空媒体参数。 */
            val media = if (requiresMedia) createMediaCard(context, root, assets, hasAdChoicesContent) else {
                createSummaryCard(root, assets, hasAdChoicesContent)
                null
            }
            root.registerNativeAd(ad, media)
            // 单次创建只记录选项存在与数量，不输出选项文字、URI、素材或广告响应。
            if (BuildConfig.DEBUG) Log.d("AdmobLayout",
                "layout=${if (requiresMedia) "A1" else "A2"} hasVideo=$hasVideo" +
                    " hasAdChoicesContent=$hasAdChoicesContent adChoicesImageCount=$adChoicesImageCount" +
                    " hasAdChoicesText=${!adChoices?.text.isNullOrBlank()}")
            return root
        } catch (failure: Exception) {
            root.destroy()
            throw failure
        }
    }

    /** A1 关闭顶对齐、CTA 可见底边对齐媒体；声明紧随标题，长内容同步增高两栏。 */
    private fun createMediaCard(context: Context, root: NativeAdView, assets: AdmobNativeAssets,
                                hasAdChoicesContent: Boolean): MediaView {
        /** SDK 按比例显示视频/图片，不拉伸或裁掉主体。 */
        val media = MediaView(context).apply { imageScaleType = ImageView.ScaleType.FIT_CENTER }
        /** 有 SDK 选项时完整媒体下方留出 24dp；无内容时回收局部空白。 */
        val mediaColumn = assets.group(LinearLayout.VERTICAL).apply {
            setPadding(0, 0, 0, if (hasAdChoicesContent) assets.dp(24) else 0)
            addView(media, assets.params(120, 120))
        }
        /** 同卡 CTA 的实测背景轮廓用于关闭图标右对齐，两种控件保持各自监听。 */
        val action = assets.action()
        /** 右栏至少等高于 120dp 媒体，剩余空间放在标题与 CTA 之间，完整文字可继续增高。 */
        val details = assets.group(LinearLayout.VERTICAL).apply {
            gravity = Gravity.START
            minimumHeight = assets.dp(120)
        }
        /** 必需图标、完整标题与独立关闭热区顶对齐，省略可选正文控制总高。 */
        val heading = assets.group(LinearLayout.HORIZONTAL).apply { gravity = Gravity.START or Gravity.TOP }
        heading.addView(assets.icon(32))
        /** 声明紧随完整标题，避免 48dp 关闭热区在短标题和声明之间产生整块空白。 */
        val title = assets.group(LinearLayout.VERTICAL).apply { gravity = Gravity.START }
        title.addView(assets.headline(), assets.params(MATCH_PARENT, WRAP_CONTENT))
        title.addView(assets.attribution(false), assets.params(MATCH_PARENT, WRAP_CONTENT))
        heading.addView(title, assets.params(0, WRAP_CONTENT, 1f))
        heading.addView(assets.dismissButton(action))
        details.addView(heading, assets.params(MATCH_PARENT, WRAP_CONTENT))
        // 空白只在标题和按钮之间伸展；没有点击或 SDK 资产，CTA 继续使用原有监听。
        details.addView(View(context), assets.params(MATCH_PARENT, 0, 1f))
        details.addView(action, assets.params(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = assets.dp(4) })
        /** 对齐测量集中在两栏容器；左右 14dp、上下 5dp 与摘要卡保持一致。 */
        val content = AdmobMediaCardRow(context, media, mediaColumn, details, action, assets.dp(120)).apply {
            setPadding(assets.dp(14), assets.dp(5), assets.dp(14), assets.dp(5))
            addView(mediaColumn, assets.params(120, WRAP_CONTENT).apply { marginEnd = assets.dp(10) })
            addView(details, assets.params(0, WRAP_CONTENT, 1f))
        }
        root.addView(content, assets.params(MATCH_PARENT, WRAP_CONTENT))
        return media
    }

    /** A2 顶部关闭控件不随标题下移；声明在标题下且默认与图标左对齐，有选项才让位。 */
    private fun createSummaryCard(root: NativeAdView, assets: AdmobNativeAssets, hasAdChoicesContent: Boolean) {
        /** 左右 14dp 保持描边内距，上下同为 5dp，不再预留整条顶部空白。 */
        val content = assets.group(LinearLayout.VERTICAL).apply {
            setPadding(assets.dp(14), assets.dp(5), assets.dp(14), assets.dp(5))
        }
        root.addView(content, assets.params(MATCH_PARENT, WRAP_CONTENT))
        /** 同卡 CTA 在标题行构建前建立，关闭图标按其背景可见边界对齐。 */
        val action = assets.action()
        /** 图标与文字区顶对齐，长文字不把关闭控件或图标推离内容顶部。 */
        val heading = assets.group(LinearLayout.HORIZONTAL).apply { gravity = Gravity.START or Gravity.TOP }
        heading.addView(assets.icon(56))
        /** 标题和正文按实际行数测量，不固定高度或截断。 */
        val text = assets.group(LinearLayout.VERTICAL).apply { gravity = Gravity.START }
        /** 标题与 48dp 热区顶对齐且独立占宽；标题换行只增高内容，不改变叉号顶部。 */
        val title = assets.group(LinearLayout.HORIZONTAL).apply { gravity = Gravity.START or Gravity.TOP }
        title.addView(assets.headline(), assets.params(0, WRAP_CONTENT, 1f))
        title.addView(assets.dismissButton(action))
        text.addView(title, assets.params(MATCH_PARENT, WRAP_CONTENT))
        text.addView(assets.body(), assets.params(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = assets.dp(4) })
        heading.addView(text, assets.params(0, WRAP_CONTENT, 1f))
        content.addView(heading, assets.params(MATCH_PARENT, WRAP_CONTENT))
        /** 底行均分宽度，长 CTA 可换行，广告主不挤占按钮。 */
        val footer = assets.group(LinearLayout.HORIZONTAL)
        footer.addView(assets.attribution(true).apply {
            // 空选项不保留缩进；有图标或文字时局部让位，SDK 覆盖层的显示与点击仍由 SDK 管理。
            setPaddingRelative(if (hasAdChoicesContent) assets.dp(32) else 0, 0, 0, 0)
        }, assets.params(0, WRAP_CONTENT, 1f).apply { marginEnd = assets.dp(8) })
        footer.addView(action, assets.params(0, WRAP_CONTENT, 1f))
        content.addView(footer, assets.params(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = assets.dp(8) })
    }
}
