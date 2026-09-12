package com.cla.clip.feature.torrent.search.network

import com.cla.clip.feature.torrent.search.settings.TorrentSearchDnsMode
import okhttp3.Dns
import org.junit.Assert.assertEquals
import org.junit.Test
import java.net.InetAddress

/** 可切换 DNS 路由测试，不访问真实解析服务。 */
class SwitchableTorrentSearchDnsTest {
    /** 默认使用系统 DNS，切换后公网域名使用 DoH，本地域名继续保持真实可用。 */
    @Test
    fun routesPublicAndLocalHostsToExpectedResolver() {
        /** systemAddress 是系统 fake 返回的确定性回环地址。 */
        val systemAddress = InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1))
        /** dohAddress 是 DoH fake 返回的确定性文档地址。 */
        val dohAddress = InetAddress.getByAddress(byteArrayOf(1, 1, 1, 1))
        /** systemDns 记录系统解析收到的 host。 */
        val systemDns = RecordingDns(systemAddress)
        /** cloudflareDns 记录 Cloudflare 解析收到的 host。 */
        val cloudflareDns = RecordingDns(dohAddress)
        /** dns 是注入共享 OkHttpClient 的运行时切换入口。 */
        val dns = SwitchableTorrentSearchDns(systemDns, cloudflareDns)

        assertEquals(listOf(systemAddress), dns.lookup("indexer.example"))
        dns.applyMode(TorrentSearchDnsMode.CLOUDFLARE)
        assertEquals(listOf(dohAddress), dns.lookup("indexer.example"))
        assertEquals(listOf(systemAddress), dns.lookup("localhost"))
        assertEquals(listOf(systemAddress), dns.lookup("jackett.local"))

        assertEquals(listOf("indexer.example", "localhost", "jackett.local"), systemDns.hosts)
        assertEquals(listOf("indexer.example"), cloudflareDns.hosts)
    }
}

/** 记录解析目标并返回固定地址的无网络 DNS fake。 */
private class RecordingDns(
    /** 每次 lookup 返回的固定地址。 */
    private val address: InetAddress,
) : Dns {
    /** hosts 保存按调用顺序收到的非敏感测试域名。 */
    val hosts = mutableListOf<String>()

    /** 记录 host 并返回固定单地址。 */
    override fun lookup(hostname: String): List<InetAddress> {
        hosts += hostname
        return listOf(address)
    }
}
