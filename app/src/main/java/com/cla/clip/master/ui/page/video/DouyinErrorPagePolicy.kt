package com.cla.clip.master.ui.page.video

import java.net.URI
import java.util.Locale

/** 仅允许已核验的分享入口，不能用包含关键字的方式信任来源。 */
private val DOUYIN_SHARE_HOSTS = setOf("iesdouyin.com", "www.iesdouyin.com", "douyin.com", "www.douyin.com", "m.douyin.com")
/** 数字作品号只作路径形态校验，不读取或记录实际标识。 */
private val DOUYIN_SHARE_PATH = Regex("/share/video/[0-9]+/?")

/** 只有已核验的作品分享页允许诊断；未知来源安全降级为原提取预算。 */
internal fun isDouyinSharePage(url: String?): Boolean {
    if (url.isNullOrBlank()) return false
    // URI 解析不发起网络请求；非法编码与缺失主机都按未知来源处理。
    val uri = runCatching { URI(url) }.getOrNull() ?: return false
    return uri.scheme?.lowercase(Locale.ROOT) in setOf("http", "https") &&
        uri.host?.lowercase(Locale.ROOT) in DOUYIN_SHARE_HOSTS &&
        DOUYIN_SHARE_PATH.matches(uri.rawPath.orEmpty())
}
