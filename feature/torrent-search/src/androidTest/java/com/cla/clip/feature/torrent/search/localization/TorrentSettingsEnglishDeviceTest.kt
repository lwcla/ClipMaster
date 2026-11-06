package com.cla.clip.feature.torrent.search.localization

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import com.cla.clip.feature.torrent.search.TorrentSearchFeatureEntryImpl
import com.cla.clip.feature.torrent.search.R
import com.cla.clip.feature.torrent.search.api.TorrentSearchFeatureEntry
import com.cla.clip.feature.torrent.search.cloudflare.CloudflareChallengeScreen
import com.cla.clip.feature.torrent.search.cloudflare.CloudflareChallengeTarget
import com.cla.clip.feature.torrent.search.cloudflare.CloudflareChallengeUnavailableScreen
import com.cla.clip.feature.torrent.search.ui.settings.TorrentSettingsError
import com.cla.clip.feature.torrent.search.ui.settings.TorrentSettingsUiState
import org.junit.Test
import org.junit.runner.RunWith

/** 设置、来源与挑战页面的英文覆盖；配置只是内存快照，网络页面只加载静态 data 内容。 */
@RunWith(AndroidJUnit4::class)
class TorrentSettingsEnglishDeviceTest : TorrentEnglishLayoutHarness() {
    /** 固定按 API 接口分派，防止直接调用实现掩盖跨模块 Compose ABI 不匹配。 */
    private val featureEntry: TorrentSearchFeatureEntry = TorrentSearchFeatureEntryImpl()

    /** 仅验证入口 API 向宿主 slot 提供完整文案；真实宿主卡片排版由 app 仪器覆盖。 */
    @Test fun hostMineEntry() = verify("torrent-host-mine-entry", labels(R.string.torrent_search_title,
        R.string.torrent_search_entry_description)) {
        featureEntry.MineEntry(onLaunch = {}, modifier = Modifier, entryCard = { title, description, _, modifier ->
            // title/description 是生产入口提供的资源；modifier 保持调用链，测试不复制宿主卡片。
            Column(modifier) { Text(title); Text(description) }
        })
    }

    /** 宿主快捷动作经真实接口进入实现，空点击不会启动业务 Activity。 */
    @Test fun hostDetailEntry() = verify("torrent-host-detail-entry", labels(R.string.torrent_search_title)) {
        Row(Modifier.fillMaxWidth()) {
            with(featureEntry) { DetailAction("Synthetic query", {}, Modifier) }
        }
    }
    /** 按分区滚动到披露说明，覆盖最长安全模式/DNS 说明。 */
    @Test fun settingsPendingDisclosure() = verify("torrent-settings-pending", labels(R.string.torrent_search_settings_screen_title,
        R.string.torrent_search_settings_search_providers, R.string.torrent_search_settings_safe_mode_summary,
        R.string.torrent_search_settings_section_sort_criteria, R.string.torrent_search_settings_section_sort_order,
        R.string.torrent_search_settings_dns_over_https, R.string.torrent_search_settings_dns_over_https_summary,
        R.string.torrent_search_settings_disclosure_title, R.string.torrent_search_settings_disclosure_pending)) {
        TorrentEnglishFixtures.Settings(TorrentSettingsUiState(settingsReady = true))
    }

    /** 已接受披露的 Reset 操作也必须保留完整文案。 */
    @Test fun settingsAcceptedDisclosure() = verify("torrent-settings-accepted", labels(R.string.torrent_search_settings_disclosure_title,
        R.string.torrent_search_settings_disclosure_accepted, R.string.torrent_search_settings_disclosure_reset)) {
        TorrentEnglishFixtures.Settings(TorrentSettingsUiState(settingsReady = true, multiSourceDisclosureAccepted = true))
    }

    /** 最长配置错误不随大字号被固定设置卡高度截断。 */
    @Test fun settingsFailure() = verify("torrent-settings-failure", labels(R.string.torrent_search_settings_error_invalid_config)) {
        TorrentEnglishFixtures.Settings(TorrentSettingsUiState(error = TorrentSettingsError.INVALID_CONFIG))
    }

    /** 长来源名称、过滤提示、受保护操作和最后一个可编辑来源都需可滚动到达。 */
    @Test fun providerManagement() = verify("torrent-providers", labels(R.string.torrent_search_search_providers_screen_title,
        R.string.torrent_search_search_providers_action_enable_all, R.string.torrent_search_search_providers_action_disable_all,
        R.string.torrent_search_search_providers_action_reset) + TorrentEnglishFixtures.PROVIDER +
        labels(R.string.torrent_search_settings_provider_filtered_by_safe_mode, R.string.torrent_search_cloudflare_open_action) +
        "Editable synthetic provider") {
        TorrentEnglishFixtures.Providers(TorrentSettingsUiState(settingsReady = true, providers = TorrentEnglishFixtures.providers()))
    }

    /** 真实挑战页只加载无外部资源的短 data 页面，不解析或连接任何真实 Provider。 */
    @Test fun cloudflareStaticPage() = verify("torrent-cloudflare-local", listOf(english(R.string.torrent_search_cloudflare_title_format,
        TorrentEnglishFixtures.PROVIDER))) {
        CloudflareChallengeScreen(CloudflareChallengeTarget(TorrentEnglishFixtures.PROVIDER,
            "data:text/html,<html><body><p>Synthetic%20offline%20challenge%20page</p></body></html>"), {})
    }

    /** 不可用页面是独立生产分支，标题和固定失败提示均需验证。 */
    @Test fun cloudflareUnavailable() = verify("torrent-cloudflare-unavailable", labels(R.string.torrent_search_cloudflare_title,
        R.string.torrent_search_cloudflare_unavailable)) { CloudflareChallengeUnavailableScreen({}) }
}
