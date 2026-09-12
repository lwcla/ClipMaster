package com.cla.clip.feature.torrent.search.ui.local

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.cla.clip.feature.torrent.search.R
import com.cla.clip.feature.torrent.search.data.mapper.toTorrent
import com.cla.clip.feature.torrent.search.model.contract.Torrent
import com.cla.clip.feature.torrent.search.ui.component.ClearLocalDataConfirmationDialog
import com.cla.clip.feature.torrent.search.ui.component.TorrentResultCard
import com.cla.clip.feature.torrent.search.ui.component.toTorrentResultCardState
import java.text.DateFormat
import java.util.Date

/** 轻量本地数据主页，只负责进入三个独立目的地。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun LocalDataHomeScreen(
    /** 进入书签页。 */
    onBookmarks: () -> Unit,
    /** 进入搜索历史页。 */
    onHistory: () -> Unit,
    /** 进入已查看页。 */
    onViewed: () -> Unit,
    /** 返回搜索页。 */
    onBack: () -> Unit,
) {
    LocalDataScaffold(title = stringResource(R.string.torrent_search_local_data_title), onBack = onBack) { /** padding 是内容安全边距。 */ padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Button(onClick = onBookmarks, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.torrent_search_bookmarks_screen_title)) }
            Button(onClick = onHistory, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.torrent_search_search_history_screen_title)) }
            Button(onClick = onViewed, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.torrent_search_viewed_title)) }
        }
    }
}

/** 书签页复用 06B 结果卡，并在选择器返回后才把一次性 URI 交给调用方。 */
@Composable
internal fun BookmarksScreen(
    /** 当前书签目的地状态。 */
    state: BookmarksUiState,
    /** 书签点击进入原生详情。 */
    onOpenDetails: (Torrent) -> Unit,
    /** 单项移除事件。 */
    onRemove: (String) -> Unit,
    /** 清空确认请求。 */
    onRequestClear: () -> Unit,
    /** 清空确认动作。 */
    onConfirmClear: () -> Unit,
    /** 清空取消动作。 */
    onDismissClear: () -> Unit,
    /** 取得输入 URI 后启动导入。 */
    onImportDocument: (Uri) -> Unit,
    /** 取得输出 URI 后启动导出。 */
    onExportDocument: (Uri) -> Unit,
    /** 返回本地数据主页。 */
    onBack: () -> Unit,
) {
    /** importLauncher 只请求一次性文档读取，不申请或持久化 URI 权限。 */
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { /** uri 是系统选择器本次返回值。 */ uri ->
        if (uri != null) onImportDocument(uri)
    }
    /** exportLauncher 固定 application/json，仅在用户选定目标后启动写出。 */
    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { /** uri 是系统新建文档本次返回值。 */ uri ->
        if (uri != null) onExportDocument(uri)
    }
    LocalDataScaffold(
        title = stringResource(R.string.torrent_search_bookmarks_screen_title),
        onBack = onBack,
        actions = {
            TextButton(enabled = state.snapshotReady && !state.isBusy, onClick = { importLauncher.launch(arrayOf("application/json", "text/json")) }) {
                Text(stringResource(R.string.torrent_search_bookmarks_action_import))
            }
            TextButton(enabled = state.snapshotReady && !state.isBusy, onClick = { exportLauncher.launch("torrent-search-bookmarks-v1.json") }) {
                Text(stringResource(R.string.torrent_search_bookmarks_action_export))
            }
        },
    ) { /** padding 是顶部栏与系统栏安全边距。 */ padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item(key = "bookmark_actions") {
                Column(modifier = Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = onRequestClear,
                        enabled = state.bookmarks.isNotEmpty() && !state.isBusy,
                    ) { Text(stringResource(R.string.torrent_search_bookmarks_action_delete_all)) }
                    state.importReport?.let { /** report 是只含固定计数的最近导入结果。 */ report ->
                        Text(
                            stringResource(
                                R.string.torrent_search_bookmark_import_report,
                                report.importedCount,
                                report.skippedCount,
                                report.duplicateCount,
                                report.writeFailureCount,
                            ),
                        )
                    }
                    state.failure?.let { /** failure 是不含 URI 或业务正文的固定失败类型。 */ failure ->
                        LocalDataFailureText(failure)
                    }
                    if (state.snapshotReady && state.bookmarks.isEmpty()) {
                        Text(stringResource(R.string.torrent_search_bookmarks_empty_message))
                    }
                }
            }
            items(state.bookmarks, key = { /** bookmark 使用 infoHash 作为稳定业务身份。 */ bookmark -> bookmark.infoHash }) { /** bookmark 是当前展示快照。 */ bookmark ->
                Column(modifier = Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    /** torrent 是复用结果卡和详情选择态所需的领域映射。 */
                    val torrent = bookmark.toTorrent()
                    TorrentResultCard(state = torrent.toTorrentResultCardState(), onClick = { onOpenDetails(torrent) })
                    TextButton(enabled = !state.isBusy, onClick = { onRemove(bookmark.infoHash) }) {
                        Text(stringResource(R.string.torrent_search_remove_bookmark))
                    }
                }
            }
        }
    }
    if (state.showClearConfirmation) {
        ClearLocalDataConfirmationDialog(
            title = stringResource(R.string.torrent_search_bookmarks_dialog_title_delete_all),
            message = stringResource(R.string.torrent_search_bookmarks_dialog_message_delete_all),
            confirmLabel = stringResource(R.string.torrent_search_bookmarks_button_delete),
            onConfirm = onConfirmClear,
            onDismiss = onDismissClear,
        )
    }
}

/** 搜索历史页只回传安全查询，由导航根返回搜索页后再请求回放。 */
@Composable
internal fun SearchHistoryScreen(
    /** 当前历史目的地状态。 */
    state: SearchHistoryUiState,
    /** 请求搜索页回放；本页不得联网。 */
    onReplay: (String) -> Unit,
    /** 删除单条历史。 */
    onRemove: (String) -> Unit,
    /** 请求清空确认。 */
    onRequestClear: () -> Unit,
    /** 确认清空。 */
    onConfirmClear: () -> Unit,
    /** 取消清空。 */
    onDismissClear: () -> Unit,
    /** 返回本地数据主页。 */
    onBack: () -> Unit,
) {
    LocalDataScaffold(title = stringResource(R.string.torrent_search_search_history_screen_title), onBack = onBack) { /** padding 是页面安全边距。 */ padding ->
        LazyColumn(modifier = Modifier.fillMaxSize().padding(padding), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item(key = "history_actions") {
                Column(modifier = Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = onRequestClear,
                        enabled = state.items.isNotEmpty() && !state.isBusy,
                    ) { Text(stringResource(R.string.torrent_search_search_history_action_delete_all)) }
                    state.failure?.let { /** failure 是不含查询正文的固定失败类型。 */ failure -> LocalDataFailureText(failure) }
                    if (state.items.isEmpty()) Text(stringResource(R.string.torrent_search_search_history_empty_message))
                }
            }
            items(state.items, key = { /** item 使用规范化查询作为稳定业务身份。 */ item -> item.normalizedQuery }) { /** item 是当前历史行。 */ item ->
                Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { onReplay(item.query) }, modifier = Modifier.weight(1f)) { Text(item.query) }
                    TextButton(enabled = !state.isBusy, onClick = { onRemove(item.query) }) { Text(stringResource(R.string.torrent_search_remove_item)) }
                }
            }
        }
    }
    if (state.showClearConfirmation) {
        ClearLocalDataConfirmationDialog(
            title = stringResource(R.string.torrent_search_search_history_dialog_title_clear_history),
            message = stringResource(R.string.torrent_search_search_history_dialog_message_clear_history),
            confirmLabel = stringResource(R.string.torrent_search_search_history_button_clear),
            onConfirm = onConfirmClear,
            onDismiss = onDismissClear,
        )
    }
}

/** 已查看页仅展示不可还原完整内容的 hash 缩略与本地化时间。 */
@Composable
internal fun ViewedTorrentsScreen(
    /** 当前已查看目的地状态。 */
    state: ViewedUiState,
    /** 请求清空确认。 */
    onRequestClear: () -> Unit,
    /** 确认清空。 */
    onConfirmClear: () -> Unit,
    /** 取消清空。 */
    onDismissClear: () -> Unit,
    /** 返回本地数据主页。 */
    onBack: () -> Unit,
) {
    LocalDataScaffold(title = stringResource(R.string.torrent_search_viewed_title), onBack = onBack) { /** padding 是页面安全边距。 */ padding ->
        LazyColumn(modifier = Modifier.fillMaxSize().padding(padding), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item(key = "viewed_actions") {
                Column(modifier = Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = onRequestClear,
                        enabled = state.items.isNotEmpty() && !state.isBusy,
                    ) { Text(stringResource(R.string.torrent_search_viewed_clear)) }
                    state.failure?.let { /** failure 是不含完整 infoHash 的固定失败类型。 */ failure -> LocalDataFailureText(failure) }
                    if (state.items.isEmpty()) Text(stringResource(R.string.torrent_search_viewed_empty))
                }
            }
            items(state.items, key = { /** item 使用 infoHash 作为稳定业务身份。 */ item -> item.infoHash }) { /** item 是只含身份和时间的查看记录。 */ item ->
                Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                    Text(stringResource(R.string.torrent_search_viewed_hash, abbreviateInfoHash(item.infoHash)))
                    /** formattedTime 只用于本地展示，不进入持久化或日志。 */
                    val formattedTime = DateFormat.getDateTimeInstance().format(Date(item.viewedAtEpochMillis))
                    Text(stringResource(R.string.torrent_search_viewed_time, formattedTime))
                }
            }
        }
    }
    if (state.showClearConfirmation) {
        ClearLocalDataConfirmationDialog(
            title = stringResource(R.string.torrent_search_viewed_clear_title),
            message = stringResource(R.string.torrent_search_viewed_clear_message),
            confirmLabel = stringResource(R.string.torrent_search_viewed_clear),
            onConfirm = onConfirmClear,
            onDismiss = onDismissClear,
        )
    }
}

/** 仅保留首尾四位的安全 infoHash 展示，非法短值回退固定占位。 */
internal fun abbreviateInfoHash(infoHash: String): String {
    if (infoHash.length < 8) return "••••"
    return "${infoHash.take(4)}…${infoHash.takeLast(4)}"
}

/** 把固定失败类型映射到资源文案，不展示异常、URI、查询或完整 hash。 */
@Composable
private fun LocalDataFailureText(
    /** 页面 ViewModel 已收敛的固定失败类型。 */
    failure: LocalDataFailure,
) {
    /** messageRes 是当前失败分类对应的稳定本地化资源。 */
    val messageRes = when (failure) {
        LocalDataFailure.READ_FAILED -> R.string.torrent_search_local_data_read_failed
        LocalDataFailure.WRITE_FAILED -> R.string.torrent_search_local_data_write_failed
        LocalDataFailure.IMPORT_FAILED -> R.string.torrent_search_local_data_import_failed
        LocalDataFailure.EXPORT_FAILED -> R.string.torrent_search_local_data_export_failed
    }
    Text(stringResource(messageRes))
}

/** 本地数据页面共用顶部栏骨架，差异由 actions slot 和内容回调表达。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LocalDataScaffold(
    /** 页面资源化标题。 */
    title: String,
    /** 返回上级目的地。 */
    onBack: () -> Unit,
    /** 页面专属顶部动作。 */
    actions: @Composable () -> Unit = {},
    /** 接收 Scaffold 安全边距的页面内容。 */
    content: @Composable (androidx.compose.foundation.layout.PaddingValues) -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.torrent_search_action_back))
                    }
                },
                actions = { actions() },
            )
        },
        content = content,
    )
}
