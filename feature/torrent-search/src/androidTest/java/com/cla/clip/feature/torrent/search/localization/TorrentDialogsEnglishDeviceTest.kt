package com.cla.clip.feature.torrent.search.localization

import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cla.clip.feature.torrent.search.R
import com.cla.clip.feature.torrent.search.legacy.LegacyMagnetCleanupGate
import com.cla.clip.feature.torrent.search.legacy.LegacyMagnetCleanupState
import com.cla.clip.feature.torrent.search.legacy.LegacyMagnetDataSnapshot
import com.cla.clip.feature.torrent.search.ui.local.BookmarksUiState
import com.cla.clip.feature.torrent.search.ui.local.SearchHistoryUiState
import com.cla.clip.feature.torrent.search.ui.local.ViewedUiState
import com.cla.clip.feature.torrent.search.ui.search.TorrentSearchUiState
import com.cla.clip.feature.torrent.search.ui.settings.TorrentSettingsUiState
import org.junit.Test
import org.junit.runner.RunWith

/** 十个真实弹窗场景；使用生产页面状态或点击入口，不复制 AlertDialog。 */
@RunWith(AndroidJUnit4::class)
class TorrentDialogsEnglishDeviceTest : TorrentEnglishLayoutHarness() {
    /** 多源披露的长标题、正文和同意/取消都继承真实窗口字体。 */
    @Test fun multiSourceDisclosure() = verify("torrent-dialog-disclosure", labels(R.string.torrent_search_multi_source_disclosure_title) +
        english(R.string.torrent_search_multi_source_disclosure_message, 123) +
        labels(R.string.torrent_search_multi_source_disclosure_confirm, R.string.torrent_search_button_cancel)) {
        TorrentEnglishFixtures.Search(TorrentSearchUiState(showMultiSourceDisclosure = true, pendingDisclosureProviderCount = 123))
    }

    /** 搜索页由提升状态控制的安全模式确认。 */
    @Test fun searchSafeModeConfirmation() = verify("torrent-dialog-search-safe", labels(R.string.torrent_search_disable_safe_mode_title,
        R.string.torrent_search_disable_safe_mode_message, R.string.torrent_search_disable_safe_mode_confirm, R.string.torrent_search_button_cancel)) {
        TorrentEnglishFixtures.Search(TorrentSearchUiState(showDisableSafeModeConfirmation = true))
    }

    /** 设置页通过真实开关点击打开内部确认，避免遗漏第二个页面实现。 */
    @Test fun settingsSafeModeConfirmation() = verify("torrent-dialog-settings-safe", labels(R.string.torrent_search_disable_safe_mode_title,
        R.string.torrent_search_disable_safe_mode_message, R.string.torrent_search_disable_safe_mode_confirm, R.string.torrent_search_button_cancel),
        beforeCheck = { reveal(isToggleable()); compose.onNode(isToggleable()).performClick() }) {
        TorrentEnglishFixtures.Settings(TorrentSettingsUiState(settingsReady = true))
    }

    /** 书签清空对话框使用书签专属标题和动作。 */
    @Test fun bookmarksClearConfirmation() = verify("torrent-dialog-clear-bookmarks", labels(R.string.torrent_search_bookmarks_dialog_title_delete_all,
        R.string.torrent_search_bookmarks_dialog_message_delete_all, R.string.torrent_search_bookmarks_button_delete, R.string.torrent_search_button_cancel)) {
        TorrentEnglishFixtures.Bookmarks(BookmarksUiState(showClearConfirmation = true))
    }

    /** 历史清空与书签共用骨架，但文案需独立验证。 */
    @Test fun historyClearConfirmation() = verify("torrent-dialog-clear-history", labels(R.string.torrent_search_search_history_dialog_title_clear_history,
        R.string.torrent_search_search_history_dialog_message_clear_history, R.string.torrent_search_search_history_button_clear, R.string.torrent_search_button_cancel)) {
        TorrentEnglishFixtures.History(SearchHistoryUiState(showClearConfirmation = true))
    }

    /** 已查看清空的长标题完整可读。 */
    @Test fun viewedClearConfirmation() = verify("torrent-dialog-clear-viewed", labels(R.string.torrent_search_viewed_clear_title,
        R.string.torrent_search_viewed_clear_message, R.string.torrent_search_viewed_clear, R.string.torrent_search_button_cancel)) {
        TorrentEnglishFixtures.Viewed(ViewedUiState(showClearConfirmation = true))
    }

    /** 新增表单通过真实 Add 入口，滚动覆盖底部安全等级并保留 Save/Cancel。 */
    @Test fun torznabAdd() = verify("torrent-dialog-torznab-add", labels(R.string.torrent_search_search_providers_add_screen_title,
        R.string.torrent_search_search_providers_label_name, R.string.torrent_search_search_providers_label_url,
        R.string.torrent_search_search_providers_label_api_key, R.string.torrent_search_settings_torznab_category,
        R.string.torrent_search_settings_torznab_safety, R.string.torrent_search_search_providers_button_save, R.string.torrent_search_button_cancel),
        beforeCheck = { compose.onNodeWithContentDescription(english(R.string.torrent_search_search_providers_add_screen_title)).performClick() }) {
        TorrentEnglishFixtures.Providers(TorrentSettingsUiState(settingsReady = true))
    }

    /** 编辑表单包含 HTTP 警告及旧密钥提示，但不会读写真实凭据。 */
    @Test fun torznabEdit() = verify("torrent-dialog-torznab-edit", labels(R.string.torrent_search_search_providers_edit_screen_title,
        R.string.torrent_search_settings_http_warning, R.string.torrent_search_settings_api_key_preserved,
        R.string.torrent_search_settings_remove_api_key, R.string.torrent_search_settings_torznab_safety,
        R.string.torrent_search_search_providers_button_save, R.string.torrent_search_button_cancel), beforeCheck = {
        reveal(hasContentDescription(english(R.string.torrent_search_search_providers_list_action_edit)))
        compose.onNodeWithContentDescription(english(R.string.torrent_search_search_providers_list_action_edit)).performClick()
    }) {
        TorrentEnglishFixtures.Providers(TorrentSettingsUiState(settingsReady = true, providers = listOf(TorrentEnglishFixtures.providers().last())))
    }

    /** 清理门禁只注入数量，不构造或删除任何实际旧文件。 */
    @Test fun legacyCleanupConfirmation() = verify("torrent-dialog-legacy-confirm", labels(R.string.torrent_search_legacy_cleanup_title) +
        english(R.string.torrent_search_legacy_cleanup_message, 123, 456) +
        labels(R.string.torrent_search_legacy_cleanup_confirm, R.string.torrent_search_button_cancel)) {
        LegacyMagnetCleanupGate(LegacyMagnetCleanupState.ConfirmationRequired(LegacyMagnetDataSnapshot(123, 456, emptySet())), {}, {}, {}) {}
    }

    /** 失败门禁固定长说明与 Retry/Cancel 不因字体放大溢出窗口。 */
    @Test fun legacyCleanupFailure() = verify("torrent-dialog-legacy-failed", labels(R.string.torrent_search_legacy_cleanup_failed_title,
        R.string.torrent_search_legacy_cleanup_failed_message, R.string.torrent_search_button_retry, R.string.torrent_search_button_cancel)) {
        LegacyMagnetCleanupGate(LegacyMagnetCleanupState.Failed, {}, {}, {}) {}
    }
}
