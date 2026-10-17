package com.cla.clip.master.ui.page.image

import androidx.compose.runtime.mutableStateSetOf

/** 页面之外持有候选选择；重扫和配置恢复保留取消记录，显式重新提取才重置。 */
internal class ImageCandidateSelection {
    /** 当前可操作的候选身份，URL 变体共享同一个稳定 key。 */
    private var currentKeys: Set<String> = emptySet()
    /** 用户仍然保留的候选，由工具栏和预览共同读取。 */
    val selectedKeys = mutableStateSetOf<String>()
    /** 用户取消的候选，后续扫描不能自动重新选中。 */
    private val excludedKeys = mutableSetOf<String>()

    /** 新候选默认选中，现有身份保留选择；仅在候选快照发布时调用。 */
    fun update(keys: List<String>) {
        currentKeys = keys.toSet()
        selectedKeys.retainAll(currentKeys)
        currentKeys.filterNot { it in excludedKeys }.forEach { selectedKeys.add(it) }
    }

    /** 单项操作以当前有效身份为边界，不接受已经移除的预览项。 */
    fun setSelected(key: String, selected: Boolean) {
        if (key !in currentKeys) return
        if (selected) { excludedKeys.remove(key); selectedKeys.add(key) }
        else { excludedKeys.add(key); selectedKeys.remove(key) }
    }

    /** 全选或取消全部只操作当前候选，之后新出现的图片仍默认选中。 */
    fun selectAll(selected: Boolean) {
        currentKeys.forEach { setSelected(it, selected) }
    }

    /** 显式重新提取才清空选择记忆，不由页面重建调用。 */
    fun reset() {
        currentKeys = emptySet()
        selectedKeys.clear()
        excludedKeys.clear()
    }
}
