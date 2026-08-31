package com.cla.clip.feature.torrent.search.provider

import com.cla.clip.feature.torrent.search.model.contract.Category
import com.cla.clip.feature.torrent.search.model.contract.ProviderSafetyLevel
import com.cla.clip.feature.torrent.search.model.contract.Torrent
import com.cla.clip.feature.torrent.search.model.contract.TorrentDetails
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 固定上游 Provider 能力测试，防止后续批量移植依赖不完整的描述符或接口。 */
class SearchProviderContractTest {
    /** 描述符必须完整携带网络、安全、能力、默认状态和来源类型元数据。 */
    @Test
    fun descriptorCarriesCompletePinnedProviderMetadata() {
        /** 合成描述符覆盖全部固定字段，不包含真实 Provider 地址或凭据。 */
        val descriptor = ProviderDescriptor(
            id = ProviderId("fixture_provider"),
            displayName = "Fixture Provider",
            baseUrl = "https://example.invalid",
            cloudflareSolverUrl = "https://solver.invalid",
            supportedCategories = setOf(Category.ALL, Category.ANIME),
            safetyLevel = ProviderSafetyLevel.SAFE,
            enabledByDefault = true,
            type = ProviderType.BUILTIN,
            isCloudflareProtected = true,
        )

        assertEquals("fixture_provider", descriptor.id.value)
        assertEquals("https://example.invalid", descriptor.baseUrl)
        assertEquals("https://solver.invalid", descriptor.cloudflareSolverUrl)
        assertEquals(setOf(Category.ALL, Category.ANIME), descriptor.supportedCategories)
        assertTrue(descriptor.enabledByDefault)
        assertTrue(descriptor.isCloudflareProtected)
    }

    /** 搜索及详情、最新、热门扩展必须传递分类并共享 SearchProvider 基础契约。 */
    @Test
    fun capabilityInterfacesExtendSearchProviderAndReceiveCategory() {
        /** 同时实现全部可选能力的 fake，用调用记录验证参数契约。 */
        val provider = CompleteCapabilityProvider()

        kotlinx.coroutines.runBlocking {
            provider.search("fixture", Category.BOOKS)
            provider.getDetails("https://example.invalid/details")
            provider.getLatestTorrents(Category.ANIME)
            provider.getTopTorrents(Category.GAMES)
        }

        assertEquals(listOf(Category.BOOKS, Category.ANIME, Category.GAMES), provider.observedCategories)
        assertEquals(listOf("mirror.invalid"), provider.alternateUrlDomains)
    }
}

/** 测试专用完整能力 Provider，不执行网络，仅记录分类参数。 */
private class CompleteCapabilityProvider : TorrentDetailsProvider, LatestTorrentsProvider, TopTorrentsProvider {
    /** 固定合成描述符覆盖完整元数据。 */
    override val descriptor = ProviderDescriptor(
        id = ProviderId("complete_fixture"), displayName = "Complete Fixture",
        baseUrl = "https://example.invalid", cloudflareSolverUrl = null,
        supportedCategories = Category.entries.toSet(), safetyLevel = ProviderSafetyLevel.SAFE,
        enabledByDefault = true, type = ProviderType.BUILTIN, isCloudflareProtected = false,
    )
    /** 详情页允许的合成备用域名。 */
    override val alternateUrlDomains: List<String> = listOf("mirror.invalid")
    /** 调用顺序中的分类记录，分别对应 search/latest/top。 */
    val observedCategories = mutableListOf<Category>()

    /** 记录搜索分类并返回空结果。 */
    override suspend fun search(query: String, category: Category): List<Torrent> {
        observedCategories += category
        return emptyList()
    }

    /** 返回空详情，验证接口允许 Provider 表示页面不受支持或解析失败。 */
    override suspend fun getDetails(detailsPageUrl: String): TorrentDetails? = null

    /** 记录最新浏览分类并返回空结果。 */
    override suspend fun getLatestTorrents(category: Category): List<Torrent> {
        observedCategories += category
        return emptyList()
    }

    /** 记录热门浏览分类并返回空结果。 */
    override suspend fun getTopTorrents(category: Category): List<Torrent> {
        observedCategories += category
        return emptyList()
    }
}
