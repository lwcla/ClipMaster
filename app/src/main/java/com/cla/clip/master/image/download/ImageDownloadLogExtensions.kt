package com.cla.clip.master.image.download

/**
 * 图片下载日志辅助扩展。
 *
 * 这里只暴露不会泄露隐私的摘要能力，例如 Cookie 是否存在和长度；真实 Cookie 不能进入日志，避免泄露登录态。
 */

/** 日志中只记录 Cookie 是否存在和长度，不输出真实 Cookie 内容，避免泄露登录态。 */
fun String?.cookieLogSummary(): String {
    return if (isNullOrBlank()) "empty" else "present(length=$length)"
}
