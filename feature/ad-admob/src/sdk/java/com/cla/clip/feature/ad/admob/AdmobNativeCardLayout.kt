package com.cla.clip.feature.ad.admob

import android.content.Context
import android.graphics.Rect
import android.util.Log
import android.view.View
import android.view.ViewGroup

/** 共享原生卡片布局，只测量已有 View；不申请广告、不重建素材、不处理点击。 */
internal class AdmobNativeCardLayout(
    /** 与原生卡片同生命周期的上下文，仅用于资源尺寸。 */
    context: Context,
    /** 所有素材一次加入本容器，窗口变化只重新测量和排布。 */
    private val elements: AdmobNativeCardElements,
) : ViewGroup(context) {
    /** 本轮测量确定的局部坐标，onLayout 仅应用结果。 */
    private val positions = LinkedHashMap<View, Rect>()
    /** 媒体方向用于分支变化诊断，不作为请求或曝光状态。 */
    private var previousArrangement: AdmobMediaArrangement? = null
    /** 统一小间距、列间距和必要媒体尺寸，单位为像素。 */
    private val smallGap = dimension(R.dimen.ad_admob_gap_small)
    private val gap = dimension(R.dimen.ad_admob_gap)
    private val mediaGap = dimension(R.dimen.ad_admob_media_gap)
    private val mediaSize = dimension(R.dimen.ad_admob_media_size)
    private val touchSize = dimension(R.dimen.ad_admob_touch_size)
    private val actionMinWidth = dimension(R.dimen.ad_admob_action_min_width)

    init {
        /** 内距含描边，避免内容压到卡片边线。 */
        val inset = dimension(R.dimen.ad_admob_card_padding) + dimension(R.dimen.ad_admob_border)
        setPadding(inset, inset, inset, inset)
        layoutDirection = LAYOUT_DIRECTION_LTR
        clipChildren = false
        clipToPadding = false
        listOfNotNull(elements.icon, elements.headline, elements.identity, elements.body,
            elements.action, elements.dismiss, elements.media, elements.choices).forEach { child ->
            // 只在构造时接入一次，避免重新绑定 SDK 资产。
            addView(child, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
        }
    }

    /** 自然高度交给现有宿主空间预算处理；横排失败后在同次测量内完成纵排。 */
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        positions.clear()
        /** 宿主正常提供宽度；未知约束保守按屏宽测量并强制媒体纵排。 */
        val knownWidth = MeasureSpec.getMode(widthMeasureSpec) != MeasureSpec.UNSPECIFIED
        val width = if (knownWidth) MeasureSpec.getSize(widthMeasureSpec) else resources.displayMetrics.widthPixels
        val contentWidth = (width - paddingLeft - paddingRight).coerceAtLeast(0)
        // 选项只测内容所需矩形；宽度用于摘要同行判断，高度用于自然底部留位。
        if (elements.choices.visibility != GONE) {
            measureExact(elements.choices, elements.choices.minimumWidth.coerceAtMost(width), elements.choices.minimumHeight)
        }
        /** 头部所有资产先完整测高；正文只在摘要版归入头部。 */
        val headerBottom = measureHeader(contentWidth)
        /** 摘要优先共享操作区；视频只调整底部留位，不改变内容区相对边线。 */
        val totalHeight = if (elements.media == null) measureSummaryFooter(contentWidth, headerBottom)
            else placeChoicesBelow(measureMediaContent(contentWidth, headerBottom, knownWidth))
        setMeasuredDimension(resolveSizeAndState(width, widthMeasureSpec, 0),
            resolveSizeAndState(totalHeight, heightMeasureSpec, 0))
    }

    /** 排布不再计算补偿或触发 requestLayout，SDK 的子树测量变化由正常 View 流程承接。 */
    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        positions.forEach { (child, bounds) -> child.layout(bounds.left, bounds.top, bounds.right, bounds.bottom) }
    }

    /** 关闭独占 48dp 换算后的热区，标题/身份/摘要正文使用同一文字列。 */
    private fun measureHeader(contentWidth: Int): Int {
        /** 视频和摘要图标分别为 40/56dp；缺失则连同列间距一起回收。 */
        val iconSize = dimension(if (elements.media == null) R.dimen.ad_admob_summary_icon else R.dimen.ad_admob_video_icon)
        val iconSpace = if (elements.icon.visibility == GONE) 0 else iconSize + gap
        val textLeft = paddingLeft + iconSpace
        val textWidth = (contentWidth - iconSpace - touchSize - gap).coerceAtLeast(0)
        measureExact(elements.dismiss, touchSize, touchSize)
        place(elements.dismiss, paddingLeft + contentWidth - touchSize, paddingTop)
        if (elements.icon.visibility != GONE) {
            measureExact(elements.icon, iconSize, iconSize)
            place(elements.icon, paddingLeft, paddingTop)
        }
        /** 下一段文字的顶部，只有实际内容之间才加入间距。 */
        var textBottom = paddingTop
        listOfNotNull(elements.headline, elements.identity, elements.body.takeIf { elements.media == null }).forEach { child ->
            if (child.visibility != GONE) {
                if (textBottom > paddingTop) textBottom += smallGap
                measureNatural(child, textWidth, exactWidth = true)
                place(child, textLeft, textBottom)
                textBottom += child.measuredHeight
            }
        }
        return maxOf(textBottom, paddingTop + touchSize, paddingTop + if (iconSpace == 0) 0 else iconSize)
    }

    /** 摘要选项优先占用 CTA 左侧空白；完整内容横向放不下时才另排。 */
    private fun measureSummaryFooter(contentWidth: Int, headerBottom: Int): Int {
        if (elements.action.visibility == GONE) return placeChoicesBelow(headerBottom)
        /** footerTop 是内容区与操作区唯一的外部间距。 */
        val footerTop = headerBottom + smallGap
        measureAction(contentWidth)
        // CTA 保持自然宽度；不通过压窄按钮或截断文本换取同行。
        val actionLeft = paddingLeft + contentWidth - elements.action.measuredWidth
        val actionBottom = footerTop + elements.action.measuredHeight
        place(elements.action, actionLeft, footerTop)
        if (elements.choices.visibility == GONE) return actionBottom + paddingBottom
        if (elements.choices.minimumWidth + gap > actionLeft) return placeChoicesBelow(actionBottom)
        // 选项锚定根容器左下；只在其高度超出操作区时才增加卡高。
        val totalHeight = maxOf(actionBottom + paddingBottom, footerTop + elements.choices.measuredHeight)
        place(elements.choices, 0, totalHeight - elements.choices.measuredHeight)
        return totalHeight
    }

    /** 选项无法同行时按实际预算留高，普通底内距与选项区域不重复叠加。 */
    private fun placeChoicesBelow(contentBottom: Int): Int {
        if (elements.choices.visibility == GONE) return contentBottom + paddingBottom
        // 始终给正文、视频或 CTA 留出小间隔，选项本身贴 SDK 根容器左下角。
        val totalHeight = contentBottom + maxOf(paddingBottom, smallGap + elements.choices.measuredHeight)
        place(elements.choices, 0, totalHeight - elements.choices.measuredHeight)
        return totalHeight
    }

    /** 视频右侧正文贴顶、按钮贴底；容纳不下则纵排且媒体恒定 120dp。 */
    private fun measureMediaContent(contentWidth: Int, headerBottom: Int, knownWidth: Boolean): Int {
        /** 媒体只在此分支存在；不通过隐藏必需媒体降级为摘要卡。 */
        val media = checkNotNull(elements.media)
        val contentTop = headerBottom + gap
        val textWidth = (contentWidth - mediaSize - mediaGap).coerceAtLeast(0)
        measureExact(media, mediaSize, mediaSize)
        measureNatural(elements.body, textWidth, exactWidth = true)
        measureNatural(elements.action, textWidth, exactWidth = true)
        /** 右栏的透明按钮上沿已提供安全间隔，不再叠加弹性占位 View。 */
        val arrangement = AdmobNativeLayoutPolicy.mediaArrangement(
            if (knownWidth) textWidth else 0, dimension(R.dimen.ad_admob_media_text_min_width),
            resources.configuration.fontScale, visibleHeight(elements.body) + visibleHeight(elements.action), mediaSize,
        )
        /** 当前内容底边用于把选项放在完整媒体/正文之后。 */
        val contentBottom = if (arrangement == AdmobMediaArrangement.SideBySide) {
            place(media, paddingLeft, contentTop)
            place(elements.body, paddingLeft + mediaSize + mediaGap, contentTop)
            place(elements.action, paddingLeft + mediaSize + mediaGap, contentTop + mediaSize - visibleHeight(elements.action))
            contentTop + mediaSize
        } else {
            place(media, paddingLeft + (contentWidth - mediaSize).coerceAtLeast(0) / 2, contentTop)
            measureNatural(elements.body, contentWidth, exactWidth = true)
            measureAction(contentWidth)
            /** 纵排不保留 120dp 文字列，也没有中间弹性空白。 */
            val detailsTop = contentTop + mediaSize + if (elements.body.visibility != GONE || elements.action.visibility != GONE) mediaGap else 0
            place(elements.body, paddingLeft, detailsTop)
            place(elements.action, paddingLeft + contentWidth - elements.action.measuredWidth, detailsTop + visibleHeight(elements.body))
            detailsTop + visibleHeight(elements.body) + visibleHeight(elements.action)
        }
        if (BuildConfig.DEBUG && previousArrangement != arrangement) {
            // 只在方向首次确定或改变时输出低敏尺寸档，不记录素材、URL 或广告 ID。
            Log.d("AdmobLayout", "媒体布局=$arrangement 宽度档=${contentWidth / dimension(R.dimen.ad_admob_gap)} 字体档=${(resources.configuration.fontScale * 10).toInt()}")
        }
        previousArrangement = arrangement
        return contentBottom
    }

    /** CTA 自然宽度有上限，短文案至少 128dp，长文案可完整换行。 */
    private fun measureAction(availableWidth: Int) {
        if (elements.action.visibility == GONE) return
        measureNatural(elements.action, availableWidth)
        measureNatural(elements.action, maxOf(actionMinWidth, elements.action.measuredWidth).coerceAtMost(availableWidth), exactWidth = true)
    }

    /** 文本自然测高；CTA 可按自然宽度或文字列宽度测量，不裁断完整文案。 */
    private fun measureNatural(child: View, width: Int, exactWidth: Boolean = false) {
        if (child.visibility == GONE) return
        child.measure(MeasureSpec.makeMeasureSpec(width.coerceAtLeast(0), if (exactWidth) MeasureSpec.EXACTLY else MeasureSpec.AT_MOST),
            MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED))
    }

    /** 媒体和关闭热区的尺寸必须确定，不随文字缩小。 */
    private fun measureExact(child: View, width: Int, height: Int) {
        child.measure(MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY))
    }

    /** 缓存本轮位置；隐藏资产不参与排布，也不保留旧高度。 */
    private fun place(child: View, left: Int, top: Int) {
        if (child.visibility != GONE) positions[child] = Rect(left, top, left + child.measuredWidth, top + child.measuredHeight)
    }

    /** 缺失素材的高度按零参与计算，防止上一轮测量值造成空行。 */
    private fun visibleHeight(child: View): Int = if (child.visibility == GONE) 0 else child.measuredHeight

    /** 所有布局尺寸从模块资源读取，避免不同广告位各自调数值。 */
    private fun dimension(id: Int): Int = resources.getDimensionPixelSize(id)
}
