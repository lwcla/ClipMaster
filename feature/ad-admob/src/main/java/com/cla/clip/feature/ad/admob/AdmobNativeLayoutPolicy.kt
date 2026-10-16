package com.cla.clip.feature.ad.admob

/** 素材展示前的纯布局规则；保留必需内容，缺少选项内容时不制造额外空白。 */
internal object AdmobNativeLayoutPolicy {
    /** 所有尺寸均为像素；只有可读宽度和完整文字高度同时满足时横排，否则保守纵排。 */
    fun mediaArrangement(
        /** 扣除媒体及列间距后的文字列可用宽度；未知宽度传零。 */
        textWidth: Int,
        /** 资源中 144dp 换算后的默认文字列宽度。 */
        minimumTextWidth: Int,
        /** 系统字体缩放，小于一不降低可读阈值。 */
        fontScale: Float,
        /** 完整正文、按钮及安全间隔在该列自然测量得到的总高度。 */
        detailsHeight: Int,
        /** 固定媒体容器高度，不能随右侧文字被拉长。 */
        mediaHeight: Int,
    ): AdmobMediaArrangement {
        if (textWidth <= 0 || minimumTextWidth <= 0 || mediaHeight <= 0 || detailsHeight < 0 ||
            !fontScale.isFinite() || fontScale <= 0f) return AdmobMediaArrangement.Stacked
        return if (textWidth.toDouble() >= minimumTextWidth * maxOf(1f, fontScale).toDouble() &&
            detailsHeight <= mediaHeight) AdmobMediaArrangement.SideBySide else AdmobMediaArrangement.Stacked
    }
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

/** 仅供模块内测量使用，不参与数据库、反射或外部协议。 */
internal enum class AdmobMediaArrangement {
    /** 文字在右侧，上沿正文、下沿按钮。 */
    SideBySide,
    /** 媒体在上方，完整文字和按钮向下自然排列。 */
    Stacked,
}
