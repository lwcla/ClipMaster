package com.cla.clip.feature.torrent.search.localization

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.SemanticsNode
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

/** 在主线程读取目标真实窗口的底部安全范围；只用于测试定位/断言，不给生产页面增加边距。 */
internal fun torrentEnglishNativeSafeBounds(node: SemanticsNode): Rect {
    // owner 是目标 Compose 根 View，windowRoot 提供该窗口整体位置；Dialog 与页面分别计算。
    val owner = (node.root as? ViewRootForTest)?.view
        ?: throw AssertionError("Native viewport requires a real Android Compose root")
    val windowRoot = owner.rootView
    // insets 必须来自已挂载窗口，不能把尚未分发的空值当成零边界通过。
    val insets = ViewCompat.getRootWindowInsets(owner)
        ?: throw AssertionError("Native viewport has no attached WindowInsets")
    // protectedBottom 合并当前可见导航条与不可排除的系统手势区，单位为真实像素。
    val protectedBottom = maxOf(insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom,
        insets.getInsets(WindowInsetsCompat.Type.mandatorySystemGestures()).bottom)
    // ownerLocation/windowLocation 将窗口底部边界换算到 Text 的 Compose root 坐标系。
    val ownerLocation = IntArray(2)
    val windowLocation = IntArray(2)
    owner.getLocationOnScreen(ownerLocation)
    windowRoot.getLocationOnScreen(windowLocation)
    // bottom 对已由系统缩小内容区的窗口只取交集，避免重复扣除系统预留空间。
    val bottom = (windowLocation[1] + windowRoot.height - protectedBottom - ownerLocation[1])
        .toFloat().coerceIn(0f, owner.height.toFloat())
    return Rect(0f, 0f, owner.width.toFloat(), bottom)
}
