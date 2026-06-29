package com.cla.clip.master.ui.page.mine

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.ImageLoader
import coil3.compose.AsyncImage
import com.cla.clip.base.general.R
import com.cla.clip.base.general.config.ClipSourceBlockRules
import com.cla.clip.base.general.config.ManualPackageValidationResult
import com.cla.clip.master.installedapps.InstalledAppIconRequest
import com.cla.clip.master.installedapps.rememberInstalledAppIconImageLoader
import com.cla.clip.master.ui.theme.ClipMasterThemeTokens
import com.cla.clip.master.ui.widget.SecondaryPageScaffold
import com.cla.clip.master.ui.widget.clip.rememberSourceAppIconModel
import java.util.Locale

/**
 * 剪贴来源过滤设置页。
 *
 * 页面使用本地草稿集合，用户确认后一次性替换 AppSetting；当前安装应用只在页面可见时本地读取，不进入设置或备份。
 */
@Composable
fun ClipSourceBlockSettingPage(
    viewModel: ClipSourceBlockSettingVm = hiltViewModel(),
    onBack: () -> Unit,
) {
    /** 当前已持久化的过滤包名集合。 */
    val blockedPackages by viewModel.blockedClipSourcePackages.collectAsStateWithLifecycle()
    /** 当前合并后的候选 App 列表。 */
    val candidates by viewModel.blockedSourceAppCandidates.collectAsStateWithLifecycle()
    /** 当前安装列表、搜索和图标加载状态。 */
    val installedState by viewModel.installedAppListState.collectAsStateWithLifecycle()
    /** 当前安装应用图标专用 ImageLoader；可见行按包名从 PackageManager 懒加载图标。 */
    val installedAppIconImageLoader = rememberInstalledAppIconImageLoader()
    /** 页面编辑草稿；确认前不写入 AppSetting。 */
    var draftPackages by remember(blockedPackages) { mutableStateOf(blockedPackages) }
    /** 是否显示清空二次确认弹窗。 */
    var showClearConfirm by rememberSaveable { mutableStateOf(false) }
    /** 是否显示手动添加包名弹窗。 */
    var showManualAddDialog by rememberSaveable { mutableStateOf(false) }
    /** 过滤后的候选项；默认隐藏系统应用，搜索时允许命中系统应用。 */
    val visibleCandidates = remember(candidates, draftPackages, installedState.searchQuery, installedState.showSystemApps) {
        filterClipSourceBlockCandidates(
            candidates = candidates,
            draftPackages = draftPackages,
            searchQuery = installedState.searchQuery,
            showSystemApps = installedState.showSystemApps
        )
    }

    /** 页面首次进入时读取安装列表；页面离开后协程会随 ViewModel 生命周期停止。 */
    LaunchedEffect(Unit) {
        viewModel.loadInstalledAppsIfNeeded()
    }

    SecondaryPageScaffold(
        title = stringResource(R.string.base_general_clip_source_block_setting),
        onBack = onBack,
        actions = {
            IconButton(onClick = { showManualAddDialog = true }) {
                Icon(Icons.Default.Add, contentDescription = stringResource(R.string.base_general_clip_source_block_manual_add))
            }
            IconButton(
                enabled = !installedState.loading,
                onClick = viewModel::refreshInstalledApps
            ) {
                Icon(Icons.Default.Refresh, contentDescription = stringResource(R.string.base_general_clip_source_block_reload_apps))
            }
        },
        bottomBar = {
            ClipSourceBlockSettingBottomBar(
                draftCount = draftPackages.size,
                onClear = { showClearConfirm = true },
                onConfirm = {
                    viewModel.replaceBlockedSourcePackages(draftPackages)
                    onBack()
                }
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues),
            verticalArrangement = Arrangement.spacedBy(ClipMasterThemeTokens.tokens.spacing.small)
        ) {
            ClipSourceBlockSettingHeader(
                selectedCount = draftPackages.size,
                state = installedState,
            )
            Text(
                text = stringResource(R.string.base_general_clip_source_block_setting_notice),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 20.dp)
            )
            OutlinedTextField(
                value = installedState.searchQuery,
                onValueChange = viewModel::updateSearchQuery,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp),
                singleLine = true,
                label = { Text(stringResource(R.string.base_general_clip_source_block_search_hint)) },
                trailingIcon = {
                    if (installedState.searchQuery.isNotEmpty()) {
                        IconButton(onClick = { viewModel.updateSearchQuery("") }) {
                            Icon(Icons.Default.Clear, contentDescription = stringResource(R.string.base_general_clear_search_keyword))
                        }
                    }
                }
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { viewModel.updateShowSystemApps(!installedState.showSystemApps) }
                    .padding(horizontal = 20.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Checkbox(
                    checked = installedState.showSystemApps,
                    onCheckedChange = viewModel::updateShowSystemApps
                )
                Text(
                    text = stringResource(R.string.base_general_clip_source_block_show_system_apps),
                    style = MaterialTheme.typography.bodyMedium
                )
            }
            if (!installedState.unavailableReasonCode.isNullOrBlank()) {
                Text(
                    text = stringResource(R.string.base_general_clip_source_block_local_read_failed),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(horizontal = 20.dp)
                )
            }
            LazyColumn(
                modifier = Modifier.weight(1f)
            ) {
                items(
                    items = visibleCandidates,
                    key = { candidate -> candidate.packageName }
                ) { candidate ->
                    ClipSourceBlockCandidateRow(
                        candidate = candidate,
                        selected = candidate.packageName in draftPackages,
                        installedAppIconImageLoader = installedAppIconImageLoader,
                        onToggle = {
                            /** 当前行包名；作为草稿集合唯一身份。 */
                            val packageName = candidate.packageName
                            draftPackages = if (packageName in draftPackages) {
                                draftPackages - packageName
                            } else if (draftPackages.size < ClipSourceBlockRules.MAX_PACKAGE_COUNT) {
                                draftPackages + packageName
                            } else {
                                draftPackages
                            }
                        }
                    )
                }
                if (visibleCandidates.isEmpty()) {
                    item {
                        Text(
                            text = stringResource(R.string.base_general_clip_source_block_empty_candidates),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 20.dp, vertical = 24.dp)
                        )
                    }
                }
            }
        }
    }

    if (showClearConfirm) {
        ClipSourceBlockClearConfirmDialog(
            onDismiss = { showClearConfirm = false },
            onConfirm = {
                draftPackages = emptySet()
                showClearConfirm = false
            }
        )
    }

    if (showManualAddDialog) {
        ClipSourceBlockManualAddDialog(
            onDismiss = { showManualAddDialog = false },
            onAddManualPackage = { packageName ->
                /** 手动输入 trim 后的包名；用于识别重复添加时不占用新名额。 */
                val trimmedPackageName = packageName.trim()
                /** 手动添加校验使用的草稿数量；重复包名不应因为已达上限而被拒绝。 */
                val currentPackageCount = if (trimmedPackageName in draftPackages) {
                    (draftPackages.size - 1).coerceAtLeast(0)
                } else {
                    draftPackages.size
                }
                /** 手动添加校验结果；成功后同步草稿并关闭弹窗。 */
                val result = viewModel.addManualBlockedPackage(packageName, currentPackageCount)
                if (result is ManualPackageValidationResult.Valid) {
                    draftPackages = draftPackages + result.packageName
                    showManualAddDialog = false
                }
                result
            }
        )
    }
}

/** 页面头部摘要，展示当前草稿选择数量。 */
@Composable
private fun ClipSourceBlockSettingHeader(
    selectedCount: Int,
    state: InstalledAppListState,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 8.dp)
    ) {
        Text(
            text = stringResource(R.string.base_general_clip_source_block_setting),
            style = MaterialTheme.typography.titleMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            text = clipSourceBlockHeaderSubtitle(selectedCount = selectedCount, state = state),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/** 来源过滤页头部副标题，区分未读取、读取中、成功和失败但仍可手动添加状态。 */
@Composable
private fun clipSourceBlockHeaderSubtitle(
    selectedCount: Int,
    state: InstalledAppListState,
): String {
    /** 最近读取摘要；成功后用于显示简短数量，不保存到数据库。 */
    val summary = state.lastLoadSummary
    return when {
        state.loading -> stringResource(R.string.base_general_clip_source_block_loading_local_apps)
        summary != null -> stringResource(
            R.string.base_general_clip_source_block_local_load_summary,
            selectedCount,
            summary.appCount
        )
        state.unavailableReasonCode != null -> stringResource(R.string.base_general_clip_source_block_local_load_failed_keep_manual, selectedCount)
        !state.everLoaded -> stringResource(R.string.base_general_clip_source_block_never_loaded, selectedCount)
        else -> stringResource(R.string.base_general_selected_source_app_count, selectedCount)
    }
}

/** 来源过滤候选行，主视觉使用图标和 App 名，包名只作为辅助确认信息。 */
@Composable
private fun ClipSourceBlockCandidateRow(
    candidate: BlockedSourceAppCandidate,
    selected: Boolean,
    installedAppIconImageLoader: ImageLoader,
    onToggle: () -> Unit,
) {
    /** 通用 App 兜底图标；没有可读图标路径时直接绘制，避免 Coil 对 null model 产生噪声错误。 */
    val fallbackIconPainter = rememberVectorPainter(Icons.Default.Apps)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle)
            .padding(horizontal = 20.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        ClipSourceBlockCandidateIcon(
            icon = candidate.icon,
            fallbackIconPainter = fallbackIconPainter,
            installedAppIconImageLoader = installedAppIconImageLoader,
        )
        Spacer(modifier = Modifier.size(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = candidate.displayName,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = if (candidate.installed) {
                    candidate.packageName
                } else {
                    stringResource(R.string.base_general_clip_source_block_unrecognized_package, candidate.packageName)
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Checkbox(
            checked = selected,
            onCheckedChange = { onToggle() }
        )
    }
}

/** 来源过滤候选图标；当前安装应用按包名懒加载，历史来源继续读取旧文件路径。 */
@Composable
private fun ClipSourceBlockCandidateIcon(
    icon: BlockedSourceAppIcon,
    fallbackIconPainter: androidx.compose.ui.graphics.painter.Painter,
    installedAppIconImageLoader: ImageLoader,
) {
    when (icon) {
        is BlockedSourceAppIcon.Installed -> {
            AsyncImage(
                model = InstalledAppIconRequest(packageName = icon.packageName),
                imageLoader = installedAppIconImageLoader,
                contentDescription = null,
                placeholder = fallbackIconPainter,
                error = fallbackIconPainter,
                modifier = Modifier.size(40.dp),
                contentScale = ContentScale.Crop,
            )
        }
        is BlockedSourceAppIcon.HistoryFile -> {
            /** 历史来源图标请求模型；为空时直接使用通用 App 图标。 */
            val iconModel = rememberSourceAppIconModel(icon.iconPath, icon.iconHash)
            if (iconModel == null) {
                Icon(
                    painter = fallbackIconPainter,
                    contentDescription = null,
                    modifier = Modifier.size(40.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                AsyncImage(
                    model = iconModel,
                    contentDescription = null,
                    placeholder = fallbackIconPainter,
                    error = fallbackIconPainter,
                    modifier = Modifier.size(40.dp),
                    contentScale = ContentScale.Crop,
                )
            }
        }
        BlockedSourceAppIcon.None -> {
            Icon(
                painter = fallbackIconPainter,
                contentDescription = null,
                modifier = Modifier.size(40.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/** 设置页底部操作栏，承载清空和确认保存。 */
@Composable
private fun ClipSourceBlockSettingBottomBar(
    draftCount: Int,
    onClear: () -> Unit,
    onConfirm: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        TextButton(
            enabled = draftCount > 0,
            onClick = onClear
        ) {
            Text(stringResource(R.string.base_general_clip_source_block_clear_all))
        }
        Spacer(modifier = Modifier.weight(1f))
        TextButton(onClick = onConfirm) {
            Text(stringResource(R.string.base_general_sure))
        }
    }
}

/** 清空来源过滤名单二次确认。 */
@Composable
private fun ClipSourceBlockClearConfirmDialog(
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.base_general_clip_source_block_clear_title)) },
        text = { Text(stringResource(R.string.base_general_clip_source_block_clear_message)) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(stringResource(R.string.base_general_sure))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.base_general_cancel))
            }
        }
    )
}

/** 手动添加来源包名弹窗。 */
@Composable
private fun ClipSourceBlockManualAddDialog(
    onDismiss: () -> Unit,
    onAddManualPackage: (String) -> ManualPackageValidationResult,
) {
    /** 用户输入的包名文本。 */
    var packageName by rememberSaveable { mutableStateOf("") }
    /** 当前校验错误文案资源；为空表示暂未校验或成功。 */
    var errorTextRes by rememberSaveable { mutableStateOf<Int?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.base_general_clip_source_block_manual_add)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = packageName,
                    onValueChange = {
                        packageName = it
                        errorTextRes = null
                    },
                    singleLine = true,
                    label = { Text(stringResource(R.string.base_general_clip_source_block_manual_add_hint)) },
                    isError = errorTextRes != null
                )
                errorTextRes?.let { resId ->
                    Text(
                        text = stringResource(resId),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    /** 手动添加校验结果；失败时留在弹窗内展示错误。 */
                    val result = onAddManualPackage(packageName)
                    errorTextRes = result.toManualAddErrorTextRes()
                }
            ) {
                Text(stringResource(R.string.base_general_sure))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.base_general_cancel))
            }
        }
    )
}

/** 将手动添加校验结果映射为错误文案；成功时返回 null。 */
private fun ManualPackageValidationResult.toManualAddErrorTextRes(): Int? {
    return when (this) {
        is ManualPackageValidationResult.Valid -> null
        ManualPackageValidationResult.Blank -> R.string.base_general_clip_source_block_manual_error_blank
        ManualPackageValidationResult.SelfPackage -> R.string.base_general_clip_source_block_manual_error_self
        ManualPackageValidationResult.TooLong -> R.string.base_general_clip_source_block_manual_error_too_long
        ManualPackageValidationResult.UnsafeCharacters -> R.string.base_general_clip_source_block_manual_error_unsafe
        ManualPackageValidationResult.TooManyPackages -> R.string.base_general_clip_source_block_manual_error_too_many
    }
}

/** 过滤设置页候选项。 */
private fun filterClipSourceBlockCandidates(
    candidates: List<BlockedSourceAppCandidate>,
    draftPackages: Set<String>,
    searchQuery: String,
    showSystemApps: Boolean,
): List<BlockedSourceAppCandidate> {
    /** 标准化后的搜索关键词；只用于 UI 匹配，不影响保存规则。 */
    val normalizedQuery = searchQuery.trim().lowercase(Locale.ROOT)
    return candidates.filter { candidate ->
        /** 搜索模式下系统应用也可命中；默认模式下按开关隐藏未选中的系统应用。 */
        val visibleByDefault = candidate.packageName in draftPackages ||
            showSystemApps ||
            candidate.defaultVisible ||
            normalizedQuery.isNotEmpty()
        /** 搜索命中 App 名或包名。 */
        val matchesQuery = normalizedQuery.isEmpty() ||
            candidate.displayName.lowercase(Locale.ROOT).contains(normalizedQuery) ||
            candidate.packageName.lowercase(Locale.ROOT).contains(normalizedQuery)
        visibleByDefault && matchesQuery
    }
}
