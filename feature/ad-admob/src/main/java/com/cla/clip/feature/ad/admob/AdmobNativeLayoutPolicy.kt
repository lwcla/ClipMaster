package com.cla.clip.feature.ad.admob

/** 素材展示前的纯布局规则；保留必需内容，缺少选项内容时不制造额外空白。 */
internal object AdmobNativeLayoutPolicy {
    /** 只有明确的非视频素材允许省略媒体区域。 */
    fun requiresMedia(hasVideo: Boolean?): Boolean = hasVideo != false

    /** 选项有图标或非空白文字才预留位置；缺失信息按无内容处理，不依赖尚未布局的 View。 */
    fun requiresAdChoicesSpace(imageCount: Int?, text: String?): Boolean =
        (imageCount ?: 0) > 0 || !text.isNullOrBlank()

    /** 返回适合紧凑卡的完整短正文，否则返回空以移除该可选资产。 */
    fun compactBody(body: String?): String? = body?.takeIf {
        // 按码点计数以保留完整补充字符；多行内容不适合摘要卡。
        it.isNotBlank() && '\n' !in it && '\r' !in it && it.codePointCount(0, it.length) <= 40
    }
}
