package com.cla.clip.feature.torrent.search

import android.app.Activity
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.cla.clip.feature.torrent.search.api.TorrentSearchBackupCountItem
import com.cla.clip.feature.torrent.search.api.TorrentSearchFeatureEntry
import com.cla.clip.feature.torrent.search.api.TorrentSearchLaunchSource
import com.cla.clip.feature.torrent.search.api.TorrentSearchMineEntryCard
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import javax.inject.Inject
import javax.inject.Singleton

/**
 * TorrentSearch 的宿主入口实现。
 *
 * 构造函数保持无状态，宿主 Context 仅在用户点击时传入，避免实现模块被编译进来后产生冷启动副作用。
 */
@Singleton
internal class TorrentSearchFeatureEntryImpl @Inject constructor() : TorrentSearchFeatureEntry {
    /** 稳定功能 ID；宿主用它对多绑定集合排序。 */
    override val featureId: String = "torrent_search"

    /** 恢复报告按书签、搜索历史、已查看和设置的固定顺序展示。 */
    override val restoreReportCategoryCodes: List<String> =
        TorrentSearchBackupDisplayMapper.restoreReportCategoryCodes

    /** 使用显式组件 Intent 启动独立 Activity，并只写入稳定来源 code。 */
    override fun launch(
        /** 启动宿主；非 Activity Context 会由实现补充新任务标记。 */
        context: Context,
        /** 仅预填到搜索页的初始查询文本，后续输入加固由 Activity 负责。 */
        initialQuery: String,
        /** 使用稳定 code 写入 Intent 的入口来源，不依赖枚举名称或 ordinal。 */
        launchSource: TorrentSearchLaunchSource,
    ) {
        /** 指向实现模块 Activity 的显式 Intent；非 Activity Context 需要新任务标记。 */
        val launchIntent = Intent(context, TorrentSearchActivity::class.java).apply {
            putExtra(EXTRA_TORRENT_SEARCH_INITIAL_QUERY, initialQuery)
            putExtra(EXTRA_TORRENT_SEARCH_LAUNCH_SOURCE, launchSource.code)
            if (context !is Activity) {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        }
        context.startActivity(launchIntent)
    }

    /** 提供“我的”页本地化内容，并把实际卡片、主题 token 与溢出规则交给宿主渲染器。 */
    @Composable
    override fun MineEntry(
        /** 用户点击“我的”入口后的宿主回调。 */
        onLaunch: () -> Unit,
        /** 宿主提供的共享卡片渲染器，避免实现模块反向依赖 app UI。 */
        entryCard: TorrentSearchMineEntryCard,
        /** 宿主传入的布局约束，保持共享入口排版一致。 */
        modifier: Modifier,
    ) {
        /** 当前语言环境下的入口标题，由实现模块资源提供但不决定宿主排版。 */
        val title = stringResource(R.string.torrent_search_title)
        /** 当前语言环境下的入口说明，由宿主共享卡片负责动态字体和溢出处理。 */
        val description = stringResource(R.string.torrent_search_entry_description)
        entryCard(title, description, onLaunch, modifier)
    }

    /** 渲染详情页等权快捷按钮，只回传调用方传入的候选查询文本。 */
    @Composable
    override fun RowScope.DetailAction(
        /** 详情页候选查询文本，仅在点击时原样交回宿主。 */
        initialQuery: String,
        /** 点击后的查询启动回调，由宿主决定 Activity 启动时机。 */
        onLaunch: (String) -> Unit,
        /** 等权 RowScope 按钮的外部布局约束。 */
        modifier: Modifier,
    ) {
        Button(
            modifier = modifier.weight(1f),
            onClick = { onLaunch(initialQuery) },
        ) {
            Icon(imageVector = Icons.Default.Search, contentDescription = null)
            Spacer(modifier = Modifier.width(6.dp))
            Text(text = stringResource(R.string.torrent_search_title))
        }
    }

    /** 按稳定 featureCounts key 生成宿主备份预检的四行计数。 */
    override fun backupCountItems(featureCounts: Map<String, Int>): List<TorrentSearchBackupCountItem> {
        return TorrentSearchBackupDisplayMapper.backupCountItems(featureCounts)
    }

    /** 把稳定恢复分类 code 映射为实现模块资源，未知值返回 null 交给宿主兜底。 */
    override fun restoreCategoryLabelRes(categoryCode: String): Int? {
        return TorrentSearchBackupDisplayMapper.restoreCategoryLabelRes(categoryCode)
    }
}

/** 仅在实现模块启用时，把唯一入口加入 API 声明的 Hilt 集合。 */
@Module
@InstallIn(SingletonComponent::class)
internal abstract class TorrentSearchFeatureBindingModule {
    /** 将无状态实现绑定进集合；关闭构建不依赖本模块，因此集合保持为空。 */
    @Binds
    @IntoSet
    abstract fun bindTorrentSearchFeatureEntry(
        /** 唯一无状态入口实现，由 Hilt 创建后加入跨模块集合。 */
        impl: TorrentSearchFeatureEntryImpl,
    ): TorrentSearchFeatureEntry
}
