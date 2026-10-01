package com.cla.clip.master.backup.upload

import com.cla.clip.base.general.backup.normalizeWebDavRemoteDir
import com.cla.clip.base.general.backup.settings.BackupSettingsSnapshot
import java.security.MessageDigest
import java.net.URI
import java.util.Locale

/** 目标绑定只保存摘要，不保存凭据、端点原文或目录授权。 */
internal object BackupUploadTargetFingerprint {
    /** 配置已规范化；长度前缀排除字段分隔歧义，密码更新不使同目标等待包失效。 */
    fun of(settings: BackupSettingsSnapshot): String {
        /** 影响实际上传目的地的固定字段；不包含密码。 */
        val fields = listOf(normalizeEndpoint(settings.webDavEndpoint), settings.webDavUsername,
            normalizeWebDavRemoteDir(settings.webDavRemoteDir), settings.webDavAllowInsecureHttp.toString())
        /** 长度编码确保任意账号字符不污染其他字段。 */
        val input = fields.joinToString("") { field -> "${field.length}:$field" }.toByteArray(Charsets.UTF_8)
        return MessageDigest.getInstance("SHA-256").digest(input).joinToString("") { byte -> "%02x".format(byte) }
    }

    /** 规范化标准 URL 等价表示；只计算目的地身份，不修改网络请求。 */
    private fun normalizeEndpoint(endpoint: String): String {
        /** 原始 URL 的规范化路径，非法配置显式失败。 */
        val uri = URI(endpoint.trim()).normalize()
        /** 协议和主机大小写不影响实际目的地。 */
        val scheme = uri.scheme?.lowercase(Locale.ROOT)
        /** 默认端口省略，显式非默认端口仍属于不同目标。 */
        val port = uri.port.takeUnless { (scheme == "https" && it == 443) || (scheme == "http" && it == 80) } ?: -1
        /** 只规范化 authority；路径与查询保留原始百分号编码，避免编码斜杠身份碰撞。 */
        val authority = buildString {
            uri.rawUserInfo?.let { append(it); append('@') }
            append(uri.host?.lowercase(Locale.ROOT).orEmpty())
            if (port >= 0) { append(':'); append(port) }
        }
        return buildString {
            append(scheme); append("://"); append(authority)
            append(uri.rawPath.orEmpty().trimEnd('/'))
            uri.rawQuery?.let { append('?'); append(it) }
            uri.rawFragment?.let { append('#'); append(it) }
        }
    }
}
