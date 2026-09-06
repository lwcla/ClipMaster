package com.cla.clip.feature.torrent.search.provider.builtin.thepiratebay

import com.cla.clip.feature.torrent.search.network.HttpDeniedException
import com.cla.clip.feature.torrent.search.provider.builtin.common.BuiltinProviderUrlPolicy
import java.net.URI

/** ThePirateBay 页面详情 token 的严格本地解析器。 */
internal object ThePirateBayDetailsTokenParser {
    /** 页面 token 只允许主展示 host，备用页面不共享该路径契约。 */
    private val pagePolicy = BuiltinProviderUrlPolicy(setOf("thepiratebay.org"))

    /** 提取唯一正整数 id；任何额外结构都在联网前拒绝。 */
    fun requireId(
        /** 未信任详情页面地址。 */
        token: String,
    ): Long {
        /** parsed 使用公共 HTTPS、精确 host、默认端口和无 userinfo 策略。 */
        val parsed = pagePolicy.requireHttpsUrl(token)
        /** rawPath 防止点段在 HttpUrl 规范化后伪装成合法路径。 */
        val rawPath = runCatching { URI(token).rawPath }.getOrNull() ?: throw HttpDeniedException()
        if (
            rawPath != "/description.php" ||
            parsed.encodedPath != "/description.php" ||
            parsed.fragment != null ||
            parsed.querySize != 1 ||
            parsed.queryParameterName(0) != "id"
        ) {
            throw HttpDeniedException()
        }
        /** rawId 必须是无符号十进制规范文本。 */
        val rawId = parsed.queryParameterValue(0) ?: throw HttpDeniedException()
        /** torrentId 限制到 Long 正整数，超长和负数都拒绝。 */
        val torrentId = rawId.toLongOrNull()?.takeIf { value -> value > 0L } ?: throw HttpDeniedException()
        if (rawId != torrentId.toString()) {
            throw HttpDeniedException()
        }
        return torrentId
    }
}
