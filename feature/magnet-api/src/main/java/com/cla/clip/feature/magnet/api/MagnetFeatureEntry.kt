package com.cla.clip.feature.magnet.api

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.RowScope
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController

/**
 * 磁力功能的宿主接入口。
 *
 * API 只暴露导航、入口、详情页动作、下载记录扩展和备份摘要标签，不暴露磁力页面状态、
 * Repository、DAO、Room 实体或搜索源实现，保证宿主禁用磁力模块时集合为空即可隐藏全部磁力能力。
 */
interface MagnetFeatureEntry {
    /** 稳定功能 ID，用于宿主排序和日志。 */
    val featureId: String

    /** 下载记录页扩展入口；禁用模块时宿主不会拿到实现。 */
    val downloadHistoryEntry: MagnetDownloadHistoryEntry?

    /** 本功能参与恢复报告展示的分类 code 顺序。 */
    val restoreReportCategoryCodes: List<String>

    /** 注册磁力内部导航路由，具体 route 类型由磁力实现模块私有维护。 */
    fun registerNavigation(
        navGraphBuilder: NavGraphBuilder,
        onBack: () -> Unit,
    )

    /** 打开磁力搜索页，宿主不需要知道磁力 route 类型。 */
    fun openSearch(
        navController: NavHostController,
        initialQuery: String = "",
    )

    /** “我的”页入口内容，宿主只传入打开动作。 */
    @Composable
    fun MineEntry(onOpenSearch: () -> Unit)

    /** 详情页底部动作，使用 RowScope 让实现自行保持与宿主按钮同权重。 */
    @Composable
    fun RowScope.DetailAction(
        initialQuery: String,
        onOpenSearch: (String) -> Unit,
    )

    /** 当前功能在备份预检摘要中的计数行。 */
    fun backupCountItems(featureCounts: Map<String, Int>): List<MagnetBackupCountItem>

    /** 恢复报告分类的文案资源；未知分类 code 返回 null 交回宿主兜底。 */
    @StringRes
    fun restoreCategoryLabelRes(categoryCode: String): Int?
}

/** 磁力备份预检摘要行。 */
data class MagnetBackupCountItem(
    @StringRes val labelRes: Int,
    val count: Int,
)

/** 下载记录页磁力扩展入口。 */
interface MagnetDownloadHistoryEntry {
    /** Tab 稳定 ID。 */
    val tabId: String

    /** Tab 标题资源。 */
    @get:StringRes
    val tabTitleRes: Int

    /** 删除选中记录确认文案资源，必须包含一个数量占位。 */
    @get:StringRes
    val deleteSelectedMessageRes: Int

    /** 清空当前 Tab 确认文案资源，必须包含一个数量占位。 */
    @get:StringRes
    val clearTabMessageRes: Int

    /** 观察当前扩展记录数量；宿主只用它控制标题栏按钮和清空确认。 */
    fun observeCount(): kotlinx.coroutines.flow.Flow<Int>

    /** 当前排序下全部记录 id，用于宿主全选和清空。 */
    suspend fun getRecordIds(): List<Long>

    /** 删除指定记录；扩展自行保证不会删除本地媒体文件或其它业务数据。 */
    suspend fun deleteRecords(ids: Set<Long>): Int

    /** 渲染当前扩展 Tab 内容。 */
    @Composable
    fun Content(
        selectionState: MagnetDownloadSelectionState,
        callbacks: MagnetDownloadHistoryCallbacks,
        modifier: Modifier,
    )
}

/** 宿主传给下载记录扩展的选择态。 */
data class MagnetDownloadSelectionState(
    val selectedIds: Set<Long>,
    val selectionMode: Boolean,
)

/** 下载记录扩展回调；扩展不直接修改宿主选择态。 */
data class MagnetDownloadHistoryCallbacks(
    val onToggleSelected: (Long) -> Unit,
    val onEnterSelection: (Long) -> Unit,
    val onShowMessage: (String) -> Unit,
)

/** 磁力模块触发用户数据变化后通知宿主安排备份。 */
interface MagnetDirtyNotifier {
    fun markDirtyAndSchedule()
}
