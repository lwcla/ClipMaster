package com.cla.clip.master.ui.page.recycle

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.flowWithLifecycle
import androidx.paging.compose.collectAsLazyPagingItems
import com.cla.clip.base.general.config.AppSetting
import com.cla.clip.base.general.entity.ClipShowEntity
import com.cla.clip.master.ui.widget.SecondaryPageScaffold
import com.cla.clip.master.ui.widget.SingleChoiceRow
import com.cla.clip.master.ui.widget.clip.ClipCardTimeMode
import com.cla.clip.master.ui.widget.clip.ClipResultList

/**
 * 回收站页面。
 *
 * 页面复用共享剪贴列表卡片，但关闭复制、置顶和普通删除菜单；点击卡片确认还原，右滑到底或多选操作执行彻底删除。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecycleBinPage(
    viewModel: RecycleBinVm = hiltViewModel(),
    onBack: () -> Unit,
) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val pagedClips = remember(viewModel.pagedClips, lifecycle) {
        viewModel.pagedClips.flowWithLifecycle(lifecycle, Lifecycle.State.STARTED)
    }.collectAsLazyPagingItems()
    val listState = rememberLazyListState()
    val selectedIds by viewModel.selectedIds.collectAsStateWithLifecycle()
    val selectionMode by viewModel.selectionMode.collectAsStateWithLifecycle()
    val retentionDays by viewModel.retentionDays.collectAsStateWithLifecycle()
    var restoreClip by remember { mutableStateOf<ClipShowEntity?>(null) }
    var showClearConfirm by remember { mutableStateOf(false) }
    var showDeleteSelectedConfirm by remember { mutableStateOf(false) }
    var showSettingSheet by remember { mutableStateOf(false) }

    BackHandler(enabled = selectionMode) {
        viewModel.clearSelection()
    }

    SecondaryPageScaffold(
        title = if (!selectionMode) {
            stringResource(com.cla.clip.base.general.R.string.base_general_recycle_bin)
        } else {
            stringResource(com.cla.clip.base.general.R.string.base_general_selected_count, selectedIds.size)
        },
        onBack = {
            if (selectionMode) {
                viewModel.clearSelection()
            } else {
                onBack()
            }
        },
        actions = {
            if (!selectionMode) {
                IconButton(onClick = { showClearConfirm = true }) {
                    Icon(
                        imageVector = Icons.Default.DeleteForever,
                        contentDescription = stringResource(com.cla.clip.base.general.R.string.base_general_clear_recycle_bin)
                    )
                }
                IconButton(onClick = { showSettingSheet = true }) {
                    Icon(
                        imageVector = Icons.Default.Settings,
                        contentDescription = stringResource(com.cla.clip.base.general.R.string.base_general_recycle_bin_settings)
                    )
                }
            }
        },
        bottomBar = {
            if (selectionMode) {
                RecycleBinSelectionBar(
                    selectedCount = selectedIds.size,
                    onDelete = { showDeleteSelectedConfirm = true }
                )
            }
        }
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            ClipResultList(
                listState = listState,
                pagedClips = pagedClips,
                emptyText = stringResource(com.cla.clip.base.general.R.string.base_general_recycle_bin_empty),
                // 回收站普通态与其他结果列表统一轻量底部留白；多选态继续为底部危险操作栏预留更大空间。
                contentPadding = PaddingValues(start = 10.dp, top = 10.dp, end = 10.dp, bottom = 12.dp),
                onPinToggle = null,
                onDelete = null,
                onCopy = null,
                onSwipePastAction = viewModel::deleteClipPermanently,
                swipePastActionText = stringResource(com.cla.clip.base.general.R.string.base_general_continue_swipe_to_delete_permanently),
                onClick = { clip ->
                    if (selectionMode) {
                        viewModel.toggleSelection(clip)
                    } else {
                        restoreClip = clip
                    }
                },
                onLongClick = viewModel::enterSelection,
                timeMode = ClipCardTimeMode.DeletedTime,
                selectedIds = selectedIds,
            )
        }
    }

    RestoreConfirmDialog(
        clip = restoreClip,
        onDismiss = { restoreClip = null },
        onConfirm = { clip ->
            restoreClip = null
            viewModel.restoreClip(clip)
        }
    )

    ConfirmPermanentActionDialog(
        visible = showClearConfirm,
        title = stringResource(com.cla.clip.base.general.R.string.base_general_clear_recycle_bin),
        message = stringResource(com.cla.clip.base.general.R.string.base_general_clear_recycle_bin_message),
        onDismiss = { showClearConfirm = false },
        onConfirm = {
            showClearConfirm = false
            viewModel.clearRecycleBinPermanently()
        }
    )

    ConfirmPermanentActionDialog(
        visible = showDeleteSelectedConfirm,
        title = stringResource(com.cla.clip.base.general.R.string.base_general_delete_selected_permanently),
        message = stringResource(com.cla.clip.base.general.R.string.base_general_delete_selected_permanently_message, selectedIds.size),
        onDismiss = { showDeleteSelectedConfirm = false },
        onConfirm = {
            showDeleteSelectedConfirm = false
            viewModel.deleteSelectedPermanently()
        }
    )

    if (showSettingSheet) {
        RecycleBinSettingSheet(
            currentDays = retentionDays,
            onDismiss = { showSettingSheet = false },
            onSave = { days ->
                showSettingSheet = false
                viewModel.saveRetentionDays(days)
            }
        )
    }
}

/** 回收站多选态底部操作栏，只承载彻底删除动作；允许 0 选中停留在多选态，但禁用删除避免误确认。 */
@Composable
private fun RecycleBinSelectionBar(
    selectedCount: Int,
    onDelete: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = stringResource(com.cla.clip.base.general.R.string.base_general_selected_count, selectedCount),
            style = MaterialTheme.typography.bodyMedium
        )
        Button(
            enabled = selectedCount > 0,
            onClick = onDelete
        ) {
            Text(stringResource(com.cla.clip.base.general.R.string.base_general_delete_permanently))
        }
    }
}

/** 单条回收站记录还原确认弹窗，确认后只恢复删除状态，不改变原折叠、折叠时间和置顶信息。 */
@Composable
private fun RestoreConfirmDialog(
    clip: ClipShowEntity?,
    onDismiss: () -> Unit,
    onConfirm: (ClipShowEntity) -> Unit,
) {
    if (clip == null) return
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(com.cla.clip.base.general.R.string.base_general_restore_clip_title)) },
        text = { Text(stringResource(com.cla.clip.base.general.R.string.base_general_restore_clip_message)) },
        confirmButton = {
            TextButton(onClick = { onConfirm(clip) }) {
                Text(stringResource(com.cla.clip.base.general.R.string.base_general_restore))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(com.cla.clip.base.general.R.string.base_general_cancel))
            }
        }
    )
}

/** 回收站永久操作确认弹窗，统一提示不可恢复。 */
@Composable
private fun ConfirmPermanentActionDialog(
    visible: Boolean,
    title: String,
    message: String,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    if (!visible) return
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(message) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(
                    text = stringResource(com.cla.clip.base.general.R.string.base_general_delete_permanently),
                    color = MaterialTheme.colorScheme.error
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(com.cla.clip.base.general.R.string.base_general_cancel))
            }
        }
    )
}

/** 回收站保留天数设置底部弹窗，保存后会立即清理超过新天数的数据。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RecycleBinSettingSheet(
    currentDays: Int,
    onDismiss: () -> Unit,
    onSave: (Int) -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var selectedDays by remember(currentDays) { mutableStateOf(currentDays) }
    var customText by remember(currentDays) { mutableStateOf(currentDays.toString()) }
    val customDays = customText.toIntOrNull()
    val effectiveDays = when (selectedDays) {
        7, 30 -> selectedDays
        else -> customDays
    }
    val canSave = effectiveDays != null &&
        effectiveDays in AppSetting.MIN_RECYCLE_BIN_RETENTION_DAYS..AppSetting.MAX_RECYCLE_BIN_RETENTION_DAYS

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 12.dp)
        ) {
            Text(
                text = stringResource(com.cla.clip.base.general.R.string.base_general_recycle_bin_settings),
                style = MaterialTheme.typography.titleMedium
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = stringResource(com.cla.clip.base.general.R.string.base_general_current_retention_days, currentDays),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(12.dp))
            SingleChoiceRow(
                title = stringResource(com.cla.clip.base.general.R.string.base_general_keep_7_days),
                selected = selectedDays == 7,
                onClick = {
                    selectedDays = 7
                    customText = "7"
                }
            )
            SingleChoiceRow(
                title = stringResource(com.cla.clip.base.general.R.string.base_general_keep_30_days),
                selected = selectedDays == 30,
                onClick = {
                    selectedDays = 30
                    customText = "30"
                }
            )
            SingleChoiceRow(
                title = stringResource(com.cla.clip.base.general.R.string.base_general_custom_days),
                selected = selectedDays != 7 && selectedDays != 30,
                onClick = { selectedDays = customDays ?: currentDays }
            )
            OutlinedTextField(
                value = customText,
                onValueChange = { value ->
                    customText = value.filter { it.isDigit() }
                    selectedDays = customText.toIntOrNull() ?: selectedDays
                },
                modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                singleLine = true,
                label = { Text(stringResource(com.cla.clip.base.general.R.string.base_general_custom_retention_days_hint)) }
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = stringResource(com.cla.clip.base.general.R.string.base_general_recycle_bin_retention_warning),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error
            )
            Spacer(Modifier.height(16.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                TextButton(onClick = onDismiss) {
                    Text(stringResource(com.cla.clip.base.general.R.string.base_general_cancel))
                }
                TextButton(
                    enabled = canSave,
                    onClick = { effectiveDays?.let(onSave) }
                ) {
                    Text(stringResource(com.cla.clip.base.general.R.string.base_general_sure))
                }
            }
        }
    }
}
