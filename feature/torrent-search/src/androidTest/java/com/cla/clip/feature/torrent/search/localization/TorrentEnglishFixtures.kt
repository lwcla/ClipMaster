package com.cla.clip.feature.torrent.search.localization

import androidx.compose.runtime.Composable
import com.cla.clip.feature.torrent.search.data.model.BookmarkedTorrent
import com.cla.clip.feature.torrent.search.data.model.TorrentSearchHistory
import com.cla.clip.feature.torrent.search.data.model.ViewedTorrent
import com.cla.clip.feature.torrent.search.model.contract.Category
import com.cla.clip.feature.torrent.search.model.contract.ProviderSafetyLevel
import com.cla.clip.feature.torrent.search.model.contract.Torrent
import com.cla.clip.feature.torrent.search.model.contract.TorrentDetails
import com.cla.clip.feature.torrent.search.model.contract.TorrentFile
import com.cla.clip.feature.torrent.search.model.contract.TorrentTracker
import com.cla.clip.feature.torrent.search.provider.ProviderDescriptor
import com.cla.clip.feature.torrent.search.provider.ProviderId
import com.cla.clip.feature.torrent.search.torznab.TorznabConfig
import com.cla.clip.feature.torrent.search.torznab.TorznabConfigId
import com.cla.clip.feature.torrent.search.ui.browse.TorrentBrowseScreen
import com.cla.clip.feature.torrent.search.ui.browse.TorrentBrowseUiState
import com.cla.clip.feature.torrent.search.ui.details.TorrentDetailsScreen
import com.cla.clip.feature.torrent.search.ui.details.TorrentDetailsUiState
import com.cla.clip.feature.torrent.search.ui.local.BookmarksScreen
import com.cla.clip.feature.torrent.search.ui.local.BookmarksUiState
import com.cla.clip.feature.torrent.search.ui.local.SearchHistoryScreen
import com.cla.clip.feature.torrent.search.ui.local.SearchHistoryUiState
import com.cla.clip.feature.torrent.search.ui.local.ViewedTorrentsScreen
import com.cla.clip.feature.torrent.search.ui.local.ViewedUiState
import com.cla.clip.feature.torrent.search.ui.search.TorrentSearchScreen
import com.cla.clip.feature.torrent.search.ui.search.TorrentSearchUiState
import com.cla.clip.feature.torrent.search.ui.settings.TorrentProviderManagementScreen
import com.cla.clip.feature.torrent.search.ui.settings.TorrentProviderUiState
import com.cla.clip.feature.torrent.search.ui.settings.TorrentSettingsScreen
import com.cla.clip.feature.torrent.search.ui.settings.TorrentSettingsUiState

/** 仅用于排版的短合成 fixture；不连接 Provider、数据库、密钥仓库或真实用户文件。 */
internal object TorrentEnglishFixtures {
    /** 固定 40 位合成身份，只服务稳定列表 key。 */
    const val HASH = "0123456789012345678901234567890123456789"
    /** 足够触发结果摘要省略的合成标题；详情页必须完整换行。 */
    const val NAME = "A deliberately long English synthetic release title with additional accessibility information"
    /** 来源长名称用于验证开关、编辑和删除动作不能挤掉名称。 */
    const val PROVIDER = "Synthetic international distribution and documentation provider"
    /** 最后一个文件名作为列表滚动到底的可观察标记。 */
    const val LAST_FILE = "Final synthetic documentation file with a deliberately long English name.txt"
    /** 描述只包含合成英文段落，不能加载图片或外链。 */
    val DESCRIPTION = List(8) { "Synthetic description paragraph for larger text and narrow displays." }.joinToString("\n\n")

    /** 构造搜索/浏览共用结果；无磁力、图片、详情链接或请求。 */
    fun torrent(): Torrent = Torrent(HASH, NAME, "123.45 GiB", 123456u, 654321u, ProviderId("fixture"), PROVIDER)

    /** 构造包含可滚动文件列表的详情；下载地址仅用于显示按钮，不会被空回调执行。 */
    fun details(): TorrentDetails = TorrentDetails(
        infoHash = HASH, name = NAME, providerId = ProviderId("fixture"), providerName = PROVIDER,
        magnetUri = "", fileDownloadLink = "https://fixture.invalid/sample.torrent", size = "123.45 GiB",
        seeders = 123456u, peers = 654321u, category = Category.BOOKS,
        uploader = "Synthetic documentation publisher with a long English display name",
        uploadEpochMillis = 1_700_000_000_000L, lastCheckedEpochMillis = 1_700_000_001_000L,
        files = List(5) { index -> TorrentFile(if (index == 4) LAST_FILE else "Synthetic file $index.txt") },
        trackers = listOf(TorrentTracker("https://fixture.invalid/tracker/end")),
    )

    /** 合成书签不提供可访问的外部地址，页面只消费快照。 */
    fun bookmark(): BookmarkedTorrent = BookmarkedTorrent(HASH, NAME, ProviderId("fixture"), PROVIDER, "123 GiB", 123456u, 654321u)

    /** 固定时间避免使用真实历史；长文本用于回放按钮多行测量。 */
    fun history(): TorrentSearchHistory = TorrentSearchHistory(NAME, NAME.lowercase(), 1_700_000_000_000L)

    /** 已查看只保留合成身份和固定时间。 */
    fun viewed(): ViewedTorrent = ViewedTorrent(HASH, 1_700_000_000_000L)

    /** 编辑场景带旧密钥存在标记，但没有真实密钥或凭据。 */
    fun config(): TorznabConfig = TorznabConfig(TorznabConfigId("torznab_fixture"), PROVIDER,
        "http://fixture.invalid/feed", Category.BOOKS, ProviderSafetyLevel.SAFE, true)

    /** 三类来源分别覆盖普通、受保护和可编辑来源；全部地址为不可解析的保留测试域。 */
    fun providers(): List<TorrentProviderUiState> = listOf(
        TorrentProviderUiState(ProviderDescriptor(ProviderId("fixture"), PROVIDER, "https://fixture.invalid",
            safetyLevel = ProviderSafetyLevel.UNSAFE, enabledByDefault = false), true, false),
        TorrentProviderUiState(ProviderDescriptor(ProviderId("challenge"), "Synthetic protected provider", "https://fixture.invalid",
            safetyLevel = ProviderSafetyLevel.SAFE, enabledByDefault = false, isCloudflareProtected = true), true, true),
        TorrentProviderUiState(ProviderDescriptor(ProviderId("torznab_fixture"), "Editable synthetic provider", "https://fixture.invalid",
            safetyLevel = ProviderSafetyLevel.SAFE, enabledByDefault = false), true, true, config()),
    )

    /** 空业务回调挂载真实搜索页面；弹窗仍由生产 state 分支创建。 */
    @Composable fun Search(state: TorrentSearchUiState) = TorrentSearchScreen(state,
        {}, {}, { _, _ -> }, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {})

    /** 空回调挂载浏览，不启动自动加载 ViewModel。 */
    @Composable fun Browse(state: TorrentBrowseUiState) = TorrentBrowseScreen(state, {}, {}, {}, {}, {})

    /** 禁止图片 Composition，避免 fixture 在设备上发起网络请求。 */
    @Composable fun Details(state: TorrentDetailsUiState) = TorrentDetailsScreen(state, false, {}, {}, {}, {}, {}, {}, {})

    /** SAF launcher 只注册不点击；所有文档/数据库业务回调为空。 */
    @Composable fun Bookmarks(state: BookmarksUiState) = BookmarksScreen(state, {}, {}, {}, {}, {}, {}, {}, {})

    /** 回放与删除只测真实控件，永不提交搜索或写历史库。 */
    @Composable fun History(state: SearchHistoryUiState) = SearchHistoryScreen(state, {}, {}, {}, {}, {}, {})

    /** 已查看列表与清空确认共用同一个真实页面入口。 */
    @Composable fun Viewed(state: ViewedUiState) = ViewedTorrentsScreen(state, {}, {}, {}, {})

    /** 设置页内部安全模式确认保留真实点击行为，持久化事件为空。 */
    @Composable fun Settings(state: TorrentSettingsUiState) = TorrentSettingsScreen(state, {}, {}, { _, _ -> }, {}, {}, {}, {})

    /** 编辑弹窗使用稳定合成身份，保存与删除事件不会写入设置或密钥仓库。 */
    @Composable fun Providers(state: TorrentSettingsUiState) = TorrentProviderManagementScreen(state,
        { _, _ -> }, {}, {}, {}, { TorznabConfigId("torznab_new_fixture") }, { _, _, _, _, _, _, _ -> }, {}, {}, {}, {})
}
