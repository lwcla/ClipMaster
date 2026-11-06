package com.cla.clip.master.ui.page.image

import androidx.compose.runtime.mutableStateSetOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** 页面之外持有候选选择；重扫和配置恢复保留取消记录，显式重新提取才重置。 */
internal class ImageCandidateSelection {
    /** 当前可操作的候选身份，URL 变体共享同一个稳定 key。 */
    private var currentKeys by mutableStateOf<Set<String>>(emptySet())
    /** 用户仍然保留的候选，由工具栏和预览共同读取。 */
    val selectedKeys = mutableStateSetOf<String>()
    /** 用户取消的候选，后续扫描不能自动重新选中。 */
    private val excludedKeys = mutableSetOf<String>()
    /** 当前有效失败事实由 tracker 同步，提交中仍更新但暂不影响选择。 */
    private var failedKeys by mutableStateOf<Set<String>>(emptySet())
    /** 只在本轮提取有效；用户主动选回已知失败项才产生例外。 */
    private val manualOverrides = mutableSetOf<String>()
    /** 新提取默认关闭，不写入设置，不因全选或组合重建改变。 */
    var filterFailedEnabled by mutableStateOf(false)
        private set
    /** 提交快照及已提交批次期间拒绝任何选择修改。 */
    private var frozen = false

    /** 批量全选的精确目标，不包含用户手动保留的失败例外。 */
    private val selectAllTarget: Set<String>
        get() = if (filterFailedEnabled) currentKeys - failedKeys else currentKeys

    /** 低敏诊断只输出例外数量，不输出候选身份。 */
    val manualOverrideCount: Int get() = manualOverrides.size

    /** 非空目标与实际选择完全相等才显示取消全选，失败例外会使按钮回到全选。 */
    val isAllSelected: Boolean get() = selectAllTarget.isNotEmpty() && selectedKeys.toSet() == selectAllTarget

    /** 空目标仍有手动选择时允许全选清除例外，避免全部失败时无法归一化。 */
    val canSelectAll: Boolean get() = selectAllTarget.isNotEmpty() || selectedKeys.isNotEmpty()

    /** 开启立即应用当前失败；关闭不恢复选择，再次开启清除历史手动例外。 */
    fun setFilterFailed(enabled: Boolean) {
        if (frozen || filterFailedEnabled == enabled) return
        filterFailedEnabled = enabled
        manualOverrides.clear()
        applyFailureFilter()
    }

    /** 有效加载事件与候选版本变更后同步，不在重组过程中反复改动用户选择。 */
    fun updateFailures(keys: Set<String>) {
        failedKeys = keys.intersect(currentKeys)
        applyFailureFilter()
    }

    /** 提交失败解冻时应用积累的有效失败事实，尊重已有手动例外。 */
    fun setFrozen(value: Boolean) {
        frozen = value
        if (!value) applyFailureFilter()
    }

    /** 自动取消写入排除记忆，成功、重扫或版本更新都不能自行恢复选择。 */
    private fun applyFailureFilter() {
        if (frozen || !filterFailedEnabled) return
        // 只取消当前失败且没有用户手动保留的图片。
        val rejected = failedKeys - manualOverrides
        excludedKeys.addAll(rejected)
        selectedKeys.removeAll(rejected)
    }

    /** 批量取消当前仍选中的候选，返回实际移除数；沿用手动取消记忆以抵抗重扫。 */
    fun deselect(keys: Set<String>): Int {
        if (frozen) return 0
        // 固定本次有效交集，忽略已取消、已移除或其他会话的身份。
        val removed = keys.intersect(selectedKeys).intersect(currentKeys)
        removed.forEach { setSelected(it, false) }
        return removed.size
    }

    /** 新候选默认选中，现有身份保留选择；仅在候选快照发布时调用。 */
    fun update(keys: List<String>) {
        currentKeys = keys.toSet()
        failedKeys = failedKeys.intersect(currentKeys)
        manualOverrides.retainAll(currentKeys)
        if (frozen) return
        selectedKeys.retainAll(currentKeys)
        currentKeys.filterNot { it in excludedKeys }.forEach { selectedKeys.add(it) }
        applyFailureFilter()
    }

    /** 单项操作以当前有效身份为边界，不接受已经移除的预览项。 */
    fun setSelected(key: String, selected: Boolean) {
        if (frozen || key !in currentKeys) return
        // 只有主动选中已知失败项才例外放行，普通全选不能生成例外。
        if (selected && filterFailedEnabled && key in failedKeys) manualOverrides.add(key)
        if (!selected) manualOverrides.remove(key)
        if (selected) { excludedKeys.remove(key); selectedKeys.add(key) }
        else { excludedKeys.add(key); selectedKeys.remove(key) }
    }

    /** 全选严格替换为目标集合并清理例外；取消全部不改变开关，新候选仍默认选中。 */
    fun selectAll(selected: Boolean) {
        if (frozen) return
        // 固定目标快照，避免逐项操作错误地生成手动失败例外。
        val target = if (selected) selectAllTarget else emptySet()
        manualOverrides.clear()
        excludedKeys.addAll(currentKeys - target)
        excludedKeys.removeAll(target)
        selectedKeys.clear()
        selectedKeys.addAll(target)
    }

    /** 显式重新提取才清空选择记忆，不由页面重建调用。 */
    fun reset() {
        currentKeys = emptySet()
        selectedKeys.clear()
        excludedKeys.clear()
        failedKeys = emptySet()
        manualOverrides.clear()
        filterFailedEnabled = false
        frozen = false
    }
}
