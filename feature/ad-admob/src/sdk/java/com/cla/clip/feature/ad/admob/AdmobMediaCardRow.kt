package com.cla.clip.feature.ad.admob

import android.content.Context
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.Button
import android.widget.LinearLayout

/** A1 专用两栏测量容器：媒体顶边固定，CTA 可见底边对齐媒体，长内容同步增高且不裁切。
 * 工厂在挂载前放入媒体列和带弹性空白的详情列；这里只测量原 View，不持有请求、监听或素材状态。
 */
internal class AdmobMediaCardRow(
    /** 随原生卡片释放的页面上下文，所有测量在主线程执行。 */
    context: Context,
    /** SDK 媒体控件，仅调整测量高度，比例和播放仍由 SDK 管理。 */
    private val media: View,
    /** 媒体及局部 AdChoices 底部预留列，预留不计入 CTA 对齐基线。 */
    private val mediaColumn: View,
    /** 顶部标题/声明与底部 CTA 的竖列，内部弹性空白吸收多余高度。 */
    private val details: View,
    /** 已注册的原主题按钮，轮廓用于排除背景底部透明内距。 */
    private val action: Button,
    /** 120dp 换算后的像素下限，避免系统字体或窄屏使媒体缩小。 */
    private val minimumMediaHeight: Int,
) : LinearLayout(context) {
    init {
        orientation = HORIZONTAL
        gravity = Gravity.START or Gravity.TOP
        // CTA 的透明热区可进入原有底部内距，保持完整触摸范围及主题反馈。
        clipChildren = false
        clipToPadding = false
    }

    /** 每次先按自然内容测量，再统一两栏高度；直接更新尺寸字段，不触发曝光后异步布局循环。 */
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        // 先还原自然测量，字体/可用宽度变化时也能回收上一次较高的媒体尺寸。
        media.layoutParams.height = minimumMediaHeight
        details.layoutParams.height = WRAP_CONTENT
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)

        /** CTA 背景矩形相对按钮的像素范围，无 CTA 时不保留透明内距。 */
        val actionBounds = if (action.visibility != GONE) AdmobNativeAssets.actionBackgroundBounds(action) else null
        /** 仅补偿实测轮廓底部透明区域，无矩形时按按钮底边降级。 */
        val actionBottomInset = if (actionBounds != null) {
            (action.measuredHeight - actionBounds.bottom).coerceAtLeast(0)
        } else 0
        /** 媒体高度排除按钮透明底部，标题/CTA 超出最小值时完整扩展，不重叠或截断。 */
        val alignedMediaHeight = maxOf(minimumMediaHeight, details.measuredHeight - actionBottomInset)
        media.layoutParams.height = alignedMediaHeight
        details.layoutParams.height = alignedMediaHeight + actionBottomInset
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)

        /** 媒体列保留真实选项空间；按钮透明热区优先借用底部内距，不再凭空增大可见留白。 */
        val contentHeight = maxOf(mediaColumn.measuredHeight, details.measuredHeight - paddingBottom)
        setMeasuredDimension(measuredWidthAndState, resolveSizeAndState(
            contentHeight + paddingTop + paddingBottom,
            heightMeasureSpec,
            measuredState shl MEASURED_HEIGHT_STATE_SHIFT,
        ))
    }
}
