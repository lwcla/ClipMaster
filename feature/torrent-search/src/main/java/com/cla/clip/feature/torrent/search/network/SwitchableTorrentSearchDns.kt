package com.cla.clip.feature.torrent.search.network

import com.cla.clip.feature.torrent.search.settings.TorrentSearchDnsMode
import com.cla.clip.feature.torrent.search.settings.TorrentSearchDnsModeApplier
import okhttp3.Dns
import java.net.InetAddress
import java.util.Locale

/** 在系统 DNS 与 Cloudflare DoH 之间切换的共享 OkHttp DNS 路由。 */
internal class SwitchableTorrentSearchDns(
    /** 系统解析器负责默认模式和本地目标。 */
    private val systemDns: Dns,
    /** Cloudflare DoH 解析器只负责用户主动选择后的公共域名。 */
    private val cloudflareDns: Dns,
) : Dns, TorrentSearchDnsModeApplier {
    /** 当前模式使用 volatile 保证设置线程与 OkHttp 线程立即可见。 */
    @Volatile
    private var currentMode: TorrentSearchDnsMode = TorrentSearchDnsMode.SYSTEM

    /** 幂等应用最新设置；这里只切换内存路由，不发起 DNS 或网络请求。 */
    override fun applyMode(mode: TorrentSearchDnsMode) {
        currentMode = mode
    }

    /** 按当前模式解析；本地域名和字面量地址始终交给系统以保持自建服务可用。 */
    override fun lookup(hostname: String): List<InetAddress> {
        /** resolver 是当前请求实际使用且不会静默跨公共 DNS 回退的解析器。 */
        val resolver = if (currentMode == TorrentSearchDnsMode.CLOUDFLARE && !hostname.isLocalTarget()) {
            cloudflareDns
        } else {
            systemDns
        }
        return resolver.lookup(hostname)
    }
}

/** 判断 host 是否应保留在系统/局域网解析边界，避免 DoH 破坏自建索引器。 */
private fun String.isLocalTarget(): Boolean {
    /** normalizedHost 只用于不区分大小写的本地域名分类。 */
    val normalizedHost = lowercase(Locale.US)
    /** looksLikeIpv4 只识别 HttpUrl 已规范化后的数字点分形状，实际合法性仍由 OkHttp 负责。 */
    val looksLikeIpv4 = normalizedHost.all { character -> character.isDigit() || character == '.' }
    /** looksLikeIpv6 由规范化 host 中的冒号判断，避免把字面量地址交给公共 DoH。 */
    val looksLikeIpv6 = ':' in normalizedHost
    return normalizedHost == "localhost" ||
        normalizedHost.endsWith(".localhost") ||
        normalizedHost.endsWith(".local") ||
        normalizedHost.endsWith(".lan") ||
        '.' !in normalizedHost ||
        looksLikeIpv4 ||
        looksLikeIpv6
}
