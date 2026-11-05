package com.cla.clip.master.ui.widget.image

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.cla.clip.master.R
import com.cla.clip.base.general.utils.logD
import com.cla.clip.master.ui.widget.media.MediaGridItemSnapshot
import kotlinx.coroutines.flow.first

/**
 * 共享全屏浏览器，只编排图片身份、翻页和固定操作区，不依赖页面或数据层。
 * items 必须是同一帧的不可变快照；关闭后所有 Pager/图片局部状态随组合释放。
 */
@Composable
internal fun <T> SharedImagePreviewDialog(
    items: List<MediaGridItemSnapshot<T>>,
    initialKey: String,
    onDismiss: () -> Unit,
    imageContent: @Composable (T, Boolean) -> Unit,
    modifier: Modifier = Modifier,
    footer: @Composable (T) -> Unit = {},
) {
    // 初始目标仅在打开时解析；点击与渲染之间消失则安全关闭。
    val initialPosition = remember { resolveImagePreviewPosition(items.map { it.key }, initialKey, null) }
    if (initialPosition == null) {
        // 初始无效只发出关闭事件，不在组合计算阶段修改父状态。
        LaunchedEffect(Unit) { onDismiss() }
        return
    }
    // 渲染快照与原列表解耦；结构变更等待手势结束，追加与内容替换立即发布。
    var renderedItems by remember { mutableStateOf(items) }
    // 当前身份由落定页的布局 key 更新，避免下标在追加/重排后含义变化。
    var position by remember { mutableStateOf(initialPosition) }
    // 页数、key 与页内容共用同一快照，禁止延迟消费时再读取源列表。
    val pagerState = rememberPagerState(initialPage = initialPosition.index) { renderedItems.size }
    // 外部回调始终读取最新实现，避免旧闭包持有过期页面状态。
    val dismiss by rememberUpdatedState(onDismiss)
    // 宿主 STOP 时移除图片请求子组合，Dialog 自身的定位状态仍可保留。
    val lifecycleState by LocalLifecycleOwner.current.lifecycle.currentStateFlow.collectAsState()
    // 前台资格仅由生命周期决定，不能被当前页码替代。
    val foreground = lifecycleState.isAtLeast(Lifecycle.State.STARTED)
    // 低敏会话标识只关联本次 UI，不输出图片 key、URL 或请求对象。
    val traceId = remember { System.identityHashCode(pagerState) }

    // 只记录打开/关闭边界，不在每次翻页或追加时打印日志。
    DisposableEffect(Unit) {
        logD("ImagePreview") { "打开全屏预览 previewSessionId=$traceId count=${items.size}" }
        onDispose { logD("ImagePreview") { "关闭全屏预览 previewSessionId=$traceId" } }
    }
    // 追加保持 Pager 位置；重排/删除等结构变化以手势落定时的可见 key 为锚点。
    LaunchedEffect(items) {
        // 比较身份序列，元信息变化无需打断手势。
        val oldKeys = renderedItems.map { it.key }
        // 新序列决定是可立即发布的追加，还是需要落定校正的结构变化。
        val newKeys = items.map { it.key }
        if (newKeys.take(oldKeys.size) == oldKeys) {
            renderedItems = items
        } else {
            snapshotFlow { pagerState.isScrollInProgress }.first { !it }
            // 从布局获取真实落定身份，避免等待手势期间仍使用上一次页码。
            val visibleKey = pagerState.layoutInfo.visiblePagesInfo
                .firstOrNull { it.index == pagerState.currentPage }?.key as? String
            // 保留可见身份；当前图被删除时选择原位置附近的合法图片。
            val target = resolveImagePreviewPosition(newKeys, visibleKey ?: position.key, pagerState.currentPage)
            if (target == null) {
                dismiss()
                return@LaunchedEffect
            }
            renderedItems = items
            position = target
            pagerState.scrollToPage(target.index)
        }
    }
    // 只有落定时提交当前图片；读取布局 key 而不是使用旧集合的数字下标。
    LaunchedEffect(pagerState) {
        snapshotFlow {
            if (pagerState.isScrollInProgress) null else pagerState.layoutInfo.visiblePagesInfo
                .firstOrNull { it.index == pagerState.currentPage }?.key as? String
        }.collect { visibleKey ->
            // 过期布局 key 不允许恢复已经被移除的图片。
            val index = renderedItems.indexOfFirst { it.key == visibleKey }
            if (index >= 0) position = ImagePreviewPosition(renderedItems[index].key, index)
        }
    }
    // 底部图片与计数来自同一份定位结果，删除时由纯规则提供就近兜底。
    val current = resolveImagePreviewPosition(renderedItems.map { it.key }, position.key, position.index)
        ?: return

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        MaterialTheme(colorScheme = darkColorScheme()) {
            // MaterialTheme 不会自行提供文字前景色；黑色全屏背景必须明确白色内容色。
            CompositionLocalProvider(androidx.compose.material3.LocalContentColor provides Color.White) {
                Column(modifier.fillMaxSize().background(Color.Black).safeDrawingPadding()) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.image_preview_title), Modifier.weight(1f).padding(start = 16.dp))
                        TextButton(onClick = onDismiss) { Text(stringResource(R.string.image_preview_close)) }
                    }
                    HorizontalPager(
                        state = pagerState,
                        key = { renderedItems[it].key },
                        modifier = Modifier.weight(1f).fillMaxWidth().testTag("image_preview_pager"),
                    ) { page ->
                        // 按实际可见页授予加载资格，拖过半页时仍露出的旧图不能变成黑块。
                        val active = foreground && pagerState.layoutInfo.visiblePagesInfo.any { it.index == page }
                        imageContent(renderedItems[page].media, active)
                    }
                    // 计数固定在图片缩放区域外；长元信息使用独立受限高度区域。
                    Text(
                        stringResource(R.string.image_preview_position, current.index + 1, renderedItems.size),
                        modifier = Modifier.align(Alignment.CenterHorizontally).padding(8.dp),
                        color = Color.White,
                    )
                    footer(renderedItems[current.index].media)
                }
            }
        }
    }
}

/** 共享容器的静态样例，只展示排版和计数，不发起图片请求。 */
@androidx.compose.ui.tooling.preview.Preview
@Composable
private fun SharedImagePreviewDialogSample() {
    SharedImagePreviewDialog(
        items = listOf(MediaGridItemSnapshot("sample", Unit)),
        initialKey = "sample",
        onDismiss = {},
        imageContent = { _, _ ->
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(stringResource(R.string.image_preview_image))
            }
        },
    )
}
