package com.cla.clip.master.ui.page.recycle

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.cachedIn
import androidx.paging.map
import com.cla.clip.base.general.config.clip.ClipSettingsRepository
import com.cla.clip.base.general.entity.ClipShowEntity
import com.cla.clip.base.general.entity.toUi
import com.cla.clip.base.general.repository.ClipRepository
import com.cla.clip.base.general.utils.toast
import com.cla.clip.base.general.utils.logE
import com.cla.clip.master.work.BackupAutoScheduler
import dagger.Lazy
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * 回收站页面 ViewModel。
 *
 * 负责分页读取回收站剪贴数据、管理多选状态、还原/彻底删除/清空数据，以及保存保留天数后立即执行一次过期清理。
 */
@HiltViewModel
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class RecycleBinVm @Inject constructor(
    /** 应用级 Context，仅用于读取字符串资源和展示轻量 Toast，不持有页面实例。 */
    @param:ApplicationContext private val appContext: Context,

    /** 剪贴数据仓库，提供回收站分页、恢复、永久删除和过期清理能力。 */
    private val clipRepository: Lazy<ClipRepository>,

    /** 保留天数由统一剪贴设置仓库持久化，清理前须等待提交。 */
    private val clipSettingsRepository: ClipSettingsRepository,
) : ViewModel() {

    /**
     * 回收站分页数据。
     *
     * 页面可见时才收集，排序由 DAO 保证为删除时间倒序；在 ViewModel 生命周期内缓存，减少重组时重复创建查询。
     */
    val pagedClips = Pager(
        config = PagingConfig(
            pageSize = 20,
            prefetchDistance = 5,
            enablePlaceholders = false
        )
    ) {
        clipRepository.get().loadRecycleBinClips()
    }.flow.map { pagingData -> pagingData.map { clipDetail -> clipDetail.toUi() } }.cachedIn(
        CoroutineScope(viewModelScope.coroutineContext + Dispatchers.IO)
    )

    /** 当前选中的回收站记录 id 集合；只保存 id，分页刷新后可自动忽略已不存在数据。 */
    private val _selectedIds = MutableStateFlow<Set<Long>>(emptySet())

    /** 页面订阅的选中 id 集合。 */
    val selectedIds: StateFlow<Set<Long>> = _selectedIds.asStateFlow()

    /** 是否处于多选模式；和选中集合分离，避免最后一条被反选时被动退出多选。 */
    private val _selectionMode = MutableStateFlow(false)

    /** 页面订阅的多选模式状态；用户需通过返回或操作完成主动退出。 */
    val selectionMode: StateFlow<Boolean> = _selectionMode.asStateFlow()

    /** 设置读取失败后的重试序号，不触发不必要的 Room 分页重建。 */
    private val settingsReload = MutableStateFlow(0L)

    /** 设置读取失败标记，页面据此展示重试入口。 */
    private val _settingsLoadFailed = MutableStateFlow(false)

    /** 页面只读的设置读取失败状态。 */
    val settingsLoadFailed = _settingsLoadFailed.asStateFlow()

    /** 页面订阅已提交保留天数；null 时设置弹层不可打开。 */
    val retentionDays = settingsReload.flatMapLatest {
        clipSettingsRepository.data.map { settings -> settings.recycleBinRetentionDays }
            .onEach { _settingsLoadFailed.value = false }
            .catch { error ->
                if (error is CancellationException) throw error
                logE("RecycleBinVm", error) { "读取回收站保留天数失败 reasonCode=settings_read_failed" }
                _settingsLoadFailed.value = true
            }
    }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null as Int?)

    /** 用户点击读取错误入口后重新收集设置，不改变清理任务。 */
    fun retrySettingsLoad() {
        // 旧天数继续不可编辑，成功读取新快照后才解除门禁。
        settingsReload.update { sequence -> sequence + 1L }
    }

    /** 长按进入多选并选中当前记录。 */
    fun enterSelection(clip: ClipShowEntity) {
        _selectionMode.value = true
        _selectedIds.update { it + clip.id }
    }

    /** 普通点击在多选模式下切换选中；非多选模式的点击由页面弹出还原确认。 */
    fun toggleSelection(clip: ClipShowEntity) {
        if (!_selectionMode.value) return
        _selectedIds.update { ids ->
            if (clip.id in ids) ids - clip.id else ids + clip.id
        }
    }

    /** 退出多选态并清空选择，返回键和删除完成后都会调用。 */
    fun clearSelection() {
        _selectionMode.value = false
        _selectedIds.value = emptySet()
    }

    /** 还原单条回收站记录；只清空 deletedAt，不改变原折叠、折叠时间、置顶和剪贴时间。 */
    fun restoreClip(clip: ClipShowEntity) {
        viewModelScope.launch(Dispatchers.IO) {
            val count = clipRepository.get().restoreClipsFromRecycleBin(setOf(clip.id))
            if (count > 0) {
                appContext.toast(com.cla.clip.base.general.R.string.base_general_recycle_bin_restore_done)
            }
        }
    }

    /** 彻底删除单条回收站记录；已不存在时按幂等成功处理，不向用户展示错误。 */
    fun deleteClipPermanently(clip: ClipShowEntity) {
        deleteIdsPermanently(ids = setOf(clip.id), exitSelectionAfterDelete = false)
    }

    /** 彻底删除当前多选记录，部分 id 已不存在时自动忽略并退出多选。 */
    fun deleteSelectedPermanently() {
        val ids = _selectedIds.value
        if (ids.isEmpty()) return
        deleteIdsPermanently(ids = ids, exitSelectionAfterDelete = true)
    }

    /** 彻底清空回收站；使用条件 SQL 删除，不先读取全部 id。 */
    fun clearRecycleBinPermanently() {
        viewModelScope.launch(Dispatchers.IO) {
            val count = clipRepository.get().clearRecycleBinPermanently()
            clearSelection()
            appContext.toast(appContext.getString(com.cla.clip.base.general.R.string.base_general_recycle_bin_clear_done, count))
        }
    }

    /** 保存保留天数并立即清理一次过期数据；后台 Worker 仍会按每日周期继续清理。 */
    fun saveRetentionDays(days: Int, onSaved: () -> Unit) {
        /** 与仓库统一的保留范围，避免 UI 草稿与实际清理天数分叉。 */
        val safeDays = days.coerceIn(
            ClipSettingsRepository.MIN_RECYCLE_BIN_RETENTION_DAYS,
            ClipSettingsRepository.MAX_RECYCLE_BIN_RETENTION_DAYS
        )
        viewModelScope.launch {
            try {
                clipSettingsRepository.setRecycleBinRetentionDays(safeDays)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                logE("RecycleBinVm", error) { "保存保留天数失败 reasonCode=settings_write_failed" }
                appContext.toast(com.cla.clip.master.R.string.host_settings_save_failed_retry)
                return@launch
            }
            onSaved()
            try {
                BackupAutoScheduler.scheduleAfterCommittedChange(appContext)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                // 保留天数已提交；调度失败不会撤销用户设置。
                logE("RecycleBinVm", error) { "保留天数已保存但备份调度失败 reasonCode=backup_schedule_failed" }
            }
            try {
                /** 保存完成后按同一天数立即清理，避免未提交草稿触发删除。 */
                val count = clipRepository.get().cleanupExpiredRecycleBinClips(safeDays)
                appContext.toast(appContext.getString(com.cla.clip.base.general.R.string.base_general_recycle_bin_cleanup_done, count))
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                logE("RecycleBinVm", error) { "保留天数已保存但即时清理失败 reasonCode=recycle_cleanup_failed" }
                appContext.toast(com.cla.clip.master.R.string.host_recycle_cleanup_failed_retry)
            }
        }
    }

    /** 执行永久删除并统一处理选择集合和 Toast；单条右滑删除不会强制退出用户已经进入的多选模式。 */
    private fun deleteIdsPermanently(ids: Set<Long>, exitSelectionAfterDelete: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            val count = clipRepository.get().deleteClipsPermanently(ids)
            if (exitSelectionAfterDelete) {
                clearSelection()
            } else {
                _selectedIds.update { selectedIds -> selectedIds - ids }
            }
            if (count > 0) {
                appContext.toast(appContext.getString(com.cla.clip.base.general.R.string.base_general_deleted_count_permanently, count))
            }
        }
    }
}
