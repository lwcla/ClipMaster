package com.cla.clip.feature.torrent.search.ui.local

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cla.clip.feature.torrent.search.data.document.BookmarkDocumentGateway
import com.cla.clip.feature.torrent.search.data.exporter.BookmarkExportResult
import com.cla.clip.feature.torrent.search.data.exporter.BookmarkExportSerializer
import com.cla.clip.feature.torrent.search.data.importer.BookmarkImportResult
import com.cla.clip.feature.torrent.search.data.importer.BookmarkImportCoordinator
import com.cla.clip.feature.torrent.search.data.importer.BookmarkPersistenceReport
import com.cla.clip.feature.torrent.search.data.model.BookmarkedTorrent
import com.cla.clip.feature.torrent.search.data.model.TorrentSearchHistory
import com.cla.clip.feature.torrent.search.data.model.ViewedTorrent
import com.cla.clip.feature.torrent.search.data.repository.BookmarkRepository
import com.cla.clip.feature.torrent.search.data.repository.TorrentSearchDataLogger
import com.cla.clip.feature.torrent.search.data.repository.TorrentSearchHistoryRepository
import com.cla.clip.feature.torrent.search.data.repository.ViewedTorrentRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** 本地数据页面固定失败分类，不携带查询、hash、URI 或异常正文。 */
internal enum class LocalDataFailure {
    /** Room 读取流失败。 */
    READ_FAILED,
    /** 保存、删除或清空失败。 */
    WRITE_FAILED,
    /** SAF 输入文档无效或读取失败。 */
    IMPORT_FAILED,
    /** 导出协议超限或 SAF 写出失败。 */
    EXPORT_FAILED,
}

/** 书签目的地不可变状态；URI 永不进入状态。 */
internal data class BookmarksUiState(
    /** 当前可见时收集到的最近书签快照。 */
    val bookmarks: List<BookmarkedTorrent> = emptyList(),
    /** 当前目的地是否至少 STARTED。 */
    val isScreenActive: Boolean = false,
    /** 首个 Room 快照是否到达，未到达前禁止导出。 */
    val snapshotReady: Boolean = false,
    /** 是否正在执行删除、清空、导入或导出。 */
    val isBusy: Boolean = false,
    /** 是否展示共享清空确认。 */
    val showClearConfirmation: Boolean = false,
    /** 最近一次导入的固定计数报告。 */
    val importReport: BookmarkPersistenceReport? = null,
    /** 最近一次低敏失败分类。 */
    val failure: LocalDataFailure? = null,
)

/** 搜索历史目的地不可变状态。 */
internal data class SearchHistoryUiState(
    /** 当前可见时收集到的历史快照。 */
    val items: List<TorrentSearchHistory> = emptyList(),
    /** 当前目的地是否至少 STARTED。 */
    val isScreenActive: Boolean = false,
    /** 本地写操作是否运行中。 */
    val isBusy: Boolean = false,
    /** 是否展示共享清空确认。 */
    val showClearConfirmation: Boolean = false,
    /** 最近一次低敏失败分类。 */
    val failure: LocalDataFailure? = null,
)

/** 已查看目的地不可变状态。 */
internal data class ViewedUiState(
    /** 当前可见时收集到的安全身份与时间。 */
    val items: List<ViewedTorrent> = emptyList(),
    /** 当前目的地是否至少 STARTED。 */
    val isScreenActive: Boolean = false,
    /** 清空操作是否运行中。 */
    val isBusy: Boolean = false,
    /** 是否展示共享清空确认。 */
    val showClearConfirmation: Boolean = false,
    /** 最近一次低敏失败分类。 */
    val failure: LocalDataFailure? = null,
)

/** 书签列表、精确删除和一次性 SAF 传输的目的地作用域编排器。 */
@HiltViewModel
internal class BookmarksViewModel @Inject constructor(
    /** 书签 Room 领域边界。 */
    private val repository: BookmarkRepository,
    /** 只接收一次性 URI 参数的 feature-local 文档网关。 */
    private val documentGateway: BookmarkDocumentGateway,
    /** 解析后逐项写入、重复与部分失败计数协作者。 */
    private val importCoordinator: BookmarkImportCoordinator,
    /** 既有固定 operation/state/count 日志出口。 */
    private val logger: TorrentSearchDataLogger,
    /** 在后台逐项生成完整有界文档的导出器。 */
    private val exportSerializer: BookmarkExportSerializer = BookmarkExportSerializer(),
) : ViewModel() {
    /** 页面唯一可变状态。 */
    private val mutableUiState = MutableStateFlow(BookmarksUiState())
    /** Compose 生命周期感知收集的只读状态。 */
    val uiState: StateFlow<BookmarksUiState> = mutableUiState.asStateFlow()
    /** Room 收集任务只在目的地可见时存在。 */
    private var collectionJob: Job? = null
    /** Room 收集代次阻止旧 finally 清除重进后的新任务。 */
    private var collectionGeneration = 0L
    /** 页面 IO/写入任务离页时统一取消。 */
    private var operationJob: Job? = null
    /** 页面操作代次阻止旧结果或 finally 覆盖新任务状态。 */
    private var operationGeneration = 0L

    /** 同步目的地活跃状态；激活才订阅全量阶段性 Flow，离页立即取消全部工作。 */
    fun setScreenActive(active: Boolean) {
        mutableUiState.update { /** state 是生命周期切换前的页面快照。 */ state -> state.copy(isScreenActive = active) }
        if (active) startCollecting() else stopWork()
    }

    /** 请求展示清空确认，不直接产生数据库副作用。 */
    fun requestClear() {
        if (mutableUiState.value.bookmarks.isNotEmpty()) {
            mutableUiState.update { /** state 是展示确认前的页面快照。 */ state -> state.copy(showClearConfirmation = true) }
        }
    }

    /** 关闭清空确认且不删除数据。 */
    fun dismissClear() {
        mutableUiState.update { /** state 是关闭确认前的页面快照。 */ state -> state.copy(showClearConfirmation = false) }
    }

    /** 用户确认后清空全部书签；离页取消不会显示失败。 */
    fun confirmClear() {
        dismissClear()
        launchWrite { repository.clear() }
    }

    /** 删除单个稳定身份书签。 */
    fun remove(infoHash: String) {
        launchWrite { if (repository.delete(infoHash)) 1 else 0 }
    }

    /** SAF 选择器回调取得 URI 后才启动读取和逐项写入。 */
    fun importFrom(uri: Uri) {
        if (!canStartOperation() || !mutableUiState.value.snapshotReady) return
        /** generation 固定本次导入所有权。 */
        val generation = ++operationGeneration
        operationJob = viewModelScope.launch {
            if (generation == operationGeneration) setBusy(true)
            logger.log("bookmark_import", "started")
            try {
                when (/** parsed 是 05C 解析器提供的有界批次。 */ val parsed = documentGateway.read(uri)) {
                    is BookmarkImportResult.Failure -> {
                        logger.log("bookmark_import", "failed")
                        if (generation == operationGeneration) setFailure(LocalDataFailure.IMPORT_FAILED)
                    }
                    is BookmarkImportResult.Success -> {
                        /** report 是逐项隔离写入后的固定计数。 */
                        val report = importCoordinator.persist(parsed)
                        if (generation == operationGeneration) mutableUiState.update { /** state 是写入导入计数前页面快照。 */ state ->
                            state.copy(importReport = report, failure = null)
                        }
                        logger.log("bookmark_import", if (report.writeFailureCount == 0) "completed" else "partial", report.importedCount)
                    }
                }
            /** 离页或新任务取消时记录固定阶段并继续传播。 */
            } catch (cancellation: CancellationException) {
                logger.log("bookmark_import", "cancelled")
                throw cancellation
            /** 网关或数据库异常统一映射为固定导入失败。 */
            } catch (exception: Exception) {
                logger.log("bookmark_import", "failed")
                if (generation == operationGeneration) setFailure(LocalDataFailure.IMPORT_FAILED)
            } finally {
                if (generation == operationGeneration) {
                    setBusy(false)
                    operationJob = null
                }
            }
        }
    }

    /** SAF 创建文档回调取得 URI 后才序列化当前完整快照并写出。 */
    fun exportTo(uri: Uri) {
        if (!canStartOperation() || !mutableUiState.value.snapshotReady) return
        /** snapshot 固定选择器返回时的完整列表，后续 Room 更新不改变本次文件。 */
        val snapshot = mutableUiState.value.bookmarks
        /** generation 固定本次导出所有权。 */
        val generation = ++operationGeneration
        operationJob = viewModelScope.launch {
            if (generation == operationGeneration) setBusy(true)
            logger.log("bookmark_export", "started")
            try {
                when (/** serialized 在打开输出流前完成全部条数、单项与字节预检。 */ val serialized = exportSerializer.serialize(snapshot)) {
                    is BookmarkExportResult.Failure -> {
                        logger.log("bookmark_export", "failed")
                        if (generation == operationGeneration) setFailure(LocalDataFailure.EXPORT_FAILED)
                    }
                    is BookmarkExportResult.Success -> {
                        documentGateway.write(uri, serialized.bytes)
                        logger.log("bookmark_export", "completed", snapshot.size)
                    }
                }
            /** 离页取消时记录固定阶段并继续传播。 */
            } catch (cancellation: CancellationException) {
                logger.log("bookmark_export", "cancelled")
                throw cancellation
            /** 输出流异常统一映射为固定导出失败。 */
            } catch (exception: Exception) {
                logger.log("bookmark_export", "failed")
                if (generation == operationGeneration) setFailure(LocalDataFailure.EXPORT_FAILED)
            } finally {
                if (generation == operationGeneration) {
                    setBusy(false)
                    operationJob = null
                }
            }
        }
    }

    /** 激活后开始 Room Flow；重复 START 不创建第二个收集者。 */
    private fun startCollecting() {
        if (collectionJob != null) return
        /** generation 固定本次 Room 收集所有权。 */
        val generation = ++collectionGeneration
        collectionJob = viewModelScope.launch {
            try {
                repository.observeBookmarks().collect { /** bookmarks 是当前完整阶段性快照。 */ bookmarks ->
                    if (generation == collectionGeneration) mutableUiState.update { /** state 是写入新快照前的页面状态。 */ state ->
                        state.copy(bookmarks = bookmarks, snapshotReady = true, failure = null)
                    }
                }
            /** 离开书签目的地时保持收集任务的结构化取消。 */
            } catch (cancellation: CancellationException) {
                throw cancellation
            /** Room Flow 异常只进入固定读取失败状态。 */
            } catch (exception: Exception) {
                if (generation == collectionGeneration) setFailure(LocalDataFailure.READ_FAILED)
            } finally {
                if (generation == collectionGeneration) collectionJob = null
            }
        }
    }

    /** 启动统一书签写操作并收敛固定错误。 */
    private fun launchWrite(operation: suspend () -> Int) {
        if (!canStartOperation()) return
        /** generation 固定本次数据库写入所有权。 */
        val generation = ++operationGeneration
        operationJob = viewModelScope.launch {
            if (generation == operationGeneration) setBusy(true)
            try {
                operation()
            /** 离页时保持书签写任务的结构化取消。 */
            } catch (cancellation: CancellationException) {
                throw cancellation
            /** 数据库异常只进入固定写入失败状态。 */
            } catch (exception: Exception) {
                if (generation == operationGeneration) setFailure(LocalDataFailure.WRITE_FAILED)
            } finally {
                if (generation == operationGeneration) {
                    setBusy(false)
                    operationJob = null
                }
            }
        }
    }

    /** 只有可见且无 IO 时才允许启动不可重入操作。 */
    private fun canStartOperation(): Boolean = mutableUiState.value.isScreenActive && operationJob?.isActive != true

    /** 更新操作忙碌状态且不保存 URI。 */
    private fun setBusy(busy: Boolean) {
        mutableUiState.update { /** state 是忙碌状态变化前的页面快照。 */ state ->
            state.copy(isBusy = busy, failure = if (busy) null else state.failure)
        }
    }

    /** 更新固定失败并清除过期成功报告。 */
    private fun setFailure(failure: LocalDataFailure) {
        mutableUiState.update { /** state 是错误发生前的页面快照。 */ state -> state.copy(failure = failure, importReport = null) }
    }

    /** 离页取消 Room 收集和未完成 IO，并关闭确认弹窗。 */
    private fun stopWork() {
        collectionGeneration += 1
        collectionJob?.cancel(CancellationException("bookmarks_collection_stopped"))
        collectionJob = null
        operationGeneration += 1
        operationJob?.cancel(CancellationException("bookmarks_operation_stopped"))
        operationJob = null
        mutableUiState.update { /** state 是目的地停止前的页面快照。 */ state ->
            state.copy(isBusy = false, showClearConfirmation = false)
        }
    }
}

/** 搜索历史目的地作用域编排器；回放只返回查询，不在本页联网。 */
@HiltViewModel
internal class SearchHistoryViewModel @Inject constructor(
    /** 搜索历史 Room 领域边界。 */
    private val repository: TorrentSearchHistoryRepository,
) : ViewModel() {
    /** 页面唯一可变状态。 */
    private val mutableUiState = MutableStateFlow(SearchHistoryUiState())
    /** Compose 生命周期感知收集的只读状态。 */
    val uiState: StateFlow<SearchHistoryUiState> = mutableUiState.asStateFlow()
    /** 可见期间的 Room 收集任务。 */
    private var collectionJob: Job? = null
    /** 历史收集代次阻止旧任务释放新任务所有权。 */
    private var collectionGeneration = 0L
    /** 删除或清空任务。 */
    private var operationJob: Job? = null
    /** 历史写入代次阻止旧任务结束覆盖新任务状态。 */
    private var operationGeneration = 0L

    /** 激活才订阅历史 Flow，离页取消收集与写入。 */
    fun setScreenActive(active: Boolean) {
        mutableUiState.update { /** state 是生命周期切换前快照。 */ state -> state.copy(isScreenActive = active) }
        if (active) startCollecting() else stopWork()
    }

    /** 请求清空确认。 */
    fun requestClear() {
        if (mutableUiState.value.items.isNotEmpty()) mutableUiState.update { /** state 是确认前快照。 */ state -> state.copy(showClearConfirmation = true) }
    }

    /** 关闭清空确认。 */
    fun dismissClear() {
        mutableUiState.update { /** state 是关闭确认前快照。 */ state -> state.copy(showClearConfirmation = false) }
    }

    /** 确认后清空全部历史。 */
    fun confirmClear() {
        dismissClear()
        launchWrite { repository.clear() }
    }

    /** 删除单条规范化历史。 */
    fun remove(query: String) {
        launchWrite { if (repository.deleteQuery(query)) 1 else 0 }
    }

    /** 可见时启动唯一 Flow 收集。 */
    private fun startCollecting() {
        if (collectionJob != null) return
        /** generation 固定本次历史收集所有权。 */
        val generation = ++collectionGeneration
        collectionJob = viewModelScope.launch {
            try {
                repository.observeHistory().collect { /** items 是当前历史快照。 */ items ->
                    if (generation == collectionGeneration) mutableUiState.update { /** state 是写入列表前快照。 */ state -> state.copy(items = items, failure = null) }
                }
            /** 离开历史目的地时保持收集任务的结构化取消。 */
            } catch (cancellation: CancellationException) {
                throw cancellation
            /** Room Flow 异常只进入固定读取失败状态。 */
            } catch (exception: Exception) {
                if (generation == collectionGeneration) mutableUiState.update { /** state 是读取失败前快照。 */ state -> state.copy(failure = LocalDataFailure.READ_FAILED) }
            } finally {
                if (generation == collectionGeneration) collectionJob = null
            }
        }
    }

    /** 启动不可重入历史写入并收敛固定失败。 */
    private fun launchWrite(operation: suspend () -> Int) {
        if (!mutableUiState.value.isScreenActive || operationJob?.isActive == true) return
        /** generation 固定本次历史写入所有权。 */
        val generation = ++operationGeneration
        operationJob = viewModelScope.launch {
            if (generation == operationGeneration) mutableUiState.update { /** state 是写入开始前快照。 */ state -> state.copy(isBusy = true) }
            try {
                operation()
            /** 离页时保持历史写任务的结构化取消。 */
            } catch (cancellation: CancellationException) {
                throw cancellation
            /** 数据库异常只进入固定写入失败状态。 */
            } catch (exception: Exception) {
                if (generation == operationGeneration) mutableUiState.update { /** state 是写入失败前快照。 */ state -> state.copy(failure = LocalDataFailure.WRITE_FAILED) }
            } finally {
                if (generation == operationGeneration) {
                    mutableUiState.update { /** state 是写入结束前快照。 */ state -> state.copy(isBusy = false) }
                    operationJob = null
                }
            }
        }
    }

    /** 离页取消所有工作并关闭确认。 */
    private fun stopWork() {
        collectionGeneration += 1
        collectionJob?.cancel(CancellationException("history_collection_stopped"))
        collectionJob = null
        operationGeneration += 1
        operationJob?.cancel(CancellationException("history_operation_stopped"))
        operationJob = null
        mutableUiState.update { /** state 是离页前快照。 */ state -> state.copy(isBusy = false, showClearConfirmation = false) }
    }
}

/** 已查看目的地作用域编排器，只展示安全缩略身份与时间。 */
@HiltViewModel
internal class ViewedTorrentsViewModel @Inject constructor(
    /** 已查看 Room 领域边界。 */
    private val repository: ViewedTorrentRepository,
) : ViewModel() {
    /** 页面唯一可变状态。 */
    private val mutableUiState = MutableStateFlow(ViewedUiState())
    /** Compose 生命周期感知收集的只读状态。 */
    val uiState: StateFlow<ViewedUiState> = mutableUiState.asStateFlow()
    /** 可见期间 Room 收集任务。 */
    private var collectionJob: Job? = null
    /** 已查看收集代次阻止旧任务释放新任务所有权。 */
    private var collectionGeneration = 0L
    /** 清空任务。 */
    private var operationJob: Job? = null
    /** 已查看操作代次阻止旧任务结束覆盖新状态。 */
    private var operationGeneration = 0L

    /** 激活才订阅已查看 Flow，离页立即取消。 */
    fun setScreenActive(active: Boolean) {
        mutableUiState.update { /** state 是生命周期切换前快照。 */ state -> state.copy(isScreenActive = active) }
        if (active) startCollecting() else stopWork()
    }

    /** 请求清空确认。 */
    fun requestClear() {
        if (mutableUiState.value.items.isNotEmpty()) mutableUiState.update { /** state 是确认前快照。 */ state -> state.copy(showClearConfirmation = true) }
    }

    /** 关闭清空确认。 */
    fun dismissClear() {
        mutableUiState.update { /** state 是关闭确认前快照。 */ state -> state.copy(showClearConfirmation = false) }
    }

    /** 用户确认后清空全部已查看。 */
    fun confirmClear() {
        dismissClear()
        if (!mutableUiState.value.isScreenActive || operationJob?.isActive == true) return
        /** generation 固定本次清空操作所有权。 */
        val generation = ++operationGeneration
        operationJob = viewModelScope.launch {
            if (generation == operationGeneration) mutableUiState.update { /** state 是清空开始前快照。 */ state -> state.copy(isBusy = true) }
            try {
                repository.clear()
            /** 离页时保持已查看清空任务的结构化取消。 */
            } catch (cancellation: CancellationException) {
                throw cancellation
            /** 数据库异常只进入固定写入失败状态。 */
            } catch (exception: Exception) {
                if (generation == operationGeneration) mutableUiState.update { /** state 是清空失败前快照。 */ state -> state.copy(failure = LocalDataFailure.WRITE_FAILED) }
            } finally {
                if (generation == operationGeneration) {
                    mutableUiState.update { /** state 是清空结束前快照。 */ state -> state.copy(isBusy = false) }
                    operationJob = null
                }
            }
        }
    }

    /** 可见时启动唯一已查看 Flow 收集。 */
    private fun startCollecting() {
        if (collectionJob != null) return
        /** generation 固定本次已查看收集所有权。 */
        val generation = ++collectionGeneration
        collectionJob = viewModelScope.launch {
            try {
                repository.observeViewed().collect { /** items 是当前安全身份快照。 */ items ->
                    if (generation == collectionGeneration) mutableUiState.update { /** state 是写入列表前快照。 */ state -> state.copy(items = items, failure = null) }
                }
            /** 离开已查看目的地时保持收集任务的结构化取消。 */
            } catch (cancellation: CancellationException) {
                throw cancellation
            /** Room Flow 异常只进入固定读取失败状态。 */
            } catch (exception: Exception) {
                if (generation == collectionGeneration) mutableUiState.update { /** state 是读取失败前快照。 */ state -> state.copy(failure = LocalDataFailure.READ_FAILED) }
            } finally {
                if (generation == collectionGeneration) collectionJob = null
            }
        }
    }

    /** 离页取消收集和清空任务并关闭确认。 */
    private fun stopWork() {
        collectionGeneration += 1
        collectionJob?.cancel(CancellationException("viewed_collection_stopped"))
        collectionJob = null
        operationGeneration += 1
        operationJob?.cancel(CancellationException("viewed_operation_stopped"))
        operationJob = null
        mutableUiState.update { /** state 是离页前快照。 */ state -> state.copy(isBusy = false, showClearConfirmation = false) }
    }
}
