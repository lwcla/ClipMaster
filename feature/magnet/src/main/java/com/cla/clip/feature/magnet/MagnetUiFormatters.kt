package com.cla.clip.feature.magnet

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.cla.clip.feature.magnet.R
import com.cla.clip.base.general.R as BaseR
import com.cla.clip.feature.magnet.MAGNET_SOURCE_ACADEMIC_TORRENTS
import com.cla.clip.feature.magnet.source.cache.MagnetSourceCacheState
import com.cla.clip.feature.magnet.source.cache.MagnetSourceStatusReason

/** 磁力来源 id 的展示文案；第一版只允许 Academic Torrents。 */
@Composable
internal fun magnetSourceName(sourceId: String): String {
    return when (sourceId) {
        MAGNET_SOURCE_ACADEMIC_TORRENTS -> stringResource(R.string.magnet_feature_magnet_source_academic_torrents)
        else -> sourceId
    }
}

/** 磁力条目大小展示，未知大小不伪造为 0。 */
@Composable
internal fun formatMagnetSize(bytes: Long?): String {
    val value = bytes?.takeIf { it >= 0L } ?: return stringResource(R.string.magnet_feature_magnet_unknown_size)
    val doubleValue = value.toDouble()
    return when {
        value < 1024L -> stringResource(BaseR.string.base_general_file_size_bytes, value)
        value < 1024L * 1024L -> stringResource(BaseR.string.base_general_file_size_kb, doubleValue / 1024.0)
        value < 1024L * 1024L * 1024L -> stringResource(BaseR.string.base_general_file_size_mb, doubleValue / 1024.0 / 1024.0)
        else -> stringResource(BaseR.string.base_general_file_size_gb, doubleValue / 1024.0 / 1024.0 / 1024.0)
    }
}

/** 缓存状态原因对应的短文案。 */
@Composable
internal fun MagnetSourceCacheState.statusText(): String {
    return when (reason) {
        MagnetSourceStatusReason.NotSynced -> stringResource(R.string.magnet_feature_magnet_source_not_synced)
        MagnetSourceStatusReason.Syncing -> stringResource(R.string.magnet_feature_magnet_source_syncing)
        MagnetSourceStatusReason.Ready -> stringResource(R.string.magnet_feature_magnet_source_ready, itemCount)
        MagnetSourceStatusReason.NotModified -> stringResource(R.string.magnet_feature_magnet_source_ready, itemCount)
        MagnetSourceStatusReason.Cooldown -> stringResource(R.string.magnet_feature_magnet_source_ready, itemCount)
        MagnetSourceStatusReason.EmptyIndex -> stringResource(R.string.magnet_feature_magnet_source_empty)
        MagnetSourceStatusReason.CacheCleared -> stringResource(R.string.magnet_feature_magnet_source_not_synced)
        MagnetSourceStatusReason.NetworkFailed -> stringResource(R.string.magnet_feature_magnet_source_network_failed)
        MagnetSourceStatusReason.NetworkTimeout -> stringResource(R.string.magnet_feature_magnet_source_network_failed)
        MagnetSourceStatusReason.ParseFailed -> stringResource(R.string.magnet_feature_magnet_source_parse_failed)
        MagnetSourceStatusReason.InsufficientSpace -> stringResource(R.string.magnet_feature_magnet_source_insufficient_space)
        MagnetSourceStatusReason.InvalidInfoHash -> stringResource(R.string.magnet_feature_magnet_source_parse_failed)
        MagnetSourceStatusReason.UserCancelled -> stringResource(R.string.magnet_feature_magnet_source_cancelled)
    }
}

/** 磁力动作结果对应的 Snackbar 文案。 */
internal val MagnetActionResult.messageRes: Int
    get() = when (this) {
        MagnetActionResult.CopiedAndOpened -> R.string.magnet_feature_magnet_copied_opening
        MagnetActionResult.CopiedOnly -> R.string.magnet_feature_magnet_copied
        MagnetActionResult.CopiedNoApp -> R.string.magnet_feature_magnet_copied_no_app
        MagnetActionResult.Invalid -> R.string.magnet_feature_magnet_invalid
    }
