package com.cla.clip.master.ui.widget.image

import android.graphics.Bitmap
import android.graphics.drawable.Animatable
import android.os.Debug
import androidx.activity.ComponentActivity
import androidx.compose.animation.core.snap
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.Lifecycle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import coil3.DrawableImage
import coil3.EventListener
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import com.cla.clip.master.ui.widget.media.snapshotMediaGridItems
import com.cla.clip.master.ui.widget.rememberAnimatedImageLoader
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import me.saket.telephoto.zoomable.ZoomableImageState
import me.saket.telephoto.zoomable.rememberZoomableImageState
import me.saket.telephoto.zoomable.rememberZoomableState
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/** 在授权设备用真实编码图片验证生产预览；不进入用户数据页面、不连接网络。 */
@RunWith(AndroidJUnit4::class)
class ImagePreviewDeviceTest {
    /** 独立空 Activity 支持真实 STOP/START，不启动主页面或数据库操作。 */
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    /** 同一用例持有的缩放状态，仅供读取可观察结果。 */
    private val states = mutableMapOf<String, ZoomableImageState>()
    /** 可见页加载资格用于验证宿主停止时资源退出。 */
    private val activePages = mutableMapOf<String, Boolean>()
    /** 仅保存小动图的可绘制对象，检查离屏时停止播放；不保留大图位图。 */
    private val animations = ConcurrentHashMap<String, Animatable>()
    /** 使用宿主 Compose 帧时钟执行测试中的缩放复位，避免普通 Main 协程缺少帧时钟。 */
    private lateinit var previewScope: CoroutineScope
    /** 本次合成素材目录，测试结束只删除此目录中的已知文件。 */
    private lateinit var fixtureDirectory: File
    /** 便于 ADB 拉取的测试证据目录，不覆盖用户媒体。 */
    private lateinit var evidenceDirectory: File

    /** 将测试 APK 内的合成素材复制到专用缓存，供 Coil/Telephoto 使用真实文件源。 */
    @Before fun prepareFixtures() {
        fixtureDirectory = File(compose.activity.cacheDir, "image-preview-qa-${System.nanoTime()}").apply { mkdirs() }
        evidenceDirectory = File(compose.activity.getExternalFilesDir(null), "image-preview-phone-qa").apply { mkdirs() }
        // 测试 APK 的资源来源独立于应用和用户相册。
        val assets = InstrumentationRegistry.getInstrumentation().context.assets
        for (name in listOf("wide.jpg", "large.jpg", "tall.jpg", "animated.gif", "animated.webp")) {
            assets.open("image-preview/$name").use { input ->
                File(fixtureDirectory, name).outputStream().use { input.copyTo(it) }
            }
        }
    }

    /** 只清理本用例创建的素材，保留截图与内存采样供人工复核。 */
    @After fun removeFixtures() {
        fixtureDirectory.listFiles()?.forEach { it.delete() }
        fixtureDirectory.delete()
    }

    /** 真实双指缩放后平移，追加图片保持缩放，缩回后能继续左右翻页。 */
    @Test fun pinchPanAppendAndPagingKeepCurrentImage() {
        // 五个稳定身份共用一张合成宽图，避免网络和数据库干扰手势验证。
        val images = mutableStateOf(listOf("a", "b", "c"))
        showPreview(images, "b") { "wide.jpg" }
        awaitImage("b")
        compose.onNodeWithText("2 / 3").assertIsDisplayed()
        compose.onNodeWithTag("image_preview_zoomable").performTouchInput {
            // 两指从中心分开，检查实际多指输入，而非调用 zoomTo 伪造缩放。
            down(0, center.copy(x = width * 0.4f))
            down(1, center.copy(x = width * 0.6f))
            moveTo(0, center.copy(x = width * 0.15f), delayMillis = 300)
            moveTo(1, center.copy(x = width * 0.85f), delayMillis = 300)
            up(0)
            up(1)
        }
        compose.waitUntil(5_000) { (states["b"]?.zoomableState?.zoomFraction ?: 0f) > 0f }
        compose.onNodeWithText("2 / 3").assertIsDisplayed()
        // 放大后的小幅横移应移动图片，不切换页码。
        val beforePan = compose.runOnIdle { states.getValue("b").zoomableState.contentTransformation.offset }
        compose.onNodeWithTag("image_preview_zoomable").performTouchInput {
            swipe(center, center.copy(x = center.x - width * 0.12f), durationMillis = 350)
        }
        compose.runOnIdle { assertNotEquals(beforePan, states.getValue("b").zoomableState.contentTransformation.offset) }
        // 当前比例在追加同一集合时必须保持不变。
        val beforeAppend = compose.runOnIdle { states.getValue("b").zoomableState.zoomFraction!! }
        compose.runOnIdle { images.value = images.value + listOf("d", "e") }
        compose.onNodeWithText("2 / 5").assertIsDisplayed()
        compose.runOnIdle { assertEquals(beforeAppend, states.getValue("b").zoomableState.zoomFraction!!, 0.001f) }
        screenshot("pinch-appended")
        resetZoom("b")
        compose.onNodeWithTag("image_preview_pager").performTouchInput { swipeLeft() }
        compose.onNodeWithText("3 / 5").assertIsDisplayed()
        compose.onNodeWithTag("image_preview_pager").performTouchInput { swipeRight() }
        compose.onNodeWithText("2 / 5").assertIsDisplayed()
    }

    /** 真实 8192 方图与 16000 像素长图按需显示；后台释放加载资格并能恢复原页。 */
    @Test fun largeAndTallImagesPanAndResumeAcrossActivityStop() {
        // 记录的是少量阶段 PSS，不宣称覆盖整个运行期的峰值。
        val samples = StringBuilder("stage,pss_kb\n")
        sampleMemory(samples, "before")
        // 两个编码文件走实际文件源和库分块路径，不在测试中创建原尺寸位图。
        val images = mutableStateOf(listOf("large.jpg", "tall.jpg"))
        showPreview(images, "large.jpg") { it }
        awaitImage("large.jpg")
        sampleMemory(samples, "large_loaded")
        screenshot("large")
        compose.onNodeWithTag("image_preview_pager").performTouchInput { swipeLeft() }
        awaitImage("tall.jpg")
        compose.onNodeWithText("2 / 2").assertIsDisplayed()
        // 长图纵向滑动应改变内容偏移，页码仍是第二张。
        val beforePan = compose.runOnIdle { states.getValue("tall.jpg").zoomableState.contentTransformation.offset }
        compose.onNodeWithTag("image_preview_zoomable").performTouchInput { swipeUp() }
        compose.runOnIdle { assertNotEquals(beforePan, states.getValue("tall.jpg").zoomableState.contentTransformation.offset) }
        screenshot("tall-panned")
        sampleMemory(samples, "tall_panned")
        compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        compose.waitUntil(5_000) { activePages.values.none { it } }
        sampleMemory(samples, "stopped")
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        awaitImage("tall.jpg")
        compose.onNodeWithText("2 / 2").assertIsDisplayed()
        // 连续往返检查释放与再次加载，避免只验一次理想路径。
        repeat(3) {
            compose.onNodeWithTag("image_preview_pager").performTouchInput { swipeRight() }
            awaitImage("large.jpg")
            compose.onNodeWithTag("image_preview_pager").performTouchInput { swipeLeft() }
            awaitImage("tall.jpg")
            sampleMemory(samples, "round_${it + 1}")
        }
        File(evidenceDirectory, "memory.csv").writeText(samples.toString())
    }

    /** GIF/WebP 均观察实际画面变化；支持放大，并在离屏和宿主后台时停止动画。 */
    @Test fun animatedGifAndWebpPlayZoomAndStopWhenHidden() {
        // 两种格式使用同样合成帧，行为差异来自实际平台解码路径。
        val images = mutableStateOf(listOf("animated.gif", "animated.webp"))
        showPreview(images, "animated.gif") { it }
        awaitImage("animated.gif")
        assertAnimationChanges()
        compose.onNodeWithTag("image_preview_zoomable").performTouchInput { doubleClick() }
        compose.waitUntil(5_000) { (states["animated.gif"]?.zoomableState?.zoomFraction ?: 0f) > 0f }
        screenshot("gif-zoomed")
        resetZoom("animated.gif")
        compose.onNodeWithTag("image_preview_pager").performTouchInput { swipeLeft() }
        awaitImage("animated.webp")
        assertAnimationChanges()
        compose.waitUntil(5_000) { animations["animated.gif"]?.isRunning == false }
        screenshot("webp")
        compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        compose.waitUntil(5_000) { activePages.values.none { it } && animations.values.none { it.isRunning } }
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        awaitImage("animated.webp")
        assertAnimationChanges()
    }

    /** 连接真实共享容器和图片内容，仅通过 slot 暴露测试状态，不复制生产布局。 */
    private fun showPreview(images: State<List<String>>, initial: String, fileName: (String) -> String) {
        compose.setContent {
            // 缩放动画即使使用 snap 也需要 Compose 帧时钟，不能用裸 Main dispatcher。
            val scope = rememberCoroutineScope()
            SideEffect { previewScope = scope }
            // 使用生产动图加载器，并添加仅用于确认播放释放的成功监听。
            val baseLoader = rememberAnimatedImageLoader(compose.activity)
            val loader = remember(baseLoader) {
                baseLoader.newBuilder().eventListenerFactory {
                    object : EventListener() {
                        /** 只记录合成文件名与小动图，不读取或打印真实请求参数。 */
                        override fun onSuccess(request: ImageRequest, result: SuccessResult) {
                            // 平台动图句柄用来核对离屏释放，不作为画面已播放的唯一依据。
                            val animation = (result.image as? DrawableImage)?.drawable as? Animatable
                            if (animation != null) animations[(request.data as File).name] = animation
                        }
                    }
                }.build()
            }
            // 测试专用加载器随宿主组合销毁，避免测试之间共享请求。
            DisposableEffect(loader) { onDispose { loader.shutdown() } }
            SharedImagePreviewDialog(
                items = snapshotMediaGridItems(images.value) { it }, initialKey = "media:$initial",
                onDismiss = {}, modifier = Modifier.testTag("phone_preview"),
                imageContent = { key, active ->
                    // 与生产一致按 Pager key 创建状态，测试只读取结果。
                    val state = rememberZoomableImageState(rememberZoomableState())
                    // 文件源来自测试专用缓存，不接触外部网络或相册。
                    val request = remember(key) {
                        ImageRequest.Builder(compose.activity).data(File(fixtureDirectory, fileName(key))).build()
                    }
                    SideEffect { states[key] = state; activePages[key] = active }
                    // Pager 释放整页时也移除加载资格记录。
                    DisposableEffect(key) { onDispose { activePages[key] = false } }
                    ImagePreviewContent(request, loader, active, state = state)
                },
            )
        }
    }

    /** 等待真实图片可显示且分块就绪，超时即失败，不以请求开始代替加载成功。 */
    private fun awaitImage(key: String) {
        compose.waitUntil(20_000) { states[key]?.isImageDisplayedInFullQuality == true }
        compose.waitForIdle()
    }

    /** 手势已经验证后恢复适配状态，避免后续翻页断言依赖特定最大缩放倍率。 */
    private fun resetZoom(key: String) {
        compose.runOnIdle { previewScope.launch { states.getValue(key).zoomableState.resetZoom(snap()) } }
        compose.waitUntil(5_000) { states[key]?.zoomableState?.zoomFraction == 0f }
    }

    /** 中央像素必须在一段有界时间内变化，证明编码动图在真实画面中播放。 */
    private fun assertAnimationChanges() {
        // 两帧色块覆盖图片中心，缩放和设备分辨率不影响判定点。
        val first = imageCenterPixel()
        // 交替帧时长 400ms；多次非整周期取样避免固定相位碰巧相同。
        repeat(12) {
            Thread.sleep(150)
            // 原生截图采样避免设备上的 Compose 节点截图重录制干扰显示列表。
            val next = imageCenterPixel()
            if (first != next) return
        }
        fail("动图实际画面在采样窗口内没有变化")
    }

    /** 读取合成图片中央像素，截图仅短暂存在并立即释放，不保存其他窗口内容。 */
    private fun imageCenterPixel(): Int {
        // 全屏 Dialog 的窗口坐标与屏幕一致，取真实图片节点的中心。
        val center = compose.onNodeWithTag("image_preview_zoomable").fetchSemanticsNode().boundsInWindow.center
        // 使用原生截图路径，不触发 Compose 节点的离屏重绘。
        val bitmap = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        return try { bitmap.getPixel(center.x.toInt(), center.y.toInt()) } finally { bitmap.recycle() }
    }

    /** 截取当前合成测试窗口；不打开通知栏或其他用户页面。 */
    private fun screenshot(stage: String) {
        // 定向诊断时允许 ADB 独立采集同一画面，默认不等待，不影响常规回归耗时。
        if (stage == "webp" && InstrumentationRegistry.getArguments().getString("qaPauseScreenshot") == "true") {
            Thread.sleep(15_000)
        }
        // 系统截图保留真实窗口的标题、计数和安全区，避免节点截图造成局部内容缺失。
        val physical = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        File(evidenceDirectory, "$stage-screen.png").outputStream().use { physical.compress(Bitmap.CompressFormat.PNG, 100, it) }
        physical.recycle()
        // 标题语义位置用于区分绘制问题与布局被移出屏幕，不包含用户内容。
        val titleBounds = compose.onNodeWithText("图片预览").fetchSemanticsNode().boundsInWindow
        File(evidenceDirectory, "$stage-bounds.txt").writeText(titleBounds.toString())
    }

    /** 记录当前进程 PSS 数值作为阶段证据，不能据此声称测得完整峰值或长期无泄漏。 */
    private fun sampleMemory(samples: StringBuilder, stage: String) {
        samples.append(stage).append(',').append(Debug.getPss()).append('\n')
    }
}
