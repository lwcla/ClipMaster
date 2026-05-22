package com.cla.clip.master.ui.page.mine

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.CloudSync
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.cla.clip.base.general.R
import com.cla.clip.base.general.config.ClipItemQuickAction
import com.cla.clip.master.ui.navigation.BackupRoute
import com.cla.clip.master.ui.navigation.DownloadHistoryRoute
import com.cla.clip.master.ui.navigation.FoldedClipsRoute
import com.cla.clip.master.ui.navigation.MagnetSearchRoute
import com.cla.clip.master.ui.navigation.RecycleBinRoute
import com.cla.clip.master.ui.navigation.Route
import com.cla.clip.master.ui.widget.ListEntryCard

/**
 * 回收站入口。
 *
 * 数量来自轻量 COUNT Flow，入口只负责展示统计和导航，不加载回收站分页列表，避免“我的”页承担重型数据读取。
 */
@Composable
internal fun RecycleBinEntry(
    recycleBinCount: Int,
    onNavigate: (route: Route) -> Unit,
) {
    ListEntryCard(
        icon = {
            Icon(
                imageVector = Icons.Default.DeleteOutline,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary
            )
        },
        title = stringResource(R.string.base_general_recycle_bin),
        description = stringResource(R.string.base_general_recycle_bin_entry_desc, recycleBinCount),
        onClick = { onNavigate(RecycleBinRoute) }
    )
}

/**
 * 普通剪贴 item 快捷动作设置入口。
 *
 * 该设置只影响普通列表和普通搜索结果；折叠列表、折叠搜索和回收站继续由各自页面保持整卡点击语义。
 */
@Composable
internal fun ClipItemActionSettingEntry(
    action: ClipItemQuickAction,
    onClick: () -> Unit,
) {
    ListEntryCard(
        icon = {
            Icon(
                imageVector = Icons.Default.Tune,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary
            )
        },
        title = stringResource(R.string.base_general_clip_item_quick_action_setting),
        description = stringResource(R.string.base_general_current_option, action.labelText()),
        onClick = onClick
    )
}

/**
 * 下载记录入口。
 *
 * 放在“我的”页顶部，作为已下载视频和图片的统一管理入口；这里只负责导航，不直接读取下载数据。
 */
@Composable
internal fun DownloadHistoryEntry(
    onNavigate: (route: Route) -> Unit,
) {
    ListEntryCard(
        icon = {
            Icon(
                imageVector = Icons.Default.Download,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary
            )
        },
        title = stringResource(R.string.base_general_download_history),
        description = stringResource(R.string.base_general_download_history_entry_desc),
        onClick = { onNavigate(DownloadHistoryRoute) }
    )
}

/**
 * 磁力搜索入口。
 *
 * 入口只负责导航，Academic Torrents 索引状态和分页搜索都在磁力搜索页按生命周期启动。
 */
@Composable
internal fun MagnetSearchEntry(
    onNavigate: (route: Route) -> Unit,
) {
    ListEntryCard(
        icon = {
            Icon(
                imageVector = Icons.Default.Search,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary
            )
        },
        title = stringResource(R.string.base_general_magnet_search),
        description = stringResource(R.string.base_general_magnet_search_entry_desc),
        onClick = { onNavigate(MagnetSearchRoute()) }
    )
}

/**
 * 备份与恢复入口。
 *
 * 放在“我的”页管理入口区域，入口只负责导航；具体文件选择、WebDAV 配置和恢复预检都由备份页承载。
 */
@Composable
internal fun BackupEntry(
    onNavigate: (route: Route) -> Unit,
) {
    ListEntryCard(
        icon = {
            Icon(
                imageVector = Icons.Default.CloudSync,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary
            )
        },
        title = stringResource(R.string.base_general_backup_restore),
        description = stringResource(R.string.base_general_backup_restore_entry_desc),
        onClick = { onNavigate(BackupRoute) }
    )
}

/**
 * 折叠数据入口。
 *
 * 数量来自 ViewModel 的轻量 COUNT Flow，入口只展示统计并负责导航，不为了计数加载折叠列表。
 */
@Composable
internal fun FoldedClipsEntry(
    foldedClipCount: Int,
    onNavigate: (route: Route) -> Unit,
) {
    ListEntryCard(
        icon = {
            Icon(
                imageVector = Icons.Default.Inventory2,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary
            )
        },
        title = stringResource(R.string.base_general_folded_clips),
        description = stringResource(R.string.base_general_folded_clips_entry_desc, foldedClipCount),
        onClick = { onNavigate(FoldedClipsRoute) }
    )
}

/** 将快捷动作映射为用户可见文案，所有文案都来自字符串资源。 */
@Composable
internal fun ClipItemQuickAction.labelText(): String {
    return when (this) {
        ClipItemQuickAction.Copy -> stringResource(R.string.base_general_copy)
        ClipItemQuickAction.Pin -> stringResource(R.string.base_general_pinned)
        ClipItemQuickAction.Delete -> stringResource(R.string.base_general_delete)
        ClipItemQuickAction.Fold -> stringResource(R.string.base_general_fold_clip)
        ClipItemQuickAction.None -> stringResource(R.string.base_general_no_quick_action)
    }
}
