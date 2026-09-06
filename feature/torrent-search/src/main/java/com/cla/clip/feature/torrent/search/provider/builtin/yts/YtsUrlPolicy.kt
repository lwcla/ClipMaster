package com.cla.clip.feature.torrent.search.provider.builtin.yts

import com.cla.clip.feature.torrent.search.provider.builtin.common.BuiltinProviderUrlPolicy

/** Yts 可选媒体与 torrent 文件的输出 URL 安全策略。 */
internal object YtsUrlPolicy {
    /** manifest 审核的两个精确 host，不放行常见但未审计的 CDN。 */
    private val outputPolicy = BuiltinProviderUrlPolicy(
        setOf(
            "movies-api.accel.li",
            "yts.bz",
        ),
    )

    /** 非空候选必须是审核 host 的 HTTPS 默认端口且无 userinfo，否则安全降级为空。 */
    fun optionalOutputUrl(
        /** API 返回的未信任可选地址。 */
        candidate: String?,
    ): String? {
        if (candidate.isNullOrBlank()) {
            return null
        }
        return runCatching {
            outputPolicy.requireHttpsUrl(candidate).toString()
        }.getOrNull()
    }
}
