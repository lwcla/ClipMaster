package com.cla.clip.feature.torrent.search

import android.app.Activity
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.cla.clip.feature.torrent.search.api.TorrentSearchBackupCountItem
import com.cla.clip.feature.torrent.search.api.TorrentSearchFeatureEntry
import com.cla.clip.feature.torrent.search.api.TorrentSearchLaunchSource
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import javax.inject.Inject
import javax.inject.Singleton

/** TorrentSearch 书签在备份 featureCounts 中的稳定计数 key。 */
private const val TORRENT_SEARCH_BOOKMARK_COUNT_KEY = "torrent_search_bookmark_count"

/** TorrentSearch 搜索历史在备份 featureCounts 中的稳定计数 key。 */
private const val TORRENT_SEARCH_SEARCH_HISTORY_COUNT_KEY = "torrent_search_search_history_count"

/** TorrentSearch 已查看条目在备份 featureCounts 中的稳定计数 key。 */
private const val TORRENT_SEARCH_VIEWED_ITEM_COUNT_KEY = "torrent_search_viewed_item_count"

/** TorrentSearch 非敏感设置在备份 featureCounts 中的稳定计数 key。 */
private const val TORRENT_SEARCH_SETTING_COUNT_KEY = "torrent_search_setting_count"

/** TorrentSearch 书签恢复报告的稳定分类 code。 */
private const val TORRENT_SEARCH_BOOKMARK_CATEGORY = "torrent_search_bookmarks"

/** TorrentSearch 搜索历史恢复报告的稳定分类 code。 */
private const val TORRENT_SEARCH_SEARCH_HISTORY_CATEGORY = "torrent_search_search_histories"

/** TorrentSearch 已查看条目恢复报告的稳定分类 code。 */
private const val TORRENT_SEARCH_VIEWED_ITEM_CATEGORY = "torrent_search_viewed_items"

/** TorrentSearch 非敏感设置恢复报告的稳定分类 code。 */
private const val TORRENT_SEARCH_SETTING_CATEGORY = "torrent_search_settings"

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
    override val restoreReportCategoryCodes: List<String> = listOf(
        TORRENT_SEARCH_BOOKMARK_CATEGORY,
        TORRENT_SEARCH_SEARCH_HISTORY_CATEGORY,
        TORRENT_SEARCH_VIEWED_ITEM_CATEGORY,
        TORRENT_SEARCH_SETTING_CATEGORY,
    )

    /** 使用显式组件 Intent 启动独立 Activity，并只写入稳定来源 code。 */
    override fun launch(
        context: Context,
        initialQuery: String,
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

    /** 渲染“我的”页轻量入口，视觉遵循 MaterialTheme，点击动作完全交给宿主。 */
    @Composable
    override fun MineEntry(
        onLaunch: () -> Unit,
        modifier: Modifier,
    ) {
        Card(
            modifier = modifier.fillMaxWidth(),
            onClick = onLaunch,
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = Icons.Default.Search,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.torrent_search_title),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        text = stringResource(R.string.torrent_search_entry_description),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }

    /** 渲染详情页等权快捷按钮，只回传调用方传入的候选查询文本。 */
    @Composable
    override fun RowScope.DetailAction(
        initialQuery: String,
        onLaunch: (String) -> Unit,
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
        return listOf(
            TorrentSearchBackupCountItem(
                labelRes = R.string.torrent_search_backup_bookmarks,
                count = featureCounts[TORRENT_SEARCH_BOOKMARK_COUNT_KEY] ?: 0,
            ),
            TorrentSearchBackupCountItem(
                labelRes = R.string.torrent_search_backup_search_histories,
                count = featureCounts[TORRENT_SEARCH_SEARCH_HISTORY_COUNT_KEY] ?: 0,
            ),
            TorrentSearchBackupCountItem(
                labelRes = R.string.torrent_search_backup_viewed_items,
                count = featureCounts[TORRENT_SEARCH_VIEWED_ITEM_COUNT_KEY] ?: 0,
            ),
            TorrentSearchBackupCountItem(
                labelRes = R.string.torrent_search_backup_settings,
                count = featureCounts[TORRENT_SEARCH_SETTING_COUNT_KEY] ?: 0,
            ),
        )
    }

    /** 把稳定恢复分类 code 映射为实现模块资源，未知值返回 null 交给宿主兜底。 */
    override fun restoreCategoryLabelRes(categoryCode: String): Int? {
        return when (categoryCode) {
            TORRENT_SEARCH_BOOKMARK_CATEGORY -> R.string.torrent_search_backup_bookmarks
            TORRENT_SEARCH_SEARCH_HISTORY_CATEGORY -> R.string.torrent_search_backup_search_histories
            TORRENT_SEARCH_VIEWED_ITEM_CATEGORY -> R.string.torrent_search_backup_viewed_items
            TORRENT_SEARCH_SETTING_CATEGORY -> R.string.torrent_search_backup_settings
            else -> null
        }
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
        impl: TorrentSearchFeatureEntryImpl,
    ): TorrentSearchFeatureEntry
}
