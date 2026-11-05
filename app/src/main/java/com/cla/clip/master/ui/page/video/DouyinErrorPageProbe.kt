package com.cla.clip.master.ui.page.video

import android.webkit.WebView
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/** 有界读取当前文档错误状态；仅在两次稳定快照后确认不可继续。 */
internal object DouyinErrorPageProbe {
    /** 只观察当前文档十秒；自动提取仍受页面原有总预算约束。 */
    private const val DOCUMENT_BUDGET_MS = 10_000L
    /** 两次肯定快照至少隔半秒，避免短暂占位立即结束提取。 */
    private const val SNAPSHOT_INTERVAL_MS = 500L
    /** JS 无回调不能让一次读取永久占用文档任务。 */
    private const val SNAPSHOT_TIMEOUT_MS = 1_000L

    /** 调用方负责文档切换和生命周期取消，未知结果不能直接失败。 */
    suspend fun awaitError(view: WebView): Boolean {
        // URL 只作当前文档身份比较，绝不写入结果或日志。
        val documentUrl = view.url
        if (!isDouyinSharePage(documentUrl)) return false
        return withTimeoutOrNull(DOCUMENT_BUDGET_MS) {
            // 任一否定/未知快照清空前次确认，要求连续稳定错误。
            var previousMatched = false
            while (currentCoroutineContext().isActive) {
                if (view.url != documentUrl) return@withTimeoutOrNull false
                // 读取后再次检查地址，避免 JS 执行期间跨页面。
                val matched = readSnapshot(view) == true
                if (view.url != documentUrl) return@withTimeoutOrNull false
                if (matched && previousMatched) return@withTimeoutOrNull true
                previousMatched = matched
                delay(SNAPSHOT_INTERVAL_MS)
            }
            false
        } ?: false
    }

    /** 仅在主线程读取布尔快照；取消后迟到的 JS 结果不会恢复协程。 */
    private suspend fun readSnapshot(view: WebView): Boolean? = withTimeoutOrNull(SNAPSHOT_TIMEOUT_MS) {
        suspendCancellableCoroutine { continuation ->
            try {
                view.evaluateJavascript(DOUYIN_ERROR_PAGE_JS) { result ->
                    if (continuation.isActive) continuation.resume(when (result) {
                        "true" -> true
                        "false" -> false
                        else -> null
                    })
                }
            } catch (_: RuntimeException) {
                // 平台已释放等同步异常只代表无法判断；不覆盖原超时或外部取消。
                if (continuation.isActive) continuation.resume(null)
            }
        }
    }
}
