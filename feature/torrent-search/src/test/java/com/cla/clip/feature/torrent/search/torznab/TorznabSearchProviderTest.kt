package com.cla.clip.feature.torrent.search.torznab

import com.cla.clip.feature.torrent.search.model.contract.Category
import com.cla.clip.feature.torrent.search.model.contract.ProviderSafetyLevel
import com.cla.clip.feature.torrent.search.provider.ProviderType
import com.cla.clip.feature.torrent.search.provider.builtin.testing.FakeTorrentSearchNetworkClient
import com.cla.clip.feature.torrent.search.torznab.security.TorznabApiKeyStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Torznab XML 解析、动态描述符和真实请求参数测试。 */
class TorznabSearchProviderTest {
    /** 标准 RSS item 应映射领域结果，损坏条目只跳过自身。 */
    @Test
    fun parserMapsValidItemAndSkipsMalformedItem() {
        /** config 固定动态来源身份和缺省分类。 */
        val config = createConfig()
        /** xml 包含一个完整条目和一个缺少 hash/magnet 的坏条目。 */
        val xml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <rss xmlns:torznab="http://torznab.com/schemas/2015/feed">
              <channel>
                <item>
                  <title>Fixture Book</title>
                  <pubDate>Tue, 2 Jan 2024 00:00:00 GMT</pubDate>
                  <size>1048576</size>
                  <torznab:attr name="magneturl" value="magnet:?xt=urn:btih:0123456789abcdef0123456789abcdef01234567&amp;dn=Fixture" />
                  <torznab:attr name="seeders" value="12" />
                  <torznab:attr name="peers" value="34" />
                </item>
                <item><title>Missing identity</title></item>
              </channel>
            </rss>
        """.trimIndent()

        /** results 是解析器输出的有效条目集合。 */
        val results = TorznabResponseParser.parse(xml, config)

        assertEquals(1, results.size)
        /** torrent 锁定动态 Provider 身份与常用 Torznab 字段映射。 */
        val torrent = results.single()
        assertEquals("0123456789abcdef0123456789abcdef01234567", torrent.infoHash)
        assertEquals("Fixture Book", torrent.name)
        assertEquals("1.00 MB", torrent.size)
        assertEquals(12u, torrent.seeders)
        assertEquals(34u, torrent.peers)
        assertEquals(Category.BOOKS, torrent.category)
        assertEquals(config.id.value, torrent.providerId.value)
        assertTrue(torrent.magnetUri?.startsWith("magnet:?xt=urn:btih:") == true)
        assertNull(torrent.fileDownloadLink)
    }

    /** 搜索必须在请求时注入 API Key、查询和类别，同时保留 HTTP feed 的真实可用性。 */
    @Test
    fun providerBuildsHttpSearchRequestWithRuntimeApiKey() = runBlocking {
        /** config 使用公网域名 HTTP 和非标准端口锁定用户确认的可用性契约。 */
        val config = createConfig()
        /** networkClient 返回空 RSS 并记录最终结构化 URL。 */
        val networkClient = FakeTorrentSearchNetworkClient(
            textHandler = { "<rss><channel /></rss>" },
        )
        /** apiKeyStore 只在 search 时提供内存密钥。 */
        val apiKeyStore = FixedApiKeyStore("secret key")
        /** provider 使用同步测试 dispatcher，测试不依赖真实线程时序。 */
        val provider = TorznabSearchProvider(
            config = config,
            networkClient = networkClient,
            apiKeyStore = apiKeyStore,
            parsingDispatcher = Dispatchers.Unconfined,
            secretDispatcher = Dispatchers.Unconfined,
        )

        assertTrue(provider.search("ubuntu linux", Category.ALL).isEmpty())
        assertEquals(ProviderType.TORZNAB, provider.descriptor.type)
        assertEquals(
            "http://indexer.example:9117/api?t=search&q=ubuntu%20linux&cat=7000&apikey=secret%20key",
            networkClient.textUrls.single(),
        )
        assertEquals(listOf(config.id), apiKeyStore.readIds)
    }

    /** 构造测试共享的合法非敏感配置。 */
    private fun createConfig(): TorznabConfig = TorznabConfig(
        id = TorznabConfigId("torznab_fixture"),
        name = "Fixture",
        feedUrl = "http://indexer.example:9117/api",
        category = Category.BOOKS,
        safetyLevel = ProviderSafetyLevel.UNSAFE,
        hasApiKey = true,
    )
}

/** 只返回固定 API Key 且记录读取身份的安全仓库 fake。 */
private class FixedApiKeyStore(
    /** search 时返回的内存密钥。 */
    private val apiKey: String?,
) : TorznabApiKeyStore {
    /** readIds 证明 Provider 没有提前读取或缓存密钥。 */
    val readIds = mutableListOf<TorznabConfigId>()

    /** 测试不覆盖写入，明确返回成功避免引入异常噪声。 */
    override fun save(configId: TorznabConfigId, apiKey: String?): Boolean = true

    /** 记录稳定身份并返回固定密钥。 */
    override fun read(configId: TorznabConfigId): String? {
        readIds += configId
        return apiKey
    }

    /** 测试不覆盖删除，明确返回成功。 */
    override fun remove(configId: TorznabConfigId): Boolean = true
}
