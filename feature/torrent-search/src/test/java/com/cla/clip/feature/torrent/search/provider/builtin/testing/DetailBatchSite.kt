package com.cla.clip.feature.torrent.search.provider.builtin.testing

import com.cla.clip.feature.torrent.search.network.TorrentSearchNetworkClient
import com.cla.clip.feature.torrent.search.provider.TorrentDetailsProvider
import com.cla.clip.feature.torrent.search.provider.builtin.oxtorrent.OxTorrentProvider
import com.cla.clip.feature.torrent.search.provider.builtin.torrent9.Torrent9Provider
import com.cla.clip.feature.torrent.search.provider.builtin.zeromagnet.ZeroMagnetProvider
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

/** 04E4 公开入口的最小测试接线；不参与生产注册或网络。 */
enum class DetailBatchSite(
    /** 独立中性 fixture 所在目录。 */
    val fixtureDirectory: String,
    /** 固定来源主机，用于构造预期与恶意 authority。 */
    val host: String,
) {
    OX("oxtorrent", "oxtorrent.co"),
    NINE("torrent9", "www6.torrent9.to"),
    ZERO("zeromagnet", "9mag.net");

    /** 每次构造真实 Provider，测试只替换网络和计算线程。 */
    internal fun provider(
        /** 可控无网络客户端。 */
        client: TorrentSearchNetworkClient,
        /** 默认沿用生产计算线程，协程测试可注入虚拟调度。 */
        dispatcher: CoroutineDispatcher = Dispatchers.Default,
    ): TorrentDetailsProvider = when (this) {
        OX -> OxTorrentProvider(client, dispatcher)
        NINE -> Torrent9Provider(client, dispatcher)
        ZERO -> ZeroMagnetProvider(client, dispatcher)
    }

    /** 当前站独立详情 fixture，不共享不同 DOM 的模板。 */
    fun detailsHtml(): String = readProviderFixture("provider/$fixtureDirectory/details_normal.html")

    /** 当前站独立列表 fixture，保留 Alpha/Beta 两条详情入口。 */
    fun listHtml(): String = readProviderFixture("provider/$fixtureDirectory/search_normal.html")

    /** 将单条详情 fixture 转换为独立 Beta 身份，用于证明顺序与跳过语义。 */
    fun betaHtml(): String = detailsHtml().replace("Sample Alpha", "Sample Beta").replace(HASH, SECOND_HASH)

    /** 固定测试详情路径，不读取文件或发网络。 */
    fun url(
        /** 中性条目标识。 */
        name: String = "sample-alpha",
    ): String = "https://$host/details/$name"

    /** 各站共用的中性身份和 JUnit 参数集。 */
    companion object {
        /** Alpha 的合成非真实 infoHash。 */
        const val HASH = "0123456789abcdef0123456789abcdef01234567"
        /** Beta 的合成非真实 infoHash。 */
        const val SECOND_HASH = "89abcdef0123456789abcdef0123456789abcdef"
        /** Alpha 磁力 URI，仅用于离线断言。 */
        const val MAGNET = "magnet:?xt=urn:btih:$HASH"

        /** 为三个独立来源执行相同公开契约，每个参数单独计入 JUnit。 */
        fun parameters(): Collection<Array<Any>> = entries.map {
            /** site 是当前独立来源，不从生产注册表推导期望。 */
            site -> arrayOf<Any>(site)
        }
    }
}
