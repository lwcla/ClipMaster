package com.cla.clip.feature.torrent.search.security

import java.net.URI
import java.net.URISyntaxException

/** 统一约束详情富文本、分享和下载交给系统处理的外部链接 scheme。 */
internal object ExternalLinkPolicy {
    /** 经安全审查允许离开应用的 scheme 集合，比较时忽略大小写。 */
    private val ALLOWED_SCHEMES = setOf("http", "https", "magnet")

    /** 判断不可信文本是否为语法完整且 scheme 在白名单中的绝对 URI。 */
    fun isAllowed(
        /** 来自 Provider 内容或用户导入数据的候选外部链接。 */
        rawLink: String?,
    ): Boolean {
        /** normalizedCandidate 去除边界空白；空值不会交给 URI 解析。 */
        val normalizedCandidate = rawLink?.trim().orEmpty()
        if (normalizedCandidate.isEmpty()) return false
        return try {
            /** scheme 只取 URI 解析结果，不从字符串前缀猜测。 */
            val scheme = URI(normalizedCandidate).scheme?.lowercase()
            scheme != null && scheme in ALLOWED_SCHEMES
        /** URI 语法失败统一返回拒绝，不泄露候选原文或解析异常。 */
        } catch (
            /** invalidUri 表示候选不满足 URI 语法，拒绝且不记录原文。 */
            invalidUri: URISyntaxException,
        ) {
            false
        }
    }
}
