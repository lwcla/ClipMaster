package com.cla.clip.feature.torrent.search.provider.builtin.linuxtracker

import com.cla.clip.feature.torrent.search.network.HttpDeniedException
import com.cla.clip.feature.torrent.search.provider.builtin.common.BuiltinProviderUrlPolicy
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** LinuxTracker 唯一 host 的严格 URL 策略。 */
internal class LinuxTrackerUrlPolicy {
    /** manifest 审计通过的 host。 */ private val commonPolicy = BuiltinProviderUrlPolicy(setOf("linuxtracker.org"))

    /** 网络 URL 必须为同站 HTTPS 默认端口且无用户信息。 */
    fun requireUrl(/** 待访问 URL。 */ url: String): String {
        /** parsedUrl 用于补充端口和用户信息校验。 */ val parsedUrl = url.toHttpUrlOrNull() ?: throw HttpDeniedException()
        if (parsedUrl.port != 443 || parsedUrl.username.isNotEmpty() || parsedUrl.password.isNotEmpty()) throw HttpDeniedException()
        return commonPolicy.requireHttpsUrl(url).toString()
    }

    /** 可选相对地址非法时降级为空，不破坏必填 magnet 结果。 */
    fun optionalUrl(
        /** 已验证页面 URL。 */ pageUrl: String,
        /** 未信任 href。 */ candidate: String?,
    ): String? = runCatching {
        /** resolved 使用通用策略解析相对地址。 */ val resolved = commonPolicy.resolveOptionalHttpsUrl(pageUrl, candidate) ?: return null
        requireUrl(resolved)
    }.getOrNull()
}
