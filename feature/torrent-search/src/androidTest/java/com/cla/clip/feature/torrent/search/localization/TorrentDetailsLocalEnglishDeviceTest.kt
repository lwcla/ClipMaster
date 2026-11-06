package com.cla.clip.feature.torrent.search.localization

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cla.clip.feature.torrent.search.R
import com.cla.clip.feature.torrent.search.data.importer.BookmarkPersistenceReport
import com.cla.clip.feature.torrent.search.download.TorrentDownloadFailureReason
import com.cla.clip.feature.torrent.search.ui.details.BookmarkPresencePhase
import com.cla.clip.feature.torrent.search.ui.details.SafeRichTextDocument
import com.cla.clip.feature.torrent.search.ui.details.TorrentDetailsPhase
import com.cla.clip.feature.torrent.search.ui.details.TorrentDetailsUiState
import com.cla.clip.feature.torrent.search.ui.details.TorrentDownloadPhase
import com.cla.clip.feature.torrent.search.ui.local.BookmarksUiState
import com.cla.clip.feature.torrent.search.ui.local.LocalDataFailure
import com.cla.clip.feature.torrent.search.ui.local.LocalDataHomeScreen
import com.cla.clip.feature.torrent.search.ui.local.SearchHistoryUiState
import com.cla.clip.feature.torrent.search.ui.local.ViewedUiState
import org.junit.Test
import org.junit.runner.RunWith

/** 详情和本地数据真实布局，所有输入均为内存 fixture，无文件或数据库操作。 */
@RunWith(AndroidJUnit4::class)
class TorrentDetailsLocalEnglishDeviceTest : TorrentEnglishLayoutHarness() {
    /** 加载态仍需完整显示标题与保存书签入口。 */
    @Test fun detailsLoading() = verify("torrent-details-loading", labels(R.string.torrent_search_torrent_details_screen_title,
        R.string.torrent_search_bookmark_save)) { TorrentEnglishFixtures.Details(TorrentDetailsUiState()) }

    /** 失败态的说明和重试操作允许动态增高。 */
    @Test fun detailsError() = verify("torrent-details-error", labels(R.string.torrent_search_torrent_details_state_unavailable_title,
        R.string.torrent_search_torrent_details_state_unavailable_description, R.string.torrent_search_button_try_again)) {
        TorrentEnglishFixtures.Details(TorrentDetailsUiState(phase = TorrentDetailsPhase.ERROR))
    }

    /** 完整详情必须滚动到最后文件和 tracker，三动作不得压缩或截断。 */
    @Test fun detailsLongContent() = verify("torrent-details-content", labels(R.string.torrent_search_bookmark_remove) +
        TorrentEnglishFixtures.NAME + labels(R.string.torrent_search_open_magnet_action, R.string.torrent_search_share_action,
            R.string.torrent_search_copy_action, R.string.torrent_search_download_torrent_action) +
        listOf(TorrentEnglishFixtures.DESCRIPTION, TorrentEnglishFixtures.LAST_FILE, "https://fixture.invalid/tracker/end")) {
        TorrentEnglishFixtures.Details(TorrentDetailsUiState(phase = TorrentDetailsPhase.CONTENT,
            details = TorrentEnglishFixtures.details(), isBookmarked = true, bookmarkPresencePhase = BookmarkPresencePhase.READY,
            description = SafeRichTextDocument(TorrentEnglishFixtures.DESCRIPTION, emptyList(), emptyList())))
    }

    /** 书签失败提示不能覆盖顶部标题或动作。 */
    @Test fun bookmarkFailure() = verify("torrent-details-bookmark-failure", labels(R.string.torrent_search_torrent_details_screen_title,
        R.string.torrent_search_bookmark_save, R.string.torrent_search_bookmark_error)) {
        TorrentEnglishFixtures.Details(TorrentDetailsUiState(phase = TorrentDetailsPhase.ERROR, bookmarkWriteFailed = true))
    }

    /** 保存目标等待阶段只渲染提示，不真正弹出系统文件选择器。 */
    @Test fun downloadWaiting() = verify("torrent-details-save-waiting", labels(R.string.torrent_search_download_choose_destination)) {
        TorrentEnglishFixtures.Details(TorrentDetailsUiState(phase = TorrentDetailsPhase.CONTENT,
            details = TorrentEnglishFixtures.details(), downloadPhase = TorrentDownloadPhase.AWAITING_DESTINATION))
    }

    /** 最长下载失败提示使用固定分类，不携带服务端错误正文。 */
    @Test fun downloadFailure() = verify("torrent-details-download-failure", labels(R.string.torrent_search_download_error_identity)) {
        TorrentEnglishFixtures.Details(TorrentDetailsUiState(phase = TorrentDetailsPhase.CONTENT,
            details = TorrentEnglishFixtures.details(), downloadPhase = TorrentDownloadPhase.ERROR,
            downloadFailureReason = TorrentDownloadFailureReason.IDENTITY_MISMATCH))
    }

    /** 三个本地数据入口都由真实页面绘制。 */
    @Test fun localHome() = verify("torrent-local-home", labels(R.string.torrent_search_local_data_title,
        R.string.torrent_search_bookmarks_screen_title, R.string.torrent_search_search_history_screen_title, R.string.torrent_search_viewed_title)) {
        LocalDataHomeScreen({}, {}, {}, {})
    }

    /** 顶部导入/导出是长英文操作，测试只测量，绝不点击 SAF。 */
    @Test fun bookmarksEmpty() = verify("torrent-bookmarks-empty", labels(R.string.torrent_search_bookmarks_screen_title,
        R.string.torrent_search_bookmarks_action_import, R.string.torrent_search_bookmarks_action_export, R.string.torrent_search_bookmarks_empty_message)) {
        TorrentEnglishFixtures.Bookmarks(BookmarksUiState(snapshotReady = true))
    }

    /** 导入报告大计数、失败说明与条目底部移除操作均可滚动到达。 */
    @Test fun bookmarksContent() = verify("torrent-bookmarks-content", listOf(english(R.string.torrent_search_bookmark_import_report,
        1234, 2345, 3456, 4567)) + labels(R.string.torrent_search_local_data_import_failed) + TorrentEnglishFixtures.NAME +
        labels(R.string.torrent_search_remove_bookmark), setOf(TorrentEnglishFixtures.NAME)) {
        TorrentEnglishFixtures.Bookmarks(BookmarksUiState(snapshotReady = true, bookmarks = listOf(TorrentEnglishFixtures.bookmark()),
            importReport = BookmarkPersistenceReport(1234, 2345, 3456, 4567), failure = LocalDataFailure.IMPORT_FAILED))
    }

    /** 长历史查询完整换行，右侧 Remove 仍可见。 */
    @Test fun historyContent() = verify("torrent-history-content", labels(R.string.torrent_search_search_history_screen_title) +
        TorrentEnglishFixtures.NAME + labels(R.string.torrent_search_remove_item)) {
        TorrentEnglishFixtures.History(SearchHistoryUiState(items = listOf(TorrentEnglishFixtures.history())))
    }

    /** 历史为空和写入失败可以同时展示，保证固定提示不遮盖标题。 */
    @Test fun historyEmptyFailure() = verify("torrent-history-empty", labels(R.string.torrent_search_search_history_empty_message,
        R.string.torrent_search_local_data_write_failed)) {
        TorrentEnglishFixtures.History(SearchHistoryUiState(failure = LocalDataFailure.WRITE_FAILED))
    }

    /** 已查看身份只显示合成 hash 缩略，不读取任何真实记录。 */
    @Test fun viewedContent() = verify("torrent-viewed-content", labels(R.string.torrent_search_viewed_title) +
        english(R.string.torrent_search_viewed_hash, "0123…6789")) {
        TorrentEnglishFixtures.Viewed(ViewedUiState(items = listOf(TorrentEnglishFixtures.viewed())))
    }

    /** 已查看空态和读取失败的文案均来自实际资源。 */
    @Test fun viewedEmptyFailure() = verify("torrent-viewed-empty", labels(R.string.torrent_search_viewed_empty,
        R.string.torrent_search_local_data_read_failed)) {
        TorrentEnglishFixtures.Viewed(ViewedUiState(failure = LocalDataFailure.READ_FAILED))
    }
}
