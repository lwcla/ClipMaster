package com.cla.clip.feature.torrent.search.localization

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.unit.toSize
import kotlin.math.abs

/** 仅供英文设备回归定位真实语义节点；每次滚动有上限且拒绝无进展，避免测试 API 无界循环。 */
internal class TorrentEnglishScrollController(
    /** compose 提供当前真实组合及主线程同步，不持有生产状态或业务依赖。 */
    private val compose: ComposeContentTestRule,
    /** mark 只输出步骤和数值，不把 matcher 中的文本写入诊断。 */
    private val mark: (String) -> Unit,
    /** nativeBounds 由真实窗口给出可显示范围，不能只信可能缺少 Insets 的 Compose 视口。 */
    private val nativeBounds: (SemanticsNode) -> Rect,
) {
    /** 先定位已组合节点，再逐个扫描真实滚动容器；目标不存在或无法完整定位时明确失败。 */
    fun reveal(matcher: SemanticsMatcher) {
        if (target(matcher) != null) {
            align(matcher)
            return
        }
        // ids 与逐个 id 固定当前容器身份，优先内层横向列表，防止外层先把它移出组合。
        val ids = containers().map { node -> node.id }.reversed()
        for (id in ids) {
            if (scan(id, matcher)) {
                align(matcher)
                return
            }
        }
        throw AssertionError("bounded scroll: target missing after scanning ${ids.size} containers")
    }

    /** 用真实节点坐标对齐最近可滚动祖先；1px 与字形/可见高度断言采用相同舍入容差。 */
    private fun align(matcher: SemanticsMatcher) {
        // attempt 防止焦点回滚、边界残差或生产滚动异常令仪器测试永不结束。
        repeat(24) { attempt ->
            // node 与 bounds 保留未裁切的实际布局范围，不能用部分可见范围计算待滚动距离。
            val node = target(matcher) ?: throw AssertionError("bounded scroll: target disappeared")
            val bounds = Rect(node.positionInRoot, node.size.toSize())
            // safeBounds 同时约束嵌套容器的有效底部，关键标签必须滚到系统导航区上方。
            val safeBounds = nativeBounds(node)
            // ancestor 由近到远处理嵌套容器；只有实际存在滚动范围的轴参与定位。
            for (ancestor in generateSequence(node.parent) { parent -> parent.parent }) {
                // action、horizontal、vertical 分别描述公开滚动动作及其真实位置范围。
                val action = ancestor.config.getOrNull(SemanticsActions.ScrollBy)?.action ?: continue
                val horizontal = ancestor.config.getOrNull(SemanticsProperties.HorizontalScrollAxisRange)
                val vertical = ancestor.config.getOrNull(SemanticsProperties.VerticalScrollAxisRange)
                // viewport 是受祖先裁剪后的有效视口，dx/dy 仅纠正仍超出边界的轴。
                val viewport = ancestor.boundsInRoot.intersect(safeBounds)
                val dx = if (horizontal != null && horizontal.maxValue() > 0f) {
                    delta(bounds.left, bounds.right, viewport.left, viewport.right) * if (horizontal.reverseScrolling) -1f else 1f
                } else 0f
                val dy = if (vertical != null && vertical.maxValue() > 0f) {
                    delta(bounds.top, bounds.bottom, viewport.top, viewport.bottom) * if (vertical.reverseScrolling) -1f else 1f
                } else 0f
                if (abs(dx) <= 1f && abs(dy) <= 1f) continue
                // before 记录可观察进展，动作返回 true 本身不能证明位置发生变化。
                val before = position(ancestor)
                mark("align step=$attempt container=${ancestor.id}")
                compose.runOnUiThread { action(dx, dy) }
                compose.waitForIdle()
                // afterNode 与 afterContainer 都重新抓取，避免持有布局前的语义快照。
                val afterNode = target(matcher)
                val afterContainer = containers().firstOrNull { candidate -> candidate.id == ancestor.id }
                val moved = afterNode != null && afterNode.positionInRoot != bounds.topLeft ||
                    (afterContainer != null && position(afterContainer) != before)
                if (!moved) throw AssertionError("bounded scroll: no progress while aligning container=${ancestor.id}")
                return@repeat
            }
            return
        }
        throw AssertionError("bounded scroll: alignment exceeded 24 steps")
    }

    /** 只扫描指定真实列表，至多 80 个重叠视口；到末尾正常返回 false，无进展则失败。 */
    private fun scan(id: Int, matcher: SemanticsMatcher): Boolean {
        // initial 可能已随外层惰性列表回收；回收不是定位成功，交给其他容器继续搜索。
        val initial = containers().firstOrNull { node -> node.id == id } ?: return false
        // reset 只使用一次公开索引动作，不调用内部存在 while 循环的 performScrollToNode。
        val reset = initial.config.getOrNull(SemanticsActions.ScrollToIndex)?.action
        if (reset != null) {
            mark("scan-reset container=$id")
            compose.runOnUiThread { reset(0) }
            compose.waitForIdle()
        }
        // step 限定最坏扫描工作量；80% 视口步长保证相邻截图边界的短标签不会被跨过。
        repeat(80) { step ->
            if (target(matcher) != null) return true
            // node、horizontal、vertical 是本步布局完成后的新快照；canHorizontal/canVertical 判断该轴尚有剩余范围。
            val node = containers().firstOrNull { candidate -> candidate.id == id } ?: return false
            val horizontal = node.config.getOrNull(SemanticsProperties.HorizontalScrollAxisRange)
            val vertical = node.config.getOrNull(SemanticsProperties.VerticalScrollAxisRange)
            val canHorizontal = horizontal != null && horizontal.value() < horizontal.maxValue() - 1f
            val canVertical = vertical != null && vertical.value() < vertical.maxValue() - 1f
            if (!canHorizontal && !canVertical) return false
            // viewport/dx/dy 使用原生系统栏之上的可见区域，不把手势条遮挡区计入本步扫描。
            val viewport = node.boundsInRoot.intersect(nativeBounds(node))
            val dx = if (canHorizontal) viewport.width * 0.8f * if (horizontal!!.reverseScrolling) -1f else 1f else 0f
            val dy = if (canVertical) viewport.height * 0.8f * if (vertical!!.reverseScrolling) -1f else 1f else 0f
            if (abs(dx) <= 1f && abs(dy) <= 1f) return false
            // before 与 action 用于验证本次真实 ScrollBy 是否前进。
            val before = position(node)
            val action = node.config.getOrNull(SemanticsActions.ScrollBy)?.action ?: return false
            mark("scan step=$step container=$id")
            compose.runOnUiThread { action(dx, dy) }
            compose.waitForIdle()
            // after 容器可能随外层变化消失，不能据此宣称目标已出现。
            val after = containers().firstOrNull { candidate -> candidate.id == id } ?: return false
            if (position(after) == before) throw AssertionError("bounded scroll: no progress while scanning container=$id")
        }
        throw AssertionError("bounded scroll: scanning container=$id exceeded 80 steps")
    }

    /** 只枚举当前前台弹窗或页面中的滚动节点，避免背景同名控件替代真实目标。 */
    private fun containers(): List<SemanticsNode> {
        // matcher 在真实 Dialog 打开时限定到它的子树；不跨窗口滚动背景页面。
        val matcher = if (compose.onAllNodes(isDialog()).fetchSemanticsNodes().isNotEmpty()) {
            hasScrollAction() and hasAnyAncestor(isDialog())
        } else hasScrollAction()
        return compose.onAllNodes(matcher, useUnmergedTree = true).fetchSemanticsNodes().filter(::isPlaced)
    }

    /** 只返回自身及全部布局祖先已放置的目标；惰性列表预组合节点存在不等于已经进入布局。 */
    private fun target(matcher: SemanticsMatcher): SemanticsNode? =
        compose.onAllNodes(matcher, useUnmergedTree = true).fetchSemanticsNodes().firstOrNull(::isPlaced)

    /** 与 Compose assertIsDisplayed 的放置前提保持一致，不能只检查叶子或语义祖先。 */
    private fun isPlaced(node: SemanticsNode): Boolean =
        generateSequence(node.layoutInfo) { layout -> layout.parentInfo }.all { layout -> layout.isPlaced }

    /** 记录滚动轴当前位置，不依赖逻辑布尔返回值来判断已消费的滚动距离。 */
    private fun position(node: SemanticsNode): Pair<Float?, Float?> =
        node.config.getOrNull(SemanticsProperties.HorizontalScrollAxisRange)?.value?.invoke() to
            node.config.getOrNull(SemanticsProperties.VerticalScrollAxisRange)?.value?.invoke()

    /** 超长段落只要求与视口相交，能够容纳的标签则滚到完整显示；容差与后续断言一致。 */
    private fun delta(start: Float, end: Float, viewportStart: Float, viewportEnd: Float): Float = when {
        start <= viewportStart && end >= viewportEnd -> 0f
        start < viewportStart - 1f -> start - viewportStart
        end > viewportEnd + 1f -> end - viewportEnd
        else -> 0f
    }
}
