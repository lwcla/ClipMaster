package com.cla.clip.feature.torrent.search.ui.details

/** 同一 LazyColumn 内详情图片的稳定区段身份。 */
internal enum class TorrentDetailsImageSection(
    /** keyPrefix 为各区段提供不会与 URL 内容碰撞的固定命名空间。 */
    val keyPrefix: String,
) {
    /** DESCRIPTION 表示安全正文解析出的图片。 */
    DESCRIPTION("description_image"),
    /** SCREENSHOT 表示 Provider 结构化截图。 */
    SCREENSHOT("screenshot"),
}

/** 集中表达详情图片的生命周期展示与稳定 key 规则。 */
internal object TorrentDetailsImagePresentationPolicy {
    /** 只有当前 NavBackStackEntry 至少 STARTED 时才创建网络图片子 Composition。 */
    fun shouldComposeImages(
        /** destinationActive 来自生命周期桥接的直接 Compose 状态。 */
        destinationActive: Boolean,
    ): Boolean = destinationActive

    /** 使用区段命名空间和规范化 URL 构造同一列表中的稳定唯一 key。 */
    fun lazyItemKey(
        /** section 是图片所属的语义区段。 */
        section: TorrentDetailsImageSection,
        /** imageUrl 是已通过同源 HTTPS 校验的规范化地址。 */
        imageUrl: String,
    ): String = "${section.keyPrefix}:$imageUrl"
}
