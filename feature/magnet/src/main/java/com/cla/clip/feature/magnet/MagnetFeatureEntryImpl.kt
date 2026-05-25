package com.cla.clip.feature.magnet

import androidx.annotation.Keep
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.composable
import androidx.navigation.toRoute
import com.cla.clip.base.general.backup.BackupFeatureContributor
import com.cla.clip.feature.magnet.api.MagnetBackupCountItem
import com.cla.clip.feature.magnet.api.MagnetDownloadHistoryEntry
import com.cla.clip.feature.magnet.api.MagnetFeatureEntry
import com.cla.clip.feature.magnet.backup.MagnetBackupContributorImpl
import com.cla.clip.feature.magnet.backup.MAGNET_DOWNLOAD_RECORDS_CATEGORY
import com.cla.clip.feature.magnet.backup.MAGNET_DOWNLOAD_RECORD_COUNT_KEY
import com.cla.clip.feature.magnet.backup.MAGNET_SEARCH_HISTORIES_CATEGORY
import com.cla.clip.feature.magnet.backup.MAGNET_SEARCH_HISTORY_COUNT_KEY
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.Serializable

@Keep
@Serializable
internal data class MagnetSearchRoute(
    val initialQuery: String = "",
)

/** 磁力 feature 对宿主暴露的唯一入口。 */
@Singleton
internal class MagnetFeatureEntryImpl @Inject constructor(
    override val downloadHistoryEntry: MagnetDownloadHistoryEntryImpl,
) : MagnetFeatureEntry {
    override val featureId: String = "magnet"

    override val restoreReportCategoryCodes: List<String> = listOf(
        MAGNET_SEARCH_HISTORIES_CATEGORY,
        MAGNET_DOWNLOAD_RECORDS_CATEGORY
    )

    override fun registerNavigation(navGraphBuilder: NavGraphBuilder, onBack: () -> Unit) {
        navGraphBuilder.composable<MagnetSearchRoute> { backStackEntry ->
            val route = backStackEntry.toRoute<MagnetSearchRoute>()
            MagnetSearchPage(
                initialQuery = route.initialQuery,
                onBack = onBack
            )
        }
    }

    override fun openSearch(navController: NavHostController, initialQuery: String) {
        navController.navigate(MagnetSearchRoute(initialQuery))
    }

    @Composable
    override fun MineEntry(onOpenSearch: () -> Unit) {
        MagnetFeatureCard(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 6.dp),
            onClick = onOpenSearch
        ) {
            androidx.compose.foundation.layout.Row(
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.Search,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
                androidx.compose.foundation.layout.Spacer(Modifier.padding(horizontal = 6.dp))
                androidx.compose.foundation.layout.Column(Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.magnet_feature_magnet_search),
                        style = MaterialTheme.typography.titleMedium
                    )
                    Text(
                        text = stringResource(R.string.magnet_feature_magnet_search_entry_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }

    @Composable
    override fun RowScope.DetailAction(
        initialQuery: String,
        onOpenSearch: (String) -> Unit,
    ) {
        Button(
            modifier = Modifier.weight(1f),
            onClick = { onOpenSearch(initialQuery) }
        ) {
            Icon(imageVector = Icons.Default.Link, contentDescription = null)
            androidx.compose.foundation.layout.Spacer(Modifier.padding(horizontal = 3.dp))
            Text(stringResource(R.string.magnet_feature_magnet_search))
        }
    }

    override fun backupCountItems(featureCounts: Map<String, Int>): List<MagnetBackupCountItem> {
        return listOf(
            MagnetBackupCountItem(
                labelRes = R.string.magnet_feature_backup_count_magnet_search_histories,
                count = featureCounts[MAGNET_SEARCH_HISTORY_COUNT_KEY] ?: 0
            ),
            MagnetBackupCountItem(
                labelRes = R.string.magnet_feature_backup_count_magnet_download_records,
                count = featureCounts[MAGNET_DOWNLOAD_RECORD_COUNT_KEY] ?: 0
            )
        )
    }

    override fun restoreCategoryLabelRes(categoryCode: String): Int? {
        return when (categoryCode) {
            MAGNET_SEARCH_HISTORIES_CATEGORY -> R.string.magnet_feature_backup_count_magnet_search_histories
            MAGNET_DOWNLOAD_RECORDS_CATEGORY -> R.string.magnet_feature_backup_count_magnet_download_records
            else -> null
        }
    }
}

@Module
@InstallIn(SingletonComponent::class)
internal abstract class MagnetFeatureBindingModule {
    @Binds
    @IntoSet
    abstract fun bindMagnetFeatureEntry(impl: MagnetFeatureEntryImpl): MagnetFeatureEntry

    @Binds
    @IntoSet
    abstract fun bindMagnetBackupContributor(impl: MagnetBackupContributorImpl): BackupFeatureContributor
}
