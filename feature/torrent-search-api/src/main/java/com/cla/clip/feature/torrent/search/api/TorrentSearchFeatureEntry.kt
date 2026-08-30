package com.cla.clip.feature.torrent.search.api

import android.content.Context
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.RowScope
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * TorrentSearch 对宿主暴露的稳定轻量入口。
 *
 * API 只包含显式 Activity 启动、两个宿主 Composable 插槽和备份展示映射；不得泄漏导航、Provider、
 * Repository、DAO、Room 实体或实现模块 UI state。
 */
interface TorrentSearchFeatureEntry {
    /** 稳定功能 ID，用于宿主排序和集合去重，不作为用户可见文案。 */
    val featureId: String

    /** 本功能参与恢复报告展示的稳定分类 code 顺序。 */
    val restoreReportCategoryCodes: List<String>

    /**
     * 使用显式 Intent 启动独立 TorrentSearch Activity。
     *
     * [initialQuery] 只作为候选预填文本传递；[launchSource] 必须通过稳定 code 写入 Intent。
     */
    fun launch(
        context: Context,
        initialQuery: String,
        launchSource: TorrentSearchLaunchSource,
    )

    /**
     * “我的”页入口；组件只抛出点击事件，由宿主决定 Context 和启动来源。
     *
     * @param onLaunch 用户确认启动后的回调，不在 API 组件内读取宿主导航或 Context。
     * @param modifier 宿主提供的布局修饰符，用于适配现有列表间距。
     */
    @Composable
    fun MineEntry(
        onLaunch: () -> Unit,
        modifier: Modifier = Modifier,
    )

    /**
     * 详情页操作行入口；组件回传候选查询文本，不读取或修改系统剪贴板。
     *
     * @param initialQuery 宿主当前剪贴条目提供的候选文本，不在组件内持久化。
     * @param onLaunch 用户点击后的回调，只回传调用方提供的候选文本。
     * @param modifier 宿主操作行提供的布局修饰符。
     */
    @Composable
    fun RowScope.DetailAction(
        initialQuery: String,
        onLaunch: (String) -> Unit,
        modifier: Modifier = Modifier,
    )

    /** 把备份 manifest 的 featureCounts 映射为宿主预检摘要行。 */
    fun backupCountItems(featureCounts: Map<String, Int>): List<TorrentSearchBackupCountItem>

    /** 恢复报告分类的文案资源；未知分类返回 null 并交给宿主统一兜底。 */
    @StringRes
    fun restoreCategoryLabelRes(categoryCode: String): Int?
}

/** TorrentSearch 在宿主备份预检中的单行计数展示模型。 */
data class TorrentSearchBackupCountItem(
    /** 行标题资源；资源由实现模块提供，只有启用实现时才会进入集合。 */
    @get:StringRes val labelRes: Int,
    /** 当前分类数量；缺失的 featureCounts 键由实现映射为 0。 */
    val count: Int,
)
