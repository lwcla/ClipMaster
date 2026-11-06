package com.cla.clip.master.ui.widget.image

import android.graphics.Bitmap
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import coil3.ImageLoader
import coil3.request.ImageRequest
import coil3.request.ErrorResult
import coil3.intercept.Interceptor
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import com.cla.clip.master.ui.widget.SelectableImageTile
import java.util.concurrent.CopyOnWriteArrayList
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** 真实 Coil 与共享缩略图的集成回归，仅使用内存位图和无法解析的合成数据。 */
@RunWith(AndroidJUnit4::class)
class SelectableImageTileLoadTest {
    /** 独立 Compose 宿主，不创建页面 ViewModel 或数据库。 */
    @get:Rule val compose = createComposeRule()

    /** 保持同一候选身份替换模型，旧请求即使忽略取消晚到也不能回写新版本。 */
    @Test fun sameKeyReplacementRejectsLateOldRequest() {
        // 旧请求挂起门闩，确定替换发生在请求尚未完成时。
        val started = CompletableDeferred<Unit>()
        // 测试最后显式释放旧请求，避免留下不可取消的等待。
        val release = CompletableDeferred<Unit>()
        // 确认旧网络替身确实走到了返回终态的位置。
        val returned = CompletableDeferred<Unit>()
        // 记录调用方收到的版本与低敏事件，验证闭包没有被新模型换绑。
        val events = CopyOnWriteArrayList<Pair<String, ImageThumbnailLoadEvent>>()
        // 在同一组合位置替换图片模型，模拟同 key 地址升级。
        val replaced = mutableStateOf(false)
        try {
            compose.setContent {
                // 空宿主提供图片请求所需 Context，不访问业务页面。
                val context = LocalContext.current
                // 无网络加载器控制旧请求，其他请求继续走真实位图解码。
                val loader = remember {
                    ImageLoader.Builder(context).components {
                        add(Interceptor { chain ->
                            if (chain.request.data == "synthetic-pending") {
                                started.complete(Unit)
                                // 模拟底层任务在取消后仍完成，生产 painter 必须隔离结果。
                                withContext(NonCancellable) { release.await() }
                                returned.complete(Unit)
                                ErrorResult(image = null, request = chain.request, throwable = IllegalStateException("synthetic"))
                            } else chain.proceed()
                        })
                    }.build()
                }
                // 新版本为本地合成位图，不依赖网络或真实文件。
                val bitmap = remember { Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888) }
                // 版本从调用方快照捕获，不能让旧回调读取后来的版本值。
                val version = if (replaced.value) "new" else "old"
                // 只在替换时重建请求，勾选和其他重组不参与。
                val request = remember(version) {
                    ImageRequest.Builder(context).data(if (replaced.value) bitmap else "synthetic-pending").build()
                }
                // 测试离场释放专用加载器，不影响其他组件。
                DisposableEffect(loader) { onDispose { loader.shutdown() } }
                Box(Modifier.size(100.dp)) {
                    androidx.compose.runtime.key("same-candidate") {
                        SelectableImageTile(request, true, loader, {}, {},
                            onLoadEvent = { events.add(version to it) })
                    }
                }
            }
            compose.waitUntil(10_000) { started.isCompleted }
            compose.runOnIdle { replaced.value = true }
            compose.waitUntil(10_000) { events.any { it.first == "new" && it.second.phase == ImageThumbnailLoadPhase.Succeeded } }
            release.complete(Unit)
            compose.waitUntil(10_000) { returned.isCompleted }
            compose.waitForIdle()
            assertEquals(listOf("old", "new", "new"), events.map { it.first })
            assertFalse(events.any { it.second.phase == ImageThumbnailLoadPhase.Failed })
        } finally {
            release.complete(Unit)
        }
    }

    /** 模型从失败变为成功时，占位与上报事件一起恢复；选择重组不重复请求。 */
    @Test fun replacementSuccessClearsFailureAndSelectionDoesNotRestart() {
        // 线程安全事件集合只记录无敏感信息的阶段与尝试身份。
        val events = CopyOnWriteArrayList<ImageThumbnailLoadEvent>()
        // 切换请求数据，模拟同一候选从失败资源升级到有效资源。
        val valid = mutableStateOf(false)
        // 单独改变勾选用于验证重组不重新请求。
        val selected = mutableStateOf(true)
        compose.setContent {
            // 默认加载器没有网络组件，非法数据只在本地失败。
            val context = LocalContext.current
            // 专用无网络加载器，仅服务本用例。
            val loader = remember { ImageLoader.Builder(context).build() }
            // 内存合成图片不读取任何媒体文件。
            val bitmap = remember { Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888) }
            // 请求只随数据有效性变化，其他重组保持同一模型。
            val request = remember(valid.value) {
                ImageRequest.Builder(context).data(if (valid.value) bitmap else Any()).build()
            }
            // 图片组件离场时释放测试加载器，避免泄漏到其他测试。
            DisposableEffect(loader) { onDispose { loader.shutdown() } }
            MaterialTheme {
                Box(Modifier.size(100.dp)) {
                    SelectableImageTile(request, selected.value, loader, {}, {},
                        failureTitle = "合成失败", onLoadEvent = events::add)
                }
            }
        }
        compose.waitUntil(10_000) { events.any { it.phase == ImageThumbnailLoadPhase.Failed } }
        compose.onNodeWithText("合成失败").assertIsDisplayed()
        compose.runOnIdle { valid.value = true }
        compose.waitUntil(10_000) { events.any { it.phase == ImageThumbnailLoadPhase.Succeeded } }
        compose.onNodeWithText("合成失败").assertDoesNotExist()
        // 改变勾选触发重组，应保留同一请求与终态。
        val count = events.size
        compose.runOnIdle { selected.value = false }
        compose.waitForIdle()
        assertEquals(count, events.size)
        assertEquals(listOf(ImageThumbnailLoadPhase.Loading, ImageThumbnailLoadPhase.Failed,
            ImageThumbnailLoadPhase.Loading, ImageThumbnailLoadPhase.Succeeded), events.map { it.phase })
        assertNotSame(events[0].attempt, events[2].attempt)
    }
}
