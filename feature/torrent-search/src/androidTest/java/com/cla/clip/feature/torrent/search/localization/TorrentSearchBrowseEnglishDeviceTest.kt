package com.cla.clip.feature.torrent.search.localization

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cla.clip.feature.torrent.search.R
import com.cla.clip.feature.torrent.search.ui.browse.BrowseMode
import com.cla.clip.feature.torrent.search.ui.browse.BrowsePhase
import com.cla.clip.feature.torrent.search.ui.browse.TorrentBrowseUiState
import com.cla.clip.feature.torrent.search.ui.search.TorrentSearchPhase
import com.cla.clip.feature.torrent.search.ui.search.TorrentSearchUiError
import com.cla.clip.feature.torrent.search.ui.search.TorrentSearchUiState
import org.junit.Test
import org.junit.runner.RunWith

/** 搜索/浏览生产页面测试；没有搜索 ViewModel 或网络客户端。 */
@RunWith(AndroidJUnit4::class)
class TorrentSearchBrowseEnglishDeviceTest : TorrentEnglishLayoutHarness() {
    /** 标题、顶部动作、来源数和安全开关不能在大字号中互相挤压。 */
    @Test fun searchIdle() = verify("torrent-search-idle", labels(R.string.torrent_search_title,
        R.string.torrent_search_browse_screen_title, R.string.torrent_search_local_data_action,
        R.string.torrent_search_home_button_search) + english(R.string.torrent_search_enabled_provider_count, 123) +
        labels(R.string.torrent_search_safe_mode, R.string.torrent_search_idle_prompt)) {
        TorrentEnglishFixtures.Search(TorrentSearchUiState(settingsReady = true, isScreenActive = true, enabledProviderCount = 123))
    }

    /** 运行中的较长 Stop search 也不能把来源数压成逐字竖排。 */
    @Test fun searchWorking() = verify("torrent-search-working", labels(R.string.torrent_search_title,
        R.string.torrent_search_search_action_stop_search, R.string.torrent_search_safe_mode) +
        english(R.string.torrent_search_enabled_provider_count, 123)) {
        TorrentEnglishFixtures.Search(TorrentSearchUiState(phase = TorrentSearchPhase.SEARCHING, enabledProviderCount = 123))
    }

    /** 两行标题/一行来源是明确的结果摘要契约，统计信息必须完整。 */
    @Test fun searchResults() = verify("torrent-search-results", listOf(TorrentEnglishFixtures.NAME,
        TorrentEnglishFixtures.PROVIDER, english(R.string.torrent_search_result_seeders, "123456"),
        english(R.string.torrent_search_result_peers, "654321"), "123.45 GiB"),
        setOf(TorrentEnglishFixtures.NAME, TorrentEnglishFixtures.PROVIDER)) {
        TorrentEnglishFixtures.Search(TorrentSearchUiState(phase = TorrentSearchPhase.COMPLETED,
            results = listOf(TorrentEnglishFixtures.torrent())))
    }

    /** 完成空态与停止态分别保留正确提示，不能被高控件区挡住。 */
    @Test fun searchEmpty() = verify("torrent-search-empty", labels(R.string.torrent_search_empty_results)) {
        TorrentEnglishFixtures.Search(TorrentSearchUiState(phase = TorrentSearchPhase.COMPLETED))
    }

    /** 搜索停止提示属于结果区内容，允许从上方控件滚动到达。 */
    @Test fun searchStopped() = verify("torrent-search-stopped", labels(R.string.torrent_search_stopped)) {
        TorrentEnglishFixtures.Search(TorrentSearchUiState(phase = TorrentSearchPhase.STOPPED))
    }

    /** 最长查询边界提示验证多行错误区域，不触发真正输入解析。 */
    @Test fun searchLongError() = verify("torrent-search-error", labels(R.string.torrent_search_error_query_too_long)) {
        TorrentEnglishFixtures.Search(TorrentSearchUiState(error = TorrentSearchUiError.QUERY_TOO_LONG))
    }

    /** 浏览加载时标题、模式和筛选仍然可读。 */
    @Test fun browseLoading() = verify("torrent-browse-loading", labels(R.string.torrent_search_browse_screen_title,
        R.string.torrent_search_browse_sort_latest, R.string.torrent_search_browse_sort_top)) {
        TorrentEnglishFixtures.Browse(TorrentBrowseUiState(phase = BrowsePhase.LOADING))
    }

    /** 无结果的长错误说明和重试按钮在 2 倍字也可滚动读取。 */
    @Test fun browseEmpty() = verify("torrent-browse-empty", labels(R.string.torrent_search_browse_state_unavailable_title,
        R.string.torrent_search_browse_state_unavailable_description, R.string.torrent_search_browse_button_try_again)) {
        TorrentEnglishFixtures.Browse(TorrentBrowseUiState(phase = BrowsePhase.COMPLETED))
    }

    /** Top 结果复用生产卡片并保留长统计值，不为测试复制布局。 */
    @Test fun browseResults() = verify("torrent-browse-results", listOf(english(R.string.torrent_search_browse_torrents_count_format, 1),
        TorrentEnglishFixtures.NAME, english(R.string.torrent_search_result_seeders, "123456"), "123.45 GiB"),
        setOf(TorrentEnglishFixtures.NAME)) {
        TorrentEnglishFixtures.Browse(TorrentBrowseUiState(mode = BrowseMode.TOP, phase = BrowsePhase.COMPLETED,
            results = listOf(TorrentEnglishFixtures.torrent())))
    }
}
