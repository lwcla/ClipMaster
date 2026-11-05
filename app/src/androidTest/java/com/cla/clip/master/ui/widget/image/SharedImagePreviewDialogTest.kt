package com.cla.clip.master.ui.widget.image

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.doubleClick
import coil3.request.ImageRequest
import coil3.ImageLoader
import coil3.EventListener
import coil3.size.Size
import coil3.size.Dimension
import java.util.concurrent.atomic.AtomicReference
import android.graphics.Bitmap
import com.cla.clip.master.ui.widget.rememberAnimatedImageLoader
import me.saket.telephoto.zoomable.ZoomableState
import me.saket.telephoto.zoomable.rememberZoomableImageState
import me.saket.telephoto.zoomable.rememberZoomableState
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cla.clip.master.ui.widget.media.snapshotMediaGridItems
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** 使用纯文字图片槽验证生产全屏容器，不联网、不读取媒体或数据库。 */
@RunWith(AndroidJUnit4::class)
class SharedImagePreviewDialogTest {
    /** 空 Compose 宿主承载 Dialog 和真实 Pager 手势。 */
    @get:Rule val compose = createComposeRule()

    /** 全屏请求必须按视口解码，不能让显式 ORIGINAL 导致先完整分配超大位图。 */
    @Test fun previewDecodesAtViewportSize() {
        // EventListener 观察真实 Coil 请求尺寸，避免断言私有请求构建细节。
        val resolvedSize = AtomicReference<Size?>()
        compose.setContent {
            // 使用空测试宿主构建真实 Coil 请求。
            val context = LocalContext.current
            // 小合成位图足以验证尺寸契约，不在测试中真的分配危险大图。
            val request = remember {
                ImageRequest.Builder(context)
                    .data(Bitmap.createBitmap(80, 60, Bitmap.Config.ARGB_8888))
                    .size(Size.ORIGINAL).build()
            }
            // 单独加载器仅收集请求尺寸，不启用网络日志。
            val loader = remember {
                ImageLoader.Builder(context).eventListenerFactory {
                    object : EventListener() {
                        /** 仅记录解析后的像素边界，不访问请求地址或业务内容。 */
                        override fun resolveSizeEnd(request: ImageRequest, size: Size) { resolvedSize.set(size) }
                    }
                }.build()
            }
            ImagePreviewContent(request, loader, true)
        }
        compose.waitUntil(15_000) { resolvedSize.get() != null }
        org.junit.Assert.assertTrue(resolvedSize.get()!!.width is Dimension.Pixels)
        org.junit.Assert.assertTrue(resolvedSize.get()!!.height is Dimension.Pixels)
    }

    /** 慢拖超过半页时，上一页仍部分可见，不能提前取消它的图片显示。 */
    @Test fun partiallyVisiblePagesStayActiveWhileDragging() {
        // 记录真实图片槽收到的加载资格，不 mock Pager 的位置计算。
        val activePages = mutableMapOf<String, Boolean>()
        compose.setContent {
            SharedImagePreviewDialog(
                items = snapshotMediaGridItems(listOf("a", "b")) { it },
                initialKey = "media:a",
                onDismiss = {},
                imageContent = { item, active ->
                    SideEffect { activePages[item] = active }
                    Box(Modifier.fillMaxSize()) { Text(item) }
                },
            )
        }
        compose.onNodeWithTag("image_preview_pager").performTouchInput {
            down(center.copy(x = width * 0.9f))
            moveTo(center.copy(x = width * 0.2f), delayMillis = 500)
        }
        compose.runOnIdle {
            org.junit.Assert.assertEquals(true, activePages["a"])
            org.junit.Assert.assertEquals(true, activePages["b"])
        }
        compose.onNodeWithTag("image_preview_pager").performTouchInput { up() }
    }

    /** 合成位图通过真实 Telephoto 加载，双击放大后暂时离屏不会丢失缩放状态。 */
    @Test fun telephotoDoubleClickZoomSurvivesTemporaryInactivity() {
        // 仅持有库状态用于断言可观察缩放行为，不 mock 手势引擎。
        lateinit var zoom: ZoomableState
        // 模拟前后台/离屏资格变化，保持同一图片组件身份。
        val active = mutableStateOf(true)
        compose.setContent {
            MaterialTheme {
                // 合成位图避免联网或读取用户文件，宽高比避免触发长图初始适配。
                val context = LocalContext.current
                // 合成位图始终保留在本次测试组合中，不读取用户数据。
                val bitmap = remember { Bitmap.createBitmap(800, 600, Bitmap.Config.ARGB_8888).apply { eraseColor(android.graphics.Color.BLUE) } }
                // 内存位图请求经过真实 Telephoto/Coil 接入层。
                val request = remember { ImageRequest.Builder(context).data(bitmap).build() }
                // 提升状态供测试断言缩放，不手动修改库内部数据。
                val state = rememberZoomableImageState(rememberZoomableState())
                zoom = state.zoomableState
                ImagePreviewContent(request, rememberAnimatedImageLoader(context), active.value, state = state)
            }
        }
        compose.waitUntil(15_000) { zoom.zoomFraction != null }
        compose.onNodeWithTag("image_preview_zoomable").performTouchInput { doubleClick() }
        compose.waitUntil(5_000) { (zoom.zoomFraction ?: 0f) > 0f }
        compose.runOnIdle { active.value = false }
        compose.runOnIdle { active.value = true }
        compose.waitForIdle()
        compose.runOnIdle { org.junit.Assert.assertTrue((zoom.zoomFraction ?: 0f) > 0f) }
    }

    /** 原列表实例追加后保持当前图片，翻页使用新增集合并可正常关闭。 */
    @Test fun opensClickedImageAndSynchronizesAppends() {
        // 状态列表模拟 WebView 持续发布的同一个候选集合。
        val images = mutableStateListOf("a", "b", "c")
        // 显示标志模拟页面点击与关闭，关闭后不重建网格数据。
        val showing = mutableStateOf(true)
        compose.setContent {
            MaterialTheme {
                if (showing.value) SharedImagePreviewDialog(
                    items = snapshotMediaGridItems(images) { it },
                    initialKey = "media:b",
                    onDismiss = { showing.value = false },
                    imageContent = { item, _ -> Box(Modifier.fillMaxSize()) { Text(item) } },
                )
            }
        }
        compose.onNodeWithText("2 / 3").assertIsDisplayed()
        compose.runOnIdle { images.addAll(listOf("d", "e")) }
        compose.onNodeWithText("2 / 5").assertIsDisplayed()
        compose.onNodeWithTag("image_preview_pager").performTouchInput { swipeLeft() }
        compose.onNodeWithText("3 / 5").assertIsDisplayed()
        compose.onNodeWithText("关闭预览").performClick()
        compose.runOnIdle { assertFalse(showing.value) }
    }

    /** 前方插入不能换图，删除当前项按原位置回退，真正空列表自动关闭。 */
    @Test fun reorderRemovalAndEmptyKeepValidPosition() {
        // 使用可替换快照模拟原子发布，而非向 UI 暴露 clear/addAll 中间状态。
        val images = mutableStateOf(listOf("a", "b", "c"))
        // 空集合关闭后，父页面不会重新创建 Dialog。
        val showing = mutableStateOf(true)
        compose.setContent {
            MaterialTheme {
                if (showing.value) SharedImagePreviewDialog(
                    items = snapshotMediaGridItems(images.value) { it },
                    initialKey = "media:b",
                    onDismiss = { showing.value = false },
                    imageContent = { item, _ -> Text(item) },
                )
            }
        }
        compose.runOnIdle { images.value = listOf("x", "a", "b", "c") }
        compose.onNodeWithText("3 / 4").assertIsDisplayed()
        compose.runOnIdle { images.value = listOf("x", "a", "c") }
        compose.onNodeWithText("3 / 3").assertIsDisplayed()
        compose.runOnIdle { images.value = emptyList() }
        compose.waitForIdle()
        compose.runOnIdle { assertFalse(showing.value) }
    }
}
