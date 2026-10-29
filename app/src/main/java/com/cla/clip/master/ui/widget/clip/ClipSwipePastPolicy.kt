package com.cla.clip.master.ui.widget.clip

import kotlin.math.max
import kotlin.math.min

/** 卡片右滑的纯展示阶段；只用于编译期 UI，不保存或进入外部协议。 */
internal enum class ClipSwipePastState {
    /** 尚未越过菜单或动作不可用，不显示第二段提示。 */
    Hidden,
    /** 正在继续右滑，当前松手不会执行第二段动作。 */
    Dragging,
    /** 当前距离已达标，正常松手可执行第二段动作。 */
    Ready,
}

/**
 * 以整卡 80% 和菜单后的保护距离共同确定阈值，供折叠、取消折叠和彻底删除复用。
 * 所有输入均为像素；未测量或几何无效返回 null，调用方不得用兜底宽度提交动作。
 * @param itemWidthPx 卡片真实测量宽度，必须有限且大于零。
 * @param menuWidthPx 完整操作菜单宽度，无菜单传零，不能占满整卡。
 * @param minimumExtraDistancePx 48dp 经当前密度转换的菜单后保护距离。
 */
internal fun calculateClipSwipePastThreshold(
    itemWidthPx: Float,
    menuWidthPx: Float,
    minimumExtraDistancePx: Float,
): Float? {
    if (!itemWidthPx.isFinite() || !menuWidthPx.isFinite() || !minimumExtraDistancePx.isFinite() ||
        itemWidthPx <= 0f || menuWidthPx < 0f || menuWidthPx >= itemWidthPx || minimumExtraDistancePx <= 0f
    ) return null

    // 窄屏最多保留一半剩余空间作为保护，始终留出达标后继续拖动的余量。
    val extraDistancePx = min(minimumExtraDistancePx, (itemWidthPx - menuWidthPx) / 2f)
    return max(itemWidthPx * 0.80f, menuWidthPx + extraDistancePx)
}

/**
 * 从当前偏移计算提示及松手资格；回拉立即取消资格，不保存历史峰值。
 * @param offsetPx 当前卡片向右偏移的像素距离，负数或非有限数值视为无效。
 * @param menuWidthPx 第二段提示的起点，无菜单传零。
 * @param triggerPx 纯阈值规则的结果；null 或不在菜单之后时禁用第二段。
 */
internal fun resolveClipSwipePastState(
    offsetPx: Float,
    menuWidthPx: Float,
    triggerPx: Float?,
): ClipSwipePastState {
    if (!offsetPx.isFinite() || offsetPx < 0f || !menuWidthPx.isFinite() || menuWidthPx < 0f ||
        triggerPx == null || !triggerPx.isFinite() || triggerPx <= menuWidthPx || offsetPx <= menuWidthPx
    ) return ClipSwipePastState.Hidden

    return if (offsetPx >= triggerPx) ClipSwipePastState.Ready else ClipSwipePastState.Dragging
}
