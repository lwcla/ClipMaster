package com.cla.clip.master.ui.page.search

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.cla.clip.base.general.dao.SourceAppData
import com.cla.clip.base.general.utils.displayName
import com.cla.clip.master.ui.widget.SelectableListBottomSheet
import com.cla.clip.master.ui.widget.SelectableListItemState

/**
 * 来源 App 弹窗展开后的最大高度比例。
 *
 * 使用屏幕高度比例而不是固定 dp，避免大屏或横屏时弹窗仍停留在偏矮高度；如果后续体验需要微调，只改比例。
 */
private const val SOURCE_APP_SHEET_MAX_HEIGHT_RATIO = 0.86f

/**
 * 来源 App 选择弹窗。
 *
 * 标题右侧提供“全选”批量控制，列表只展示具体来源 App；弹窗内部草稿允许临时 0 选中，
 * 但 0 选中不能提交到外部筛选状态，避免破坏空集合表示“全部来源”的数据契约。
 */
@Composable
internal fun SourceAppPickerSheet(
    sourceApps: List<SourceAppData>,
    selectedPackageNames: Set<String>,
    onDismiss: () -> Unit,
    onConfirm: (Set<String>) -> Unit,
) {
    val configuration = LocalConfiguration.current
    val sheetMaxHeight = configuration.screenHeightDp.dp * SOURCE_APP_SHEET_MAX_HEIGHT_RATIO
    val allPackageNames = remember(sourceApps) {
        // 只把当前候选列表里的包名参与全选联动；候选缺失的旧筛选包名会保留到外部选择器，但不出现在弹窗内。
        sourceApps.map { it.packageName }.toSet()
    }
    var draftSelection by remember(selectedPackageNames, allPackageNames) {
        mutableStateOf(
            SourceAppDraftSelection.fromAppliedSelection(
                selectedPackageNames = selectedPackageNames,
                allPackageNames = allPackageNames
            )
        )
    }
    val isAllSelected = draftSelection.isAllSelected(allPackageNames)
    val selectedCount = draftSelection.selectedCount(allPackageNames)
    val canConfirm = sourceApps.isEmpty() || selectedCount > 0
    SelectableListBottomSheet(
        title = stringResource(com.cla.clip.base.general.R.string.base_general_source_app),
        items = sourceApps.map { sourceApp ->
            SelectableListItemState(
                id = sourceApp.packageName,
                title = sourceApp.displayName(),
                subtitle = sourceApp.packageName,
                selected = draftSelection.contains(
                    packageName = sourceApp.packageName,
                    allPackageNames = allPackageNames
                )
            )
        },
        onDismiss = onDismiss,
        onToggleItem = { packageName ->
            val rowSelected = draftSelection.contains(
                packageName = packageName,
                allPackageNames = allPackageNames
            )
            draftSelection = if (rowSelected) {
                draftSelection.remove(
                    packageName = packageName,
                    allPackageNames = allPackageNames
                )
            } else {
                draftSelection.add(
                    packageName = packageName,
                    allPackageNames = allPackageNames
                )
            }
        },
        onConfirm = { onConfirm(draftSelection.toAppliedPackageNames(allPackageNames)) },
        confirmEnabled = canConfirm,
        confirmText = stringResource(com.cla.clip.base.general.R.string.base_general_sure),
        cancelText = stringResource(com.cla.clip.base.general.R.string.base_general_cancel),
        maxHeight = sheetMaxHeight,
        showSelectAll = true,
        selectAllChecked = isAllSelected,
        selectAllEnabled = sourceApps.isNotEmpty(),
        selectAllText = stringResource(com.cla.clip.base.general.R.string.base_general_select_all),
        onToggleSelectAll = {
            draftSelection = if (isAllSelected) {
                SourceAppDraftSelection.Explicit(emptySet())
            } else {
                SourceAppDraftSelection.All
            }
        },
        errorText = if (!canConfirm) {
            stringResource(com.cla.clip.base.general.R.string.base_general_selected_source_app_count, 0)
        } else {
            null
        },
    )
}

/**
 * 生成当前已选来源 App 的展示名称列表。
 *
 * 来源名称由共享工具统一规整；数据库里仍可能只有包名，这类缺失候选继续回退包名，
 * 方便用户识别历史筛选条件。
 */
internal fun Map<String, String>.toSelectedSourceAppNames(
    selectedPackageNames: Set<String>,
): List<String> {
    if (selectedPackageNames.isEmpty()) {
        return emptyList()
    }

    val appNames = filterKeys { packageName -> packageName in selectedPackageNames }
        .values
        .toList()
    val missingPackageNames = selectedPackageNames
        .filterNot { packageName -> containsKey(packageName) }
        .sorted()
    return appNames + missingPackageNames
}

/**
 * 来源 App 弹窗内的草稿选择状态。
 *
 * 外部筛选用空集合表达“全部来源”，但弹窗内部还需要表达“临时 0 个来源被选中”的状态；
 * 因此这里显式区分 All 和 Explicit，避免把 0 选中误提交成全部来源。
 */
private sealed interface SourceAppDraftSelection {

    /** 全部来源模式，提交时会转换为空集合，沿用搜索筛选层“不过滤来源”的契约。 */
    data object All : SourceAppDraftSelection

    /** 显式选择的来源包名集合；允许临时为空，但为空时不能点击确定。 */
    data class Explicit(val packageNames: Set<String>) : SourceAppDraftSelection

    companion object {
        /**
         * 根据外部已应用筛选创建弹窗草稿。
         *
         * 外部空集合代表全部来源；非空集合只保留当前候选来源中的包名，避免已消失候选影响弹窗全选状态。
         */
        fun fromAppliedSelection(
            selectedPackageNames: Set<String>,
            allPackageNames: Set<String>,
        ): SourceAppDraftSelection {
            if (selectedPackageNames.isEmpty()) {
                return All
            }
            val visibleSelectedPackages = selectedPackageNames.intersect(allPackageNames)
            return normalizeExplicitSelection(
                selectedPackageNames = visibleSelectedPackages,
                allPackageNames = allPackageNames
            )
        }
    }
}

/** 判断草稿是否覆盖当前所有来源候选，空候选时仍按全部来源处理。 */
private fun SourceAppDraftSelection.isAllSelected(allPackageNames: Set<String>): Boolean {
    return when (this) {
        SourceAppDraftSelection.All -> true
        is SourceAppDraftSelection.Explicit -> allPackageNames.isNotEmpty() && packageNames.containsAll(allPackageNames)
    }
}

/** 计算弹窗内当前选中数量；全部来源模式下显示为当前候选数量。 */
private fun SourceAppDraftSelection.selectedCount(allPackageNames: Set<String>): Int {
    return when (this) {
        SourceAppDraftSelection.All -> allPackageNames.size
        is SourceAppDraftSelection.Explicit -> packageNames.size
    }
}

/** 判断某个具体来源行是否在草稿中被选中。 */
private fun SourceAppDraftSelection.contains(
    packageName: String,
    allPackageNames: Set<String>,
): Boolean {
    return when (this) {
        SourceAppDraftSelection.All -> packageName in allPackageNames
        is SourceAppDraftSelection.Explicit -> packageName in packageNames
    }
}

/**
 * 从草稿中移除一个来源。
 *
 * 如果当前是全部来源，先展开成当前全部候选包名再移除目标包名；这是“全选后取消单个”的核心联动。
 */
private fun SourceAppDraftSelection.remove(
    packageName: String,
    allPackageNames: Set<String>,
): SourceAppDraftSelection {
    val explicitPackages = when (this) {
        SourceAppDraftSelection.All -> allPackageNames
        is SourceAppDraftSelection.Explicit -> packageNames
    }
    return SourceAppDraftSelection.Explicit(explicitPackages - packageName)
}

/**
 * 向草稿中加入一个来源。
 *
 * 如果加入后覆盖当前所有候选，自动归一回 All，让标题右侧“全选”和外部空集合语义重新对齐。
 */
private fun SourceAppDraftSelection.add(
    packageName: String,
    allPackageNames: Set<String>,
): SourceAppDraftSelection {
    val explicitPackages = when (this) {
        SourceAppDraftSelection.All -> allPackageNames
        is SourceAppDraftSelection.Explicit -> packageNames
    } + packageName
    return normalizeExplicitSelection(
        selectedPackageNames = explicitPackages,
        allPackageNames = allPackageNames
    )
}

/** 将草稿转换成外部筛选可接收的包名集合；All 始终提交为空集合。 */
private fun SourceAppDraftSelection.toAppliedPackageNames(allPackageNames: Set<String>): Set<String> {
    return when (this) {
        SourceAppDraftSelection.All -> emptySet()
        is SourceAppDraftSelection.Explicit -> packageNames.intersect(allPackageNames)
    }
}

/** 显式集合覆盖所有候选时自动归一成 All，避免外部保存一份等价但更重的全量包名集合。 */
private fun normalizeExplicitSelection(
    selectedPackageNames: Set<String>,
    allPackageNames: Set<String>,
): SourceAppDraftSelection {
    return if (allPackageNames.isNotEmpty() && selectedPackageNames.containsAll(allPackageNames)) {
        SourceAppDraftSelection.All
    } else {
        SourceAppDraftSelection.Explicit(selectedPackageNames)
    }
}
