package com.cla.clip.master.ui.page.mine

import com.cla.clip.master.installedapps.InstalledAppInfo
import com.cla.clip.master.installedapps.InstalledAppReader
import com.cla.clip.master.installedapps.InstalledAppLoadResult
import com.cla.clip.master.installedapps.INSTALLED_APP_REASON_PACKAGE_SCAN_FAILED
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * 页面可见时才工作的安装扫描协调器，与设置提交互相独立。
 *
 * 主线程入口使用 Job 身份防止旧任务清理新状态，系统读取在 reader 的 IO 调度器中完成。
 * 应用快照只驻留当前页面，任何扫描失败都保留上次可用列表，不影响持久过滤名单。
 */
internal class ClipSourceBlockInstalledAppCoordinator(
    /** 页面任务 scope 与可替换系统读取器，均不包含过滤名单写入职责。 */
    private val scope: CoroutineScope,
    /** 系统读取与 IO 调度仍由原平台协作者负责。 */
    private val reader: InstalledAppReader,
    /** 低频阶段回调，由入口层统一诊断；禁止发送安装名单或图标。 */
    private val onDiagnostic: (String, InstalledAppListState) -> Unit = { _, _ -> },
) {
    /** 会话扫描状态及应用快照，只驻留当前 ViewModel 内存。 */
    private val mutableState = MutableStateFlow(InstalledAppListState())
    /** 最近成功扫描快照，扫描失败和取消不清空它。 */
    private val mutableApps = MutableStateFlow<List<InstalledAppInfo>>(emptyList())
    /** 提供给页面与候选构建的只读状态。 */
    val state = mutableState.asStateFlow()
    /** 候选构建只观察本会话成功应用列表。 */
    val apps = mutableApps.asStateFlow()

    /** 页面是否位于前台可见态，不以系统开关替代生命周期。 */
    private var visible = false
    /** 当前扫描 Job，替换或隐藏时取消；旧 Job 不能清理新任务状态。 */
    private var loadJob: Job? = null
    /** 上一次成功扫描的系统范围；范围变更取消后重新可见时需要补读。 */
    private var loadedSystemRange: Boolean? = null

    /** 同范围会话成功后不重复扫描；首次失败或未完成的新范围在再次可见时重试。 */
    fun onVisible() {
        visible = true
        /** 当前范围和会话成功标记共同决定首次按需读取，不增加时间节流。 */
        val current = mutableState.value
        if (!current.loading && (current.needsInitialLoad || loadedSystemRange != current.showSystemApps)) {
            startScan()
        }
    }

    /** 离开前台取消扫描并立即清理 loading，保留最后成功快照及查询条件。 */
    fun onHidden() {
        visible = false
        /** 先移除 Job 身份再取消，避免旧 finally 将之后的新任务错误清为 idle。 */
        val previousJob = loadJob
        loadJob = null
        previousJob?.cancel()
        mutableState.update { state -> state.copy(loading = false) }
        if (previousJob != null) onDiagnostic("cancelled", mutableState.value)
    }

    /** 用户下拉仅在可见且没有扫描时重读，短时间重复手势不会替换正在进行的任务。 */
    fun refresh() {
        if (visible && !mutableState.value.loading) startScan()
    }

    /** 系统范围变更必须替换旧扫描；不可见时只记目标，等恢复可见后才执行 IO。 */
    fun updateShowSystemApps(showSystemApps: Boolean) {
        if (mutableState.value.showSystemApps == showSystemApps) return
        mutableState.update { state -> state.copy(showSystemApps = showSystemApps) }
        if (visible) startScan()
    }

    /** 搜索仅过滤当前会话候选，既不扫描系统也不写过滤设置。 */
    fun updateSearchQuery(query: String) {
        mutableState.update { state -> state.copy(searchQuery = query) }
    }

    /** 固定本次范围并替换 Job；LAZY 保证任务开始前身份已经登记，便于取消/清理判定。 */
    private fun startScan() {
        if (!scope.isActive) return
        /** 本次读取的系统范围固定到任务，不受后续开关变化影响。 */
        val showSystemApps = mutableState.value.showSystemApps
        /** 替换前的扫描身份先摘除，避免其 finally 清理新的 loading。 */
        val previousJob = loadJob
        loadJob = null
        previousJob?.cancel()
        mutableState.update { state -> state.copy(loading = true, unavailableReasonCode = null) }
        onDiagnostic("start", mutableState.value)
        /** 新扫描任务先登记再启动，防 Unconfined 测试或立即完成任务造成身份遗漏。 */
        val nextJob = scope.launch(start = CoroutineStart.LAZY) {
            /** 当前任务身份仅用于清理判定，不是新增请求序号。 */
            val ownJob = currentCoroutineContext()[Job]
            try {
                /** reader 在 IO 线程完成系统调用及逐项取消检查。 */
                val result = reader.loadInstalledApps(showSystemApps)
                currentCoroutineContext().ensureActive()
                applyResult(result, showSystemApps)
            } catch (error: CancellationException) {
                // error 表示生命周期结束或范围替换，不转换成扫描失败。
                throw error
            } catch (error: Exception) {
                // 被取消的不可中断系统调用也可能迟到抛普通异常，不能让它覆盖新扫描状态。
                currentCoroutineContext().ensureActive()
                // 读取器契约外异常仍降级为低敏失败，保留上次成功内容，禁止输出 message。
                applyResult(InstalledAppLoadResult.Failed(INSTALLED_APP_REASON_PACKAGE_SCAN_FAILED, 0L, 0L), showSystemApps)
            } finally {
                if (loadJob === ownJob) {
                    loadJob = null
                    mutableState.update { state -> state.copy(loading = false) }
                }
            }
        }
        loadJob = nextJob
        nextJob.start()
    }

    /** 只处理当前仍有效任务的结果，失败不修改应用快照或上一次成功扫描范围。 */
    private fun applyResult(result: InstalledAppLoadResult, showSystemApps: Boolean) {
        when (result) {
            is InstalledAppLoadResult.Success -> {
                mutableApps.value = result.apps
                loadedSystemRange = showSystemApps
                mutableState.update { state ->
                    state.copy(
                        loading = false,
                        everLoaded = true,
                        unavailableReasonCode = null,
                        loadedAppCount = result.apps.size,
                        lastLoadSummary = InstalledAppLoadSummary(result.apps.size, result.elapsedMs, result.loadedAtMillis),
                    )
                }
                onDiagnostic("success", mutableState.value)
            }
            is InstalledAppLoadResult.Failed -> {
                mutableState.update { state ->
                    state.copy(loading = false, unavailableReasonCode = result.reasonCode)
                }
                onDiagnostic("failure", mutableState.value)
            }
        }
    }
}
