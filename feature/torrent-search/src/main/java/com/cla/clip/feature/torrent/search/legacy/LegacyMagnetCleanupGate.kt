package com.cla.clip.feature.torrent.search.legacy

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.cla.clip.feature.torrent.search.R

/** 在旧数据检测完成前阻止创建新功能内容，并承载确认、重试与退出。 */
@Composable
internal fun LegacyMagnetCleanupGate(
    /** 当前 ViewModel 门禁状态。 */
    state: LegacyMagnetCleanupState,
    /** 用户确认永久删除固定旧数据的回调。 */
    onConfirmCleanup: () -> Unit,
    /** 检测或清理失败后的重试回调。 */
    onRetry: () -> Unit,
    /** 用户取消确认或选择退出时结束 Activity。 */
    onExit: () -> Unit,
    /** 门禁通过后才创建的 TorrentSearch 页面内容。 */
    content: @Composable () -> Unit,
) {
    when (state) {
        LegacyMagnetCleanupState.Ready -> content()
        LegacyMagnetCleanupState.Checking,
        LegacyMagnetCleanupState.Cleaning,
        -> LegacyMagnetCleanupProgress()
        is LegacyMagnetCleanupState.ConfirmationRequired -> LegacyMagnetCleanupConfirmation(
            snapshot = state.snapshot,
            onConfirmCleanup = onConfirmCleanup,
            onExit = onExit,
        )
        LegacyMagnetCleanupState.Failed -> LegacyMagnetCleanupFailure(onRetry = onRetry, onExit = onExit)
    }
}

/** 检测或清理期间展示不含路径的阻塞加载态。 */
@Composable
private fun LegacyMagnetCleanupProgress() {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator()
    }
}

/** 展示旧数据库与缓存数量，并要求用户确认后才继续。 */
@Composable
private fun LegacyMagnetCleanupConfirmation(
    /** 仅用于格式化两类文件数量的检测快照。 */
    snapshot: LegacyMagnetDataSnapshot,
    /** 永久删除确认回调。 */
    onConfirmCleanup: () -> Unit,
    /** 取消即退出，避免新旧数据同时存在。 */
    onExit: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onExit,
        title = { Text(stringResource(R.string.torrent_search_legacy_cleanup_title)) },
        text = {
            Text(
                stringResource(
                    R.string.torrent_search_legacy_cleanup_message,
                    snapshot.databaseFileCount,
                    snapshot.cacheFileCount,
                )
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirmCleanup) {
                Text(stringResource(R.string.torrent_search_legacy_cleanup_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onExit) {
                Text(stringResource(R.string.torrent_search_button_cancel))
            }
        },
    )
}

/** 检测或清理失败时只允许重试或退出。 */
@Composable
private fun LegacyMagnetCleanupFailure(
    /** 重新检测固定旧目标。 */
    onRetry: () -> Unit,
    /** 保留旧数据并退出 Activity。 */
    onExit: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onExit,
        title = { Text(stringResource(R.string.torrent_search_legacy_cleanup_failed_title)) },
        text = { Text(stringResource(R.string.torrent_search_legacy_cleanup_failed_message)) },
        confirmButton = {
            TextButton(onClick = onRetry) {
                Text(stringResource(R.string.torrent_search_button_retry))
            }
        },
        dismissButton = {
            TextButton(onClick = onExit) {
                Text(stringResource(R.string.torrent_search_button_cancel))
            }
        },
    )
}
