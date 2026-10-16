package com.cla.clip.master.ui.page.download

import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.paging.PagingData
import androidx.paging.LoadState
import androidx.paging.LoadStates
import com.cla.clip.base.general.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** 直接验证生产 Pager 的可保存 key；空合成分页不读取用户数据，也不提供广告请求环境。 */
@RunWith(AndroidJUnit4::class)
class DownloadHistoryPagerStateTest {
    /** 使用既有测试宿主提供真实 Android 状态注册表，非法 Bundle key 会在首次组合失败。 */
    @get:Rule val compose = createComposeRule()

    /** 指向生产容器使用的分页状态，恢复后由新组合替换引用。 */
    private lateinit var pagerState: PagerState

    /** 页面组合持有的协程作用域，只执行测试需要的分类滚动，随组合销毁取消。 */
    private lateinit var pageScope: CoroutineScope

    /** 视频首次进入不崩溃，切换图片并恢复后仍显示图片，再切回视频。 */
    @Test fun videoAndImagePagesSurviveSwitchingAndStateRestore() {
        // 恢复测试器使用真实组件的保存结果，覆盖原先不可保存的分类单例 key。
        val restoration = StateRestorationTester(compose)
        showPager(restoration, initialPage = 0)
        assertEmptyPage(R.string.base_general_download_history_video_empty)
        switchPage(1)
        assertEmptyPage(R.string.base_general_download_history_image_empty)
        restoration.emulateSavedInstanceStateRestore()
        assertEmptyPage(R.string.base_general_download_history_image_empty)
        switchPage(0)
        assertEmptyPage(R.string.base_general_download_history_video_empty)
    }

    /** 图片也必须能独立作为首次页面，避免只修复 Video 分支而遗漏 Image。 */
    @Test fun imagePageCanBeOpenedDirectly() {
        // 同一生产容器从图片分类启动，不依赖视频页先创建状态。
        val restoration = StateRestorationTester(compose)
        showPager(restoration, initialPage = 1)
        assertEmptyPage(R.string.base_general_download_history_image_empty)
    }

    /** 用空的本地合成流挂载原 Pager，保持其 key、固定宿主与可见页分支都是真实实现。 */
    private fun showPager(restoration: StateRestorationTester, initialPage: Int) {
        // 两个分类配置与各自分页流是独立测试输入，不连接数据库、媒体读取或真实广告。
        val tabs = listOf(
            DownloadHistoryTabSpec(DownloadHistoryTab.Video, "视频"),
            DownloadHistoryTabSpec(DownloadHistoryTab.Image, "图片"),
        )
        // 静态空数据显式携带完成状态，避免 LazyPagingItems 沿用初始 Loading 而不进入空态分支。
        val loaded = LoadStates(
            refresh = LoadState.NotLoading(endOfPaginationReached = true),
            prepend = LoadState.NotLoading(endOfPaginationReached = true),
            append = LoadState.NotLoading(endOfPaginationReached = true),
        )
        // 两个可重复收集的流分别模拟已经完成的空数据库结果，不驱动任何真实加载。
        val videos = flowOf(PagingData.empty<DownloadHistoryVideoItem>(sourceLoadStates = loaded))
        val images = flowOf(PagingData.empty<DownloadHistoryImageBatch>(sourceLoadStates = loaded))
        restoration.setContent {
            MaterialTheme {
                pagerState = rememberPagerState(initialPage = initialPage) { tabs.size }
                pageScope = rememberCoroutineScope()
                DownloadHistoryPager(
                    tabs = tabs,
                    adOverlayBlocked = false,
                    adPointerPressed = false,
                    pagerState = pagerState,
                    state = DownloadHistoryUiState(selectedTab = tabs[pagerState.settledPage].tab),
                    videoPagingFlow = videos,
                    imagePagingFlow = images,
                    videoListState = rememberLazyListState(),
                    imageListState = rememberLazyListState(),
                    // 合成空页没有业务条目；禁止测试触发删除、播放、预览或重新下载副作用。
                    onToggleSelected = {},
                    onEnterSelection = {},
                    onOpenVideo = {},
                    onRetryVideo = {},
                    onRetryImage = {},
                    onPreviewImage = {},
                )
            }
        }
    }

    /** 通过实际 PagerState 切页，等待 Compose 空闲后再由可见内容判断是否成功。 */
    private fun switchPage(page: Int) {
        compose.runOnIdle { pageScope.launch { pagerState.scrollToPage(page) } }
    }

    /** 使用资源文案定位当前真实空态，防止仅检查页码而忽略业务分支未呈现。 */
    private fun assertEmptyPage(messageId: Int) {
        // 文案来自被测应用资源，避免设备语言影响断言；不读取用户内容。
        val message = InstrumentationRegistry.getInstrumentation().targetContext.getString(messageId)
        compose.onNodeWithText(message).assertIsDisplayed()
    }
}
