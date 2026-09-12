package com.cla.clip.feature.torrent.search.settings

/** TorrentSearch DNS 解析模式，持久化只使用显式稳定 code。 */
internal enum class TorrentSearchDnsMode(
    /** DataStore 使用的稳定协议值，禁止改用枚举 name 或 ordinal。 */
    val code: String,
) {
    /** 使用 Android/网络当前配置的系统 DNS。 */
    SYSTEM("system"),

    /** 公共域名使用固定 Cloudflare HTTPS DoH。 */
    CLOUDFLARE("cloudflare");

    /** 集中处理持久值兼容读取。 */
    companion object {
        /** 精确匹配稳定 code；缺失、大小写变化或未来值均回退系统 DNS。 */
        fun fromCode(code: String?): TorrentSearchDnsMode =
            entries.firstOrNull { /** mode 是待与持久 code 精确比较的 DNS 模式。 */ mode -> mode.code == code } ?: SYSTEM
    }
}

/** 将持久 DNS 模式应用到共享网络运行时的窄接口。 */
internal fun interface TorrentSearchDnsModeApplier {
    /** 幂等应用模式；实现不得在设置 Flow 收集线程直接联网。 */
    fun applyMode(mode: TorrentSearchDnsMode)
}

/** 纯设置测试和无网络调用方使用的无副作用默认实现。 */
internal object NoOpTorrentSearchDnsModeApplier : TorrentSearchDnsModeApplier {
    /** 无网络运行时场景只保留设置值，不执行额外动作。 */
    override fun applyMode(mode: TorrentSearchDnsMode) = Unit
}
