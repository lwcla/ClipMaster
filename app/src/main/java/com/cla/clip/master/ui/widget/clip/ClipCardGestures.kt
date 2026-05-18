package com.cla.clip.master.ui.widget.clip

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.positionChange
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.abs
import kotlin.math.sign

/** 剪贴卡片按压反馈区域，用于区分斜向快捷动作区和详情区的轻量触摸反馈。 */
internal enum class ClipCardPressedZone {
    /** 用户按下位置落在左下斜向快捷动作区。 */
    QuickAction,

    /** 用户按下位置落在详情区；未启用快捷动作区时代表整卡详情按压。 */
    Detail
}

/** 剪贴卡片手势在触摸阈值或抬手后得到的分发结果。 */
private sealed interface ClipCardGestureDecision {
    /** 未超过触摸阈值并正常抬手，后续按抬手坐标分发点击。 */
    data class Tap(val position: Offset, val isQuickActionTap: Boolean) : ClipCardGestureDecision

    /** 到达长按时间且未超过触摸阈值，后续只触发长按并取消点击。 */
    data object LongPress : ClipCardGestureDecision

    /** 横向拖动符合 item 侧滑条件，后续进入右滑菜单或菜单收回流程。 */
    data class Drag(val pointerId: PointerId, val initialOverSlopX: Float) : ClipCardGestureDecision

    /** 手势被滚动、Pager、系统取消或其他消费打断，当前 item 不再分发点击。 */
    data object Cancel : ClipCardGestureDecision
}

/**
 * 判断触点是否落在左下斜向快捷动作区。
 *
 * 快捷区固定为 `(0,0) -> (0,height) -> (width/2,height)`，边界使用严格大于，
 * 让斜线附近默认进入详情，降低删除和折叠等高影响动作的误触风险。
 */
private fun isInQuickActionZone(
    x: Float,
    y: Float,
    width: Float,
    height: Float,
): Boolean {
    if (width <= 0f || height <= 0f) return false
    val halfWidth = width / 2f
    return x < halfWidth && y > (height / halfWidth) * x
}

/** 创建快捷动作区三角路径；背景、按压反馈和命中判断都使用这组尺寸规则。 */
internal fun quickActionZonePath(width: Float, height: Float): Path {
    return Path().apply {
        moveTo(0f, 0f)
        lineTo(0f, height)
        lineTo(width / 2f, height)
        close()
    }
}

/** 创建详情区路径；启用快捷动作区时排除左下三角，避免点击详情时左下角误亮。 */
internal fun detailZonePath(width: Float, height: Float): Path {
    return Path().apply {
        moveTo(0f, 0f)
        lineTo(width, 0f)
        lineTo(width, height)
        lineTo(width / 2f, height)
        close()
    }
}

/** 创建整卡路径；未启用快捷动作区时详情按压反馈覆盖完整卡片。 */
internal fun fullCardPath(width: Float, height: Float): Path {
    return Path().apply {
        addRect(Rect(0f, 0f, width, height))
    }
}

/**
 * 剪贴 item 的统一点击和右滑手势。
 *
 * 这里把 tap、长按、纵向滚动取消、首页 Pager 左滑和 item 右滑菜单放在同一个入口协调：
 * 未超过触摸阈值才分发点击或长按；横向右滑或菜单已展开时才消费拖动；其他移动交还父级滚动。
 *
 * @param isMenuOpened 当前 item 菜单是否已经露出，决定左滑是否由 item 优先消费。
 * @param isAnimating 偏移动画是否正在执行，动画中继续消费横向手势，避免父级 Pager 抢占。
 * @param isQuickActionEnabled 是否启用斜向快捷动作区，未启用时整卡点击进入详情。
 * @param onPressZoneChanged 按压反馈区域变化回调；所有取消路径都必须传 null 清理反馈。
 * @param onTap 未被拖动/长按取消时的点击回调，第二个参数表示是否命中快捷动作区。
 * @param onLongPress 长按回调，任意位置长按都交给页面层处理。
 * @param onDrag 接收本次横向拖动增量，正数表示右滑展开，负数表示左滑收回。
 * @param onDragEnd 用户正常松手后的吸附或折叠判断入口。
 * @param onDragCancel 手势被父级或系统取消时的兜底吸附入口。
 */
internal suspend fun PointerInputScope.detectClipCardGestures(
    isMenuOpened: () -> Boolean,
    isAnimating: () -> Boolean,
    isQuickActionEnabled: () -> Boolean,
    onPressZoneChanged: (ClipCardPressedZone?) -> Unit,
    onTap: (Offset, Boolean) -> Unit,
    onLongPress: () -> Unit,
    onDrag: (Float) -> Unit,
    onDragEnd: () -> Unit,
    onDragCancel: () -> Unit,
) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        val downInQuickActionZone = isQuickActionEnabled() &&
            isInQuickActionZone(
                x = down.position.x,
                y = down.position.y,
                width = size.width.toFloat(),
                height = size.height.toFloat()
            )
        onPressZoneChanged(
            if (downInQuickActionZone) {
                ClipCardPressedZone.QuickAction
            } else {
                ClipCardPressedZone.Detail
            }
        )

        val touchSlop = viewConfiguration.touchSlop
        val longPressTimeoutMillis = viewConfiguration.longPressTimeoutMillis
        val decision = withTimeoutOrNull(longPressTimeoutMillis) {
            while (true) {
                val event = awaitPointerEvent()
                val change = event.changes.firstOrNull { it.id == down.id }
                    ?: return@withTimeoutOrNull ClipCardGestureDecision.Cancel
                if (change.isConsumed) {
                    return@withTimeoutOrNull ClipCardGestureDecision.Cancel
                }
                if (!change.pressed) {
                    val isQuickActionTap = isQuickActionEnabled() &&
                        isInQuickActionZone(
                            x = change.position.x,
                            y = change.position.y,
                            width = size.width.toFloat(),
                            height = size.height.toFloat()
                        )
                    return@withTimeoutOrNull ClipCardGestureDecision.Tap(
                        position = change.position,
                        isQuickActionTap = isQuickActionTap
                    )
                }

                val totalDelta = change.position - down.position
                if (totalDelta.getDistance() > touchSlop) {
                    val horizontalPastSlop = abs(totalDelta.x) > touchSlop &&
                        abs(totalDelta.x) >= abs(totalDelta.y)
                    val shouldHandleSwipe = horizontalPastSlop &&
                        (isMenuOpened() || isAnimating() || totalDelta.x > 0f)
                    if (shouldHandleSwipe) {
                        val overSlopX = totalDelta.x - touchSlop * sign(totalDelta.x)
                        change.consume()
                        return@withTimeoutOrNull ClipCardGestureDecision.Drag(
                            pointerId = down.id,
                            initialOverSlopX = overSlopX
                        )
                    }

                    // 纵向滚动、关闭状态下的左滑或非 item 侧滑移动都取消点击，让父级列表/Pager 继续处理。
                    return@withTimeoutOrNull ClipCardGestureDecision.Cancel
                }
            }
        } ?: ClipCardGestureDecision.LongPress

        when (decision) {
            is ClipCardGestureDecision.Tap -> {
                onPressZoneChanged(null)
                onTap(decision.position, decision.isQuickActionTap)
            }

            ClipCardGestureDecision.LongPress -> {
                onPressZoneChanged(null)
                onLongPress()
                while (true) {
                    val event = awaitPointerEvent()
                    val change = event.changes.firstOrNull { it.id == down.id } ?: break
                    if (!change.pressed || change.isConsumed) break
                }
            }

            is ClipCardGestureDecision.Drag -> {
                onPressZoneChanged(null)
                onDrag(decision.initialOverSlopX)
                var finishedNormally = true
                while (true) {
                    val event = awaitPointerEvent()
                    val change = event.changes.firstOrNull { it.id == decision.pointerId }
                    if (change == null || change.isConsumed) {
                        finishedNormally = false
                        break
                    }
                    if (!change.pressed) {
                        break
                    }
                    onDrag(change.positionChange().x)
                    change.consume()
                }
                if (finishedNormally) {
                    onDragEnd()
                } else {
                    onDragCancel()
                }
            }

            ClipCardGestureDecision.Cancel -> {
                onPressZoneChanged(null)
            }
        }
    }
}
