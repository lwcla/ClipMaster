package com.cla.clip.master.localization

import android.content.res.Configuration
import android.os.LocaleList
import androidx.activity.compose.setContent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.platform.LocalConfiguration
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextReplacement
import androidx.lifecycle.ViewModelProvider
import androidx.test.espresso.Espresso
import androidx.test.platform.app.InstrumentationRegistry
import com.cla.clip.base.general.dao.ClipData
import com.cla.clip.base.general.dao.DatabaseModule
import com.cla.clip.master.MainActivity
import com.cla.clip.master.R
import com.cla.clip.master.ui.navigation.MainInitialTab
import com.cla.clip.master.ui.navigation.SearchScope
import com.cla.clip.master.ui.page.backup.BackupFlowSession
import com.cla.clip.master.ui.page.backup.BackupPage
import com.cla.clip.master.ui.page.backup.BackupVm
import com.cla.clip.master.ui.page.backup.BackupRestoreFlowPage
import com.cla.clip.master.ui.page.detail.DetailPage
import com.cla.clip.master.ui.page.image.ImageExtractPage
import com.cla.clip.master.ui.page.image.ImageExtractVm
import com.cla.clip.master.ui.page.list.ClipListPage
import com.cla.clip.master.ui.page.list.FoldedClipListPage
import com.cla.clip.master.ui.page.main.MainPage
import com.cla.clip.master.ui.page.mine.MinePage
import com.cla.clip.master.ui.page.mine.MineVm
import com.cla.clip.master.ui.page.mine.ClipSourceBlockSettingPage
import com.cla.clip.master.ui.page.privacy.PrivacyPolicyPage
import com.cla.clip.master.ui.page.recycle.RecycleBinPage
import com.cla.clip.master.ui.page.search.SearchPage
import com.cla.clip.master.ui.page.video.VideoDownloadPage
import com.cla.clip.master.ui.page.video.VideoExtractPage
import kotlinx.coroutines.runBlocking
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

/** 用真实 Hilt 页面验证 Scaffold/VM/生命周期集成；仅在独立包和真实 App runner 下运行。 */
@RunWith(Parameterized::class)
class EnglishRealPageDeviceTest(
    /** 场景 ID 只用于选择生产页面，不注入业务规则或修改原应用。 */
    private val page: String,
) : EnglishScreenTestBase() {
    /** 完整页面使用真实 Activity，权限/首启接受均不点击，保持剪贴读取门禁关闭。 */
    @get:Rule override val compose = createAndroidComposeRule<MainActivity>()

    /** 无效会话没有可展示正文，用实际回退回调证明每个字号重建都经过导航门禁。 */
    private val invalidSessionFallbacks = AtomicInteger(0)

    /** 仅记录当前字号的真实 VM，等待失败时输出低敏状态名，不记录 URL 或候选数据。 */
    private var renderedImageModel: ImageExtractVm? = null

    /** 精确资源白名单只含可展开说明；按英文配置读取一次，不随每个文本节点重复构建 Context。 */
    private val mineCollapsibleDescriptions: Set<String> by lazy {
        // 原 Activity 可保留手机语言，白名单必须与英文测试子树使用相同语言资源。
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        // configuration 只用于测试资源读取，不修改系统设置或 Application 配置。
        val configuration = Configuration(context.resources.configuration).apply {
            setLocales(LocaleList.forLanguageTags("en-US"))
        }
        // resources 只读五种真实说明，禁止通过前缀放行其他标题、错误或重试文案。
        val resources = context.createConfigurationContext(configuration).resources
        setOf(
            R.string.host_shizuku_service_require,
            R.string.host_notification_permission_tip,
            R.string.host_notification_permission_denied_tip,
            R.string.host_notification_system_disabled_tip,
            R.string.host_link_preview_description,
        ).map(resources::getString).toSet()
    }

    /** 只有同文所有节点当前确实折叠时才允许摘要；已展开或缺少状态祖先均进入严格检查。 */
    private fun allowsCollapsedMineDescription(text: String): Boolean {
        if (page != "mine" && page != "main-mine") return false
        if (text !in mineCollapsibleDescriptions) return false
        // 同文节点只查询一次，不主动等待；避免重复查询令每帧文字检查成本扩大。
        val descriptions = compose.onAllNodes(hasText(text), useUnmergedTree = true).fetchSemanticsNodes()
        return descriptions.isNotEmpty() && descriptions.all { node ->
            generateSequence(node.parent) { ancestor -> ancestor.parent }
                .mapNotNull { ancestor -> ancestor.config.getOrNull(SemanticsProperties.StateDescription) }
                .firstOrNull() == "Collapsed"
        }
    }

    /** 点击卡片阅读区逐项展开后检查全文，右侧 Switch 的同名 contentDescription 不参与定位。 */
    private fun inspectExpandedMineDescriptions() {
        // titles 只定位三张生产阅读卡；通知的实际说明由系统状态决定，测试不改权限。
        val titles = listOf("Shizuku permission", "Notification permission", "Online previews")
        titles.forEachIndexed { index, title ->
            // Pager 同样提供 ScrollToIndex，必须明确限定竖向轴，再排除可能预取的离屏列表。
            val list = compose.onNode(
                hasScrollToNodeAction() and
                    SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange) and
                    SemanticsMatcher("Visible mine list") { node ->
                        node.boundsInRoot.width > 0f && node.boundsInRoot.height > 0f
                    },
            )
            list.performScrollToNode(hasText(title))
            // merged 阅读节点具有标题和点击动作；业务 Switch 只有 contentDescription，不能被此条件命中。
            val card = compose.onNode(hasText(title) and hasClickAction())
            if (card.fetchSemanticsNode().config.getOrNull(SemanticsProperties.StateDescription) != "Expanded") {
                card.performClick()
            }
            card.assertIsDisplayed().assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Expanded"))
            checkAndCapture("page-$page-expanded-$index")
            // 当前展开说明可能高于一屏；逐视口检查到列表末尾，并允许其他仍折叠卡片保留摘要。
            scrollAndInspect("page-$page-expanded-$index")
        }
    }

    companion object {
        /** 每个真实导航页面独立启动 Activity，避免状态/滚动/VM 互相污染。 */
        @JvmStatic @Parameterized.Parameters(name = "{0}")
        fun pages(): List<Array<String>> = listOf(
            "main-list", "main-mine", "clip-list", "folded-list", "search", "folded-search",
            "detail", "detail-missing", "image-extract", "video-extract", "video-download",
            "recycle", "backup", "restore-invalid-session", "mine", "source-filter", "privacy",
        ).map { arrayOf(it) }
    }

    /** 只向独立包写合成记录；正式包防护在打开数据库之前执行。 */
    @Before fun seedSyntheticClips() {
        // context 必须是任务创建的独立安装身份，不允许误用正式包测试数据库。
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        check(context.packageName == "com.cla.clip.master.localizationcheck")
        configureEnglishProcess(context)
        // database 是同一独立包的额外连接；页面 Activity store 下新 VM 会读取已写入样本。
        val database = DatabaseModule.provideAppDatabase(context)
        // now 仅防止合成回收站条目在测试中被自动保留清理，不参与布局断言。
        val now = System.currentTimeMillis()
        // content 为固定无隐私长文案与保留域名；网络预览默认关闭，不打开这些链接。
        val content = "Synthetic clipboard entry for English layout validation. " .repeat(12) +
            "\nhttps://example.invalid/first-long-synthetic-link\nhttps://example.invalid/second-long-synthetic-link"
        try {
            runBlocking {
                database.clipDao().upsertClipsForBackup(listOf(
                    ClipData(900001, content, now, link = null, sourceAppPackage = "synthetic.layout.example", searchText = content),
                    ClipData(900002, content + " folded", now, isFolded = true, foldedAt = now,
                        link = null, searchText = content),
                    ClipData(900003, content + " recycled", now, deletedAt = now, link = null, searchText = content),
                ))
            }
        } finally { database.close() }
    }

    /** 替换 Activity 的初始首页组合，保留真实 Hilt owner、Window、系统 Insets 与生命周期。 */
    override fun installEnglishContent(content: @Composable () -> Unit) {
        compose.activityRule.scenario.onActivity { activity ->
            configureEnglishProcess(activity)
            activity.setContent(content = content)
        }
    }

    /** 完整页面以真实默认状态和独立合成数据库渲染；长结果/弹窗状态由领域用例补齐。 */
    @Test fun realPageUsesReadableEnglishLayout() {
        verifyEnglish("page-$page", allowEllipsis = { text ->
            // 正文/URL/来源保留既有用户内容摘要；固定筛选入口与操作文字不得省略。
            text.startsWith("Synthetic clipboard entry") || text.startsWith("https://example.invalid/") ||
                text.startsWith("example.invalid/") ||
                text == "synthetic.layout.example" || text.startsWith("synthetic-no-installed") ||
                allowsCollapsedMineDescription(text)
        }, prepare = { rule ->
            // 异步数据库/设置必须进入真实可展示状态，不能把瞬时 Loading 当作页面通过。
            when (page) {
                "main-list", "clip-list", "folded-list", "recycle", "detail" -> rule.waitUntil(15_000) {
                    rule.onAllNodesWithText("Synthetic clipboard entry", substring = true).fetchSemanticsNodes().isNotEmpty()
                }
                "detail-missing" -> rule.waitUntil(15_000) {
                    rule.onAllNodesWithText("Clipboard entry not found").fetchSemanticsNodes().isNotEmpty()
                }
                "video-download" -> rule.waitUntil(15_000) {
                    rule.onAllNodesWithText("Download task not found").fetchSemanticsNodes().isNotEmpty()
                }
                "image-extract" -> try {
                    rule.waitUntil(15_000) {
                        rule.onAllNodesWithText("Image extraction failed. Tap to retry.").fetchSemanticsNodes().isNotEmpty()
                    }
                } catch (error: Exception) {
                    throw AssertionError("Offline image probe has no visible failure body; state=${compose.runOnIdle { renderedImageModel?.probeState }}", error)
                }
                "main-mine", "mine" -> {
                    // 页面默认 hiltViewModel 由同一 Activity owner 创建，此处只读取已启动的数据流。
                    val model = compose.runOnIdle { ViewModelProvider(compose.activity)[MineVm::class.java] }
                    rule.waitUntil(15_000) {
                        model.clipItemQuickAction.value != null && model.blockedClipSourcePackages.value != null && !model.settingsLoadFailed.value
                    }
                }
                "backup" -> {
                    // 只观察生产加载完成状态，不直接修改 VM 或开启任何备份操作。
                    val model = compose.runOnIdle { ViewModelProvider(compose.activity)[BackupVm::class.java] }
                    rule.waitUntil(15_000) { model.uiState.value.settingsLoaded && !model.uiState.value.settingsLoadFailed }
                }
                "search", "folded-search" -> {
                    rule.waitUntil(15_000) { rule.onAllNodesWithText("Search history").fetchSemanticsNodes().isNotEmpty() }
                    Espresso.closeSoftKeyboard()
                }
            }
            if (page == "source-filter") {
                // 只输入不可能匹配的合成名称，截图不展示手机上实际安装应用清单。
                rule.onNodeWithContentDescription("Search").performClick()
                rule.onNode(hasSetTextAction()).performTextReplacement("synthetic-no-installed-application-match")
                rule.waitUntil(15_000) { rule.onAllNodesWithText("No apps to display").fetchSemanticsNodes().isNotEmpty() }
                Espresso.closeSoftKeyboard()
            }
        }, exercise = {
            if (page == "mine" || page == "main-mine") inspectExpandedMineDescriptions()
            else scrollAndInspect("page-$page")
        }) {
            when (page) {
                "main-list" -> MainPage(initialTab = MainInitialTab.List, onNavigate = {}, torrentSearchFeatures = compose.activity.torrentSearchFeatures)
                "main-mine" -> MainPage(initialTab = MainInitialTab.Mine, onNavigate = {}, torrentSearchFeatures = compose.activity.torrentSearchFeatures)
                "clip-list" -> ClipListPage(onNavigate = {})
                "folded-list" -> FoldedClipListPage(onBack = {}, onNavigate = {})
                "search" -> SearchPage(onNavigate = {}, onBack = {})
                "folded-search" -> SearchPage(scope = SearchScope.FoldedOnly, onNavigate = {}, onBack = {})
                "detail" -> DetailPage(clipId = 900001, onBack = {}, onNavigate = {}, isMainProcess = false,
                    torrentSearchFeatures = compose.activity.torrentSearchFeatures)
                "detail-missing" -> DetailPage(clipId = -1, onBack = {}, onNavigate = {}, isMainProcess = false,
                    torrentSearchFeatures = compose.activity.torrentSearchFeatures)
                "image-extract" -> {
                    // 每档配置用独立会话，避免旧组合 onDispose 暂停同一个 VM 中新建立的探测轮次。
                    val model: ImageExtractVm = hiltViewModel(key = "english-image-${LocalConfiguration.current.fontScale}")
                    SideEffect { renderedImageModel = model }
                    ImageExtractPage(imageExtractVm = model, pageUrl = "about:blank", pageName = "Synthetic image source", onBack = {})
                }
                "video-extract" -> VideoExtractPage(pageUrl = "about:blank", pageName = "Synthetic video source", onBack = {}, onNavigate = {})
                "video-download" -> VideoDownloadPage(taskId = -1, onBack = {})
                "recycle" -> RecycleBinPage(onBack = {})
                "backup" -> BackupPage(flowSession = remember { BackupFlowSession() }, onBack = {}, onNavigate = {})
                "restore-invalid-session" -> BackupRestoreFlowPage(flowSession = remember { BackupFlowSession() },
                    onBack = {}, onInvalidSession = { invalidSessionFallbacks.incrementAndGet() }, onCloseRestoreFlow = {})
                "mine" -> MinePage(onNavigate = {}, torrentSearchFeatures = compose.activity.torrentSearchFeatures)
                "source-filter" -> ClipSourceBlockSettingPage(onBack = {})
                "privacy" -> PrivacyPolicyPage(onBack = {})
            }
        }
        if (page == "restore-invalid-session") check(invalidSessionFallbacks.get() >= 3)
    }
}
