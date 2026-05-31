package com.cla.clip.master.ui.page.mine

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.cla.clip.base.general.R
import com.cla.clip.master.update.AppUpdateFailureReason
import com.cla.clip.master.update.AppUpdateInfo
import com.cla.clip.master.update.AppUpdateLink

/**
 * 更新检查结果弹窗。
 *
 * 根据不同结果展示新版本详情、失败原因或“已是最新”的提示，
 * 所有外部跳转动作都交给页面层处理。
 */
@Composable
internal fun AppUpdateDialog(
    /** 当前要展示的更新结果状态。 */
    state: AppUpdateDialogState,

    /** 用户点击下载源或发布页时的外部跳转回调。 */
    onOpenLink: (AppUpdateLink) -> Unit,

    /** 关闭弹窗回调。 */
    onDismiss: () -> Unit,

    /** 调用方传入的通用修饰符。 */
    modifier: Modifier = Modifier,
) {
    AlertDialog(
        modifier = modifier,
        onDismissRequest = onDismiss,
        title = { Text(text = state.dialogTitle()) },
        text = {
            when (state) {
                is AppUpdateDialogState.UpdateAvailable -> UpdateAvailableContent(
                    info = state.info,
                    onOpenLink = onOpenLink,
                )

                is AppUpdateDialogState.UpToDate -> UpToDateContent(state)
                is AppUpdateDialogState.CheckUnavailable -> CheckUnavailableContent(
                    state = state,
                    onOpenLink = onOpenLink,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(text = stringResource(R.string.base_general_sure))
            }
        }
    )
}

/** 根据结果状态返回对话框标题。 */
@Composable
private fun AppUpdateDialogState.dialogTitle(): String {
    return when (this) {
        is AppUpdateDialogState.UpdateAvailable -> stringResource(R.string.base_general_app_update_available_title)
        is AppUpdateDialogState.UpToDate -> stringResource(R.string.base_general_app_update_up_to_date_title)
        is AppUpdateDialogState.CheckUnavailable -> stringResource(R.string.base_general_app_update_unavailable_title)
    }
}

/** 展示发现新版本时的版本信息、更新说明和下载入口。 */
@Composable
private fun UpdateAvailableContent(
    /** 远端版本信息。 */
    info: AppUpdateInfo,

    /** 打开下载源或发布页的回调。 */
    onOpenLink: (AppUpdateLink) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = stringResource(R.string.base_general_app_update_new_version, info.versionName, info.versionCode),
            style = MaterialTheme.typography.bodyLarge,
        )
        if (info.forceUpdate) {
            Text(
                text = stringResource(R.string.base_general_app_update_force_tip),
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        info.publishedAt?.let { publishedAt ->
            AppUpdateMetaText(text = stringResource(R.string.base_general_app_update_published_at, publishedAt))
        }
        info.sha256?.let { sha256 ->
            AppUpdateMetaText(text = stringResource(R.string.base_general_app_update_sha256, sha256.groupForDisplay()))
        }
        AppUpdateMetaText(text = stringResource(R.string.base_general_app_update_apk_zip_tip))
        if (info.changelog.isNotEmpty()) {
            Text(
                text = stringResource(R.string.base_general_app_update_changelog),
                style = MaterialTheme.typography.titleSmall,
            )
            info.changelog.take(6).forEach { item ->
                Text(
                    text = stringResource(R.string.base_general_app_update_changelog_item, item),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }

        AppUpdateLinkButtons(
            /** 先展示直接下载入口，再补一个按 URL 去重后的 fallback 发布页。 */
            links = buildList {
                addAll(info.downloads)
                info.fallbackReleasePage?.let { page ->
                    if (none { it.url == page.url }) add(page)
                }
            },
            onOpenLink = onOpenLink,
        )
    }
}

/** 展示“当前已是最新版本”的简单提示。 */
@Composable
private fun UpToDateContent(state: AppUpdateDialogState.UpToDate) {
    Text(
        text = stringResource(
            R.string.base_general_app_update_up_to_date_message,
            state.versionName,
            state.versionCode,
        )
    )
}

/** 展示检查失败时的原因和可选 fallback 发布页入口。 */
@Composable
private fun CheckUnavailableContent(
    /** 当前失败状态。 */
    state: AppUpdateDialogState.CheckUnavailable,

    /** 打开 fallback 发布页的回调。 */
    onOpenLink: (AppUpdateLink) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(text = stringResource(R.string.base_general_app_update_unavailable_message))
        Text(
            text = stringResource(R.string.base_general_app_update_reason, state.reason.toDisplayText()),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        state.fallbackReleasePage?.let { fallback ->
            AppUpdateLinkButtons(links = listOf(fallback), onOpenLink = onOpenLink)
        }
    }
}

/** 统一渲染下载源或发布页按钮列表。 */
@Composable
private fun AppUpdateLinkButtons(
    /** 当前要展示的下载源列表。 */
    links: List<AppUpdateLink>,

    /** 点击某个下载源后的外部跳转回调。 */
    onOpenLink: (AppUpdateLink) -> Unit,
) {
    if (links.isEmpty()) return
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text(
            text = stringResource(R.string.base_general_app_update_download_sources),
            style = MaterialTheme.typography.titleSmall,
        )
        links.forEach { link ->
            Row(modifier = Modifier.fillMaxWidth()) {
                TextButton(onClick = { onOpenLink(link) }) {
                    Text(text = link.buttonText())
                }
            }
            link.extractCode?.let { code ->
                Text(
                    text = stringResource(R.string.base_general_app_update_extract_code, code),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** 生成下载源按钮文案；国内推荐源会额外带上“推荐”提示。 */
@Composable
private fun AppUpdateLink.buttonText(): String {
    return if (recommendedForChina) {
        stringResource(R.string.base_general_app_update_recommended_source, name)
    } else {
        name
    }
}

/** 统一渲染发布时间、摘要等较弱层级的元信息文本。 */
@Composable
private fun AppUpdateMetaText(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** 把内部失败原因映射成用户可见文案。 */
@Composable
private fun AppUpdateFailureReason.toDisplayText(): String {
    return when (this) {
        AppUpdateFailureReason.NetworkError -> stringResource(R.string.base_general_app_update_reason_network_error)
        AppUpdateFailureReason.HttpError -> stringResource(R.string.base_general_app_update_reason_http_error)
        AppUpdateFailureReason.InvalidJson -> stringResource(R.string.base_general_app_update_reason_invalid_json)
        AppUpdateFailureReason.SchemaUnsupported -> stringResource(R.string.base_general_app_update_reason_schema_unsupported)
        AppUpdateFailureReason.PackageMismatch -> stringResource(R.string.base_general_app_update_reason_package_mismatch)
        AppUpdateFailureReason.ChannelMismatch -> stringResource(R.string.base_general_app_update_reason_channel_mismatch)
        AppUpdateFailureReason.VersionNotNewer -> stringResource(R.string.base_general_app_update_reason_version_not_newer)
        AppUpdateFailureReason.NoDownloadSource -> stringResource(R.string.base_general_app_update_reason_no_download_source)
        AppUpdateFailureReason.UnknownError -> stringResource(R.string.base_general_app_update_reason_unknown_error)
    }
}

/** 按 8 位分组展示 SHA-256，降低用户人工核对时的阅读负担。 */
private fun String.groupForDisplay(): String {
    return trim().takeIf(String::isNotBlank)?.chunked(8)?.joinToString(" ") ?: this
}
