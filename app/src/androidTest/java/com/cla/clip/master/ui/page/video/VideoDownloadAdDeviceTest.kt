package com.cla.clip.master.ui.page.video

import android.graphics.Bitmap
import android.graphics.Rect
import android.view.View
import android.view.ViewGroup
import androidx.activity.compose.setContent
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.cla.clip.base.general.R
import com.cla.clip.base.general.dao.DownloadDao
import com.cla.clip.base.general.dao.DownloadTaskData
import com.cla.clip.base.general.repository.DownloadRepository
import com.cla.clip.master.MainActivity
import com.cla.clip.master.MainVm
import com.cla.clip.master.ad.AdHostProvider
import com.cla.clip.master.entity.VideoDownloadState
import com.cla.clip.master.ui.theme.ClipMasterTheme
import com.cla.clip.master.work.DownloadVideoWorker
import com.cla.clip.master.work.VideoDownloadWorkObserver
import java.io.File
import java.lang.reflect.Proxy
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** 显式启用的真机验收：合成任务驱动正式页面，真实 SDK 广告仍遵守手机当前设置。 */
@RunWith(AndroidJUnit4::class)
class VideoDownloadAdDeviceTest {
    /** 复用正式宿主的 Hilt 广告来源、隐私控制器与系统安全区，不复制广告实现。 */
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    /** 下载、合并、成功都必须贴住安全底边，关闭后将整块空间归还业务内容。 */
    @Test fun allDownloadStagesKeepLiveAdAtSafeBottom() {
        // 实时广告依赖网络和用户授权，默认自动化运行不得静默请求此验收。
        assumeTrue(InstrumentationRegistry.getArguments().getString("videoAdDeviceQa") == "true")
        // 负数身份与正式数据库自增任务隔离，每轮只清理自己创建的调度记录。
        val taskId = -System.currentTimeMillis()
        // 只在内存里生成任务，不读写下载表、真实媒体和凭证。
        val task = MutableStateFlow<DownloadTaskData?>(DownloadTaskData(
            id = taskId, videoUrl = "https://example.invalid/fixture.mp4",
            fileName = "广告底部验收（合成任务）", progress = 35,
        ))
        // 页面仅能订阅合成任务，意外触发重试等业务操作直接失败。
        val dao = Proxy.newProxyInstance(DownloadDao::class.java.classLoader,
            arrayOf(DownloadDao::class.java)) { _, method, _ ->
            when (method.name) {
                "observeTask" -> task
                else -> error("验收禁止业务读写：${method.name}")
            }
        } as DownloadDao
        // 原观察器要求存在活动任务；延迟七天的专用占位不会实际下载，finally 精确取消。
        val work = WorkManager.getInstance(compose.activity)
        // 即使测试进程被强杀，残留记录也只查不存在的负数任务并失败，不操作用户媒体。
        val pending = OneTimeWorkRequestBuilder<DownloadVideoWorker>()
            .setInputData(workDataOf(DownloadVideoWorker.KEY_TASK_ID to taskId))
            .setInitialDelay(7, TimeUnit.DAYS).build()
        // 显式持有测试 ViewModel 的清理边界，退出时取消其观察协程。
        val store = ViewModelStore()
        try {
            work.enqueueUniqueWork(DownloadVideoWorker.uniqueWorkName(taskId),
                ExistingWorkPolicy.KEEP, pending).result.get(10, TimeUnit.SECONDS)
            // 生产 VM 继续执行任务状态映射和生命周期订阅，测试不直接伪造 UI 分支。
            val vm = VideoDownloadVm(DownloadRepository(dao), compose.activity.applicationContext,
                VideoDownloadWorkObserver(compose.activity.applicationContext))
            store.put("video-ad-device-qa", vm)
            compose.runOnUiThread {
                // 读取现有广告设置，不修改开关、来源或授权。
                val main = ViewModelProvider(compose.activity)[MainVm::class.java]
                compose.activity.setContent {
                    ClipMasterTheme {
                        AdHostProvider(compose.activity.adSources, compose.activity.adSourceSelector,
                            compose.activity.adSessionFailureFuse, true, main.adSettings) {
                            VideoDownloadPage(vm, taskId, onBack = {})
                        }
                    }
                }
            }
            compose.waitUntil(10_000) { vm.downloadState.value is VideoDownloadState.Downloading }
            // 首次素材通过真实 SDK 加载；只有原生广告已挂载并可见才验收。
            val progressAd = awaitNativeAd()
            assertAtBottomAndCapture("downloading", progressAd,
                compose.activity.getString(R.string.base_general_download_progress, 35))

            // 百分比和下载阶段变化不应该重建同一进度广告。
            task.value = task.value!!.copy(status = DownloadTaskData.STATUS_MERGING, progress = 70)
            compose.waitUntil(10_000) { vm.downloadState.value is VideoDownloadState.Merging }
            assertSame("合并应复用进度广告", progressAd, awaitNativeAd())
            assertAtBottomAndCapture("merging", progressAd,
                compose.activity.getString(R.string.base_general_merge_progress, 70))

            // 成功使用原有结果广告位；保存路径为空，防止误触打开用户媒体。
            task.value = task.value!!.copy(status = DownloadTaskData.STATUS_SUCCESS, progress = 100)
            compose.waitUntil(10_000) { vm.downloadState.value is VideoDownloadState.Success }
            compose.waitUntil(10_000) { compose.runOnIdle { !progressAd.isAttachedToWindow } }
            // 结果广告须独立完成真实加载，不能沿用上一阶段截图当作成功证据。
            val successAd = awaitNativeAd()
            assertNotSame("成功应使用结果广告", progressAd, successAd)
            assertAtBottomAndCapture("success", successAd,
                compose.activity.getString(R.string.base_general_video_download_success))

            // 长标题和描述覆盖业务区域变化；是否存在滚动由真实布局决定并记录。
            task.value = task.value!!.copy(fileName = "合成超长视频标题".repeat(30),
                videoUrl = "https://example.invalid/" + "fixture-long-name-".repeat(40) + ".mp4")
            // 状态映射在 IO 协程进行，先确认元数据已传播，再由语义查询同步 Compose。
            compose.waitUntil(10_000) { vm.taskData.value?.fileName == task.value!!.fileName }
            compose.onNodeWithText(task.value!!.fileName).assertExists()
            // 使用公开滚动语义操作生产区域，不为测试添加生产标签。
            val scroll = compose.onNode(hasScrollAction())
            // 最大滚动距离为零时，仅记录手势后的固定位置，不冒充实际滚动验收。
            val range = scroll.fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange]
            // 手势前的广告位置作为比较基准。
            val before = nativeBounds(successAd)
            scroll.performTouchInput { swipeUp() }
            compose.waitForIdle()
            assertEquals("内容手势不能移动底部广告", before, nativeBounds(successAd))
            appendEvidence("content_scroll: max=${range.maxValue()}, value=${range.value()}")
            capture("long-content")

            // 只点击已识别的宿主关闭按钮，绝不触发广告 CTA 或 SDK 点击区域。
            compose.runOnIdle {
                // 通过项目专用控件类型定位，避免误点 SDK 内的其他按钮。
                val close = findView(successAd) { it.javaClass.simpleName == "AdmobDismissControl" }
                assertTrue("宿主关闭按钮必须可操作", close?.isEnabled == true)
                assertTrue("宿主关闭必须成功", close!!.performClick())
            }
            compose.waitUntil(10_000) { compose.runOnIdle { nativeAd() == null } }
            // 广告移除后滚动区扩展到同一安全底边，不能残留广告高度的空槽。
            val contentBottom = scroll.fetchSemanticsNode().boundsInWindow.bottom
            assertEquals("关闭后应归还底部空间", safeBottom().toFloat(), contentBottom, 2f)
            appendEvidence("closed: contentBottom=$contentBottom, safeBottom=${safeBottom()}")
            capture("closed")
        } finally {
            try {
                compose.runOnUiThread {
                    // 页面释放异常也必须清理 VM，避免其观察协程泄漏。
                    try {
                        compose.activity.setContent {}
                    } finally {
                        store.clear()
                    }
                }
            } finally {
                // 页面清理失败仍要取消自己的占位，绝不取消用户任务或全局调度。
                work.cancelWorkById(pending.id).result.get(10, TimeUnit.SECONDS)
            }
        }
    }

    /** 等待实际 SDK View 完成呈现；无填充或未授权明确失败，不换成占位广告蒙混通过。 */
    private fun awaitNativeAd(): View {
        compose.waitUntil(40_000) {
            compose.runOnIdle { nativeAd()?.let { it.isShown && it.height > 0 } == true }
        }
        return compose.runOnIdle { nativeAd()!! }
    }

    /** 核对当前阶段文本、原生素材边界及剩余内容区域后保存合成页面证据。 */
    private fun assertAtBottomAndCapture(stage: String, ad: View, text: String) {
        compose.onNodeWithText(text).assertExists()
        // 实际 View 边界必须完整可见，不能只把已裁切区域的底边当作成功。
        val bounds = nativeBounds(ad)
        // 独立读取可见裁切区域，与完整尺寸比较。
        val visible = compose.runOnIdle { Rect().also { assertTrue(ad.getGlobalVisibleRect(it)) } }
        assertEquals("广告不得被裁切", bounds, visible)
        assertEquals("$stage 广告必须贴住安全底边", safeBottom(), bounds.bottom)
        // 滚动区域必须在广告上方结束，避免内容与广告重叠。
        val content = compose.onNode(hasScrollAction()).fetchSemanticsNode().boundsInWindow
        assertTrue("广告不得覆盖内容", content.bottom <= bounds.top + 2)
        appendEvidence("$stage: ad=$bounds, safeBottom=${safeBottom()}, content=$content")
        capture(stage)
    }

    /** 根据当前设备 Insets 计算安全底边，不写死三星的物理尺寸或导航模式。 */
    private fun safeBottom(): Int = compose.runOnIdle {
        // 根窗口位置与广告坐标均使用屏幕坐标。
        val root = compose.activity.window.decorView
        // 系统实时安全区，不受页面内部 Insets 消费影响。
        val insets = ViewCompat.getRootWindowInsets(root)!!
        // 导航栏和必需手势区取较大值，不能重复累加。
        val bottom = maxOf(insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom,
            insets.getInsets(WindowInsetsCompat.Type.mandatorySystemGestures()).bottom)
        // 记录完整窗口在屏幕中的原点。
        val location = IntArray(2)
        root.getLocationOnScreen(location)
        location[1] + root.height - bottom
    }

    /** 在 UI 线程读取完整素材尺寸；返回独立矩形快照便于跨阶段比较。 */
    private fun nativeBounds(view: View): Rect = compose.runOnIdle {
        // 原生坐标不依赖 Compose 内部测量实现。
        val location = IntArray(2)
        view.getLocationOnScreen(location)
        Rect(location[0], location[1], location[0] + view.width, location[1] + view.height)
    }

    /** 只查真实 SDK 的原生根节点，不把普通卡片或加载占位视作广告。 */
    private fun nativeAd(): View? = findView(compose.activity.window.decorView) {
        it.javaClass.name == "com.google.android.libraries.ads.mobile.sdk.nativead.NativeAdView"
    }

    /** 主线程递归遍历即时 View 树；查不到返回空，无业务数据访问。 */
    private fun findView(view: View, matches: (View) -> Boolean): View? {
        if (matches(view)) return view
        if (view is ViewGroup) {
            // 下标仅遍历原生子节点，不参与任务或广告身份。
            for (index in 0 until view.childCount) {
                // 首个匹配即返回，页面契约只有一个固定广告。
                val found = findView(view.getChildAt(index), matches)
                if (found != null) return found
            }
        }
        return null
    }

    /** 证据只写专用测试目录，不输出任务实体、URL、用户文件或凭证。 */
    private fun appendEvidence(line: String) {
        // 专用输出文件供本地 ADB 拉取，内容只含几何测量。
        val target = File(compose.activity.getExternalFilesDir(null), "video-download-ad-qa/layout.txt")
        target.parentFile!!.mkdirs()
        target.appendText(line + "\n")
    }

    /** 只有正式下载页和内存合成内容已显示才截图，不导出其他页面。 */
    private fun capture(stage: String) {
        compose.onNodeWithText(compose.activity.getString(R.string.base_general_video_download)).assertExists()
        // 保存本测试的明确阶段，不使用任意文件名或全局截图目录。
        val target = File(compose.activity.getExternalFilesDir(null), "video-download-ad-qa/$stage.png")
        target.parentFile!!.mkdirs()
        // 系统截图保留手势区，方便人工复核安全底边。
        val bitmap = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        target.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }
}
