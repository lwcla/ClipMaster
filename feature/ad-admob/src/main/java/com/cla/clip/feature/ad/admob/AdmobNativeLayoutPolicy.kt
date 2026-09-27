package com.cla.clip.feature.ad.admob

/** 素材展示前的纯布局规则；未知媒体按视频保守处理，正文只完整展示或省略。 */
internal object AdmobNativeLayoutPolicy {
    /** 只有明确的非视频素材允许省略媒体区域。 */
    fun requiresMedia(hasVideo: Boolean?): Boolean = hasVideo != false

    /** 返回适合紧凑卡的完整短正文，否则返回空以移除该可选资产。 */
    fun compactBody(body: String?): String? = body?.takeIf {
        // 按码点计数以保留完整补充字符；多行内容不适合摘要卡。
        it.isNotBlank() && '\n' !in it && '\r' !in it && it.codePointCount(0, it.length) <= 40
    }
}
