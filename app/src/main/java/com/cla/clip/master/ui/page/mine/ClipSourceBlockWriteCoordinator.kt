package com.cla.clip.master.ui.page.mine

import com.cla.clip.base.general.config.ClipSourceBlockRules
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * 页面单项提交协调器，原子仓库是持久名单唯一来源。
 *
 * 所有入口由页面主线程调用；同步占用当前包名防重复点击，不同包名由仓库串行事务合并。
 * 已接受写入不依赖扫描生命周期，返回等待只监听这些任务，不持有导航或 Compose。
 */
internal class ClipSourceBlockWriteCoordinator(
    /** 页面任务作用域，已接受写入与安装扫描独立。 */
    private val scope: CoroutineScope,
    /** 仓库最新持久名单读取，用于识别无变化与拒绝。 */
    private val readPackages: suspend () -> Set<String>,
    /** 原子增删仓库边界，false 表示未发生真实变化。 */
    private val addPackage: suspend (String) -> Boolean,
    /** 移除同样原子读取最新名单，容量已满也允许取消。 */
    private val removePackage: suspend (String) -> Boolean,
    /** 提交失败回调不包含任何额外备份副作用。 */
    private val onFailure: (ClipSourceBlockWriteFailure, Throwable?) -> Unit = { _, _ -> },
    /** 阶段、会话操作编号及增删目标，仅包含低敏诊断字段，不发送包名。 */
    private val onDiagnostic: (String, Long, Boolean) -> Unit = { _, _, _ -> },
) {
    /** 单项提交状态，不保存过滤草稿。 */
    private val mutableState = MutableStateFlow(ClipSourceBlockWriteState())
    /** 页面只读的提交及返回状态。 */
    val state = mutableState.asStateFlow()
    /** 会话内操作序号只关联开始和结果，不作为设置、外部协议或扫描请求编号。 */
    private var nextOperationId = 0L

    /** 接受明确目标状态；false 表示重复提交、返回等待或非法输入，没有启动额外写入。 */
    fun setBlocked(packageName: String, blocked: Boolean): Boolean {
        /** 当前提交状态，在 launch 前检查与占用，避免同一事件循环内重复接收。 */
        val currentState = mutableState.value
        if (!scope.isActive || currentState.backRequested || currentState.navigateBack) {
            return false
        }
        /** 来源包名必须满足既有共享规范化；失败不触碰持久名单。 */
        val normalizedPackage = ClipSourceBlockRules.normalizeSinglePackage(packageName)
        if (normalizedPackage == null) {
            reportFailure(ClipSourceBlockWriteFailure.InvalidPackage, null)
            return false
        }
        // pending 身份必须用规范化后的包名，首尾空白不能绕过同项重复保护。
        if (normalizedPackage in currentState.pendingPackages) return false
        /** 本次接受的操作序号，跨阶段保持一致但不暴露应用身份。 */
        val operationId = ++nextOperationId
        mutableState.update { state ->
            // state 的 pending 集合按完整包名占用，lastFailure 清除上一次已显示的失败。
            state.copy(pendingPackages = state.pendingPackages + normalizedPackage, lastFailure = null)
        }
        onDiagnostic("start", operationId, blocked)
        // 当前包名与目标状态固定到本次任务；不读取候选列表生成整单覆盖。
        scope.launch {
            try {
                /** 仓库真实变化结果，成功返回前名单已在同事务提交。 */
                val changed = if (blocked) addPackage(normalizedPackage) else removePackage(normalizedPackage)
                if (!changed) {
                    /** false 可能是幂等成功，必须读取最新真实结果才能判断拒绝。 */
                    val committedPackages = readPackages()
                    if ((normalizedPackage in committedPackages) != blocked) {
                        onDiagnostic("rejected", operationId, blocked)
                        reportFailure(
                            if (blocked && committedPackages.size >= ClipSourceBlockRules.MAX_PACKAGE_COUNT) {
                                ClipSourceBlockWriteFailure.Capacity
                            } else {
                                ClipSourceBlockWriteFailure.Storage
                            },
                            null,
                        )
                    } else {
                        onDiagnostic("unchanged", operationId, blocked)
                    }
                } else {
                    onDiagnostic("changed", operationId, blocked)
                }
            } catch (error: CancellationException) {
                // 作用域结束不代表事务成功；禁止因取消而产生自动返回许可。
                mutableState.update { state -> state.copy(backRequested = false, navigateBack = false) }
                onDiagnostic("cancelled", operationId, blocked)
                throw error
            } catch (error: Exception) {
                // error 不作为用户文案，交给调用方以低敏原因分类诊断。
                onDiagnostic("failure", operationId, blocked)
                reportFailure(ClipSourceBlockWriteFailure.Storage, error)
            } finally {
                mutableState.update { state ->
                    /** 完成后的其他 pending 项仍需等待，只有所有项结束才可以自动返回。 */
                    val remainingPackages = state.pendingPackages - normalizedPackage
                    state.copy(
                        pendingPackages = remainingPackages,
                        navigateBack = state.backRequested && remainingPackages.isEmpty(),
                    )
                }
            }
        }
        return true
    }

    /** 无写入直接返回，否则锁定新操作并等待已接受任务；不弹保存确认。 */
    fun requestBack(): Boolean {
        if (mutableState.value.pendingPackages.isEmpty()) return true
        mutableState.update { state -> state.copy(backRequested = true) }
        return false
    }

    /** 记录业务失败并撤销等待中的自动返回，允许错误显示后再次退出或重试。 */
    private fun reportFailure(reason: ClipSourceBlockWriteFailure, error: Throwable?) {
        mutableState.update { state -> state.copy(lastFailure = reason, backRequested = false, navigateBack = false) }
        onFailure(reason, error)
    }
}
