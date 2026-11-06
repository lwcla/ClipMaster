package com.cla.clip.master.ui.page.image

import androidx.compose.runtime.mutableStateSetOf
import com.cla.clip.master.ui.widget.image.ImageThumbnailLoadEvent
import com.cla.clip.master.ui.widget.image.ImageThumbnailLoadPhase

/** 页面主线程持有缩略图失败证据；不加载图片、不修改选择、不持久化请求上下文。 */
internal class ImageCandidateLoadTracker {
    /** 仅显式重新提取才改变，普通前后台探测轮次不清除失败记忆。 */
    private var extractionId: Int? = null
    /** 当前有效候选的请求版本；含敏感上下文，禁止输出或序列化。 */
    private var versions: Map<String, List<String?>> = emptyMap()
    /** 同一候选只允许最新尝试提交终态，身份按引用比较。 */
    private val attempts = mutableMapOf<String, Any>()
    /** 可观察的失败集合供工具栏派生可用性；网格离屏不清除。 */
    val failedKeys = mutableStateSetOf<String>()

    /** 候选发布时同步；移除、请求升级或新提取清理旧证据，排序变化不影响。 */
    fun update(extractionId: Int, versions: Map<String, List<String?>>) {
        if (this.extractionId != extractionId) {
            attempts.clear()
            failedKeys.clear()
        } else {
            // 只保留仍存在且请求内容完全相同的候选。
            val unchanged = versions.keys.filterTo(mutableSetOf()) { this.versions[it] == versions[it] }
            attempts.keys.retainAll(unchanged)
            failedKeys.retainAll(unchanged)
        }
        this.extractionId = extractionId
        // 复制列表，防止调用方在异步回调前修改版本内容。
        this.versions = versions.mapValues { it.value.toList() }
    }

    /** 仅接受当前提取和请求版本的事件；旧尝试终态与未知候选静默忽略。 */
    fun onLoadEvent(extractionId: Int, key: String, version: List<String?>, event: ImageThumbnailLoadEvent) {
        if (this.extractionId != extractionId || versions[key] != version) return
        if (event.phase == ImageThumbnailLoadPhase.Loading) {
            attempts[key] = event.attempt
            failedKeys.remove(key)
        } else if (attempts[key] === event.attempt) {
            // 一次尝试只允许一个终态，迟到重复事件不能反转已确认结果。
            attempts.remove(key)
            if (event.phase == ImageThumbnailLoadPhase.Failed) failedKeys.add(key)
            else failedKeys.remove(key)
        }
    }
}
