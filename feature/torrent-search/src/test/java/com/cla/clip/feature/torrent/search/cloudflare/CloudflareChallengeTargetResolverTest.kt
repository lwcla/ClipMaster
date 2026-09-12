package com.cla.clip.feature.torrent.search.cloudflare

import com.cla.clip.feature.torrent.search.model.contract.Category
import com.cla.clip.feature.torrent.search.model.contract.ProviderSafetyLevel
import com.cla.clip.feature.torrent.search.model.contract.Torrent
import com.cla.clip.feature.torrent.search.provider.ProviderDescriptor
import com.cla.clip.feature.torrent.search.provider.ProviderId
import com.cla.clip.feature.torrent.search.provider.SearchProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Cloudflare 挑战路由只能由稳定 Provider ID 解析已知受保护来源。 */
class CloudflareChallengeTargetResolverTest {
    /** 已知受保护来源返回固定求解地址，普通或未知来源稳定拒绝。 */
    @Test
    fun resolvesOnlyKnownProtectedProvider() {
        /** protectedProvider 声明独立挑战地址。 */
        val protectedProvider = FakeProvider("protected", true, "http://provider.example/challenge")
        /** normalProvider 不允许进入 WebView。 */
        val normalProvider = FakeProvider("normal", false, null)
        /** providers 模拟固定内置注册表。 */
        val providers = listOf(protectedProvider, normalProvider)

        assertEquals(
            CloudflareChallengeTarget("Protected", "http://provider.example/challenge"),
            CloudflareChallengeTargetResolver.resolve(providers, ProviderId("protected")),
        )
        assertNull(CloudflareChallengeTargetResolver.resolve(providers, ProviderId("normal")))
        assertNull(CloudflareChallengeTargetResolver.resolve(providers, ProviderId("unknown")))
    }

    /** 最小 Provider fake 只承载挑战描述符。 */
    private class FakeProvider(
        /** 稳定 Provider ID。 */
        id: String,
        /** 是否声明 Cloudflare 保护。 */
        protected: Boolean,
        /** 可选挑战地址。 */
        solverUrl: String?,
    ) : SearchProvider {
        /** 描述符固定展示名并允许 HTTP/HTTPS 基础地址。 */
        override val descriptor = ProviderDescriptor(
            id = ProviderId(id),
            displayName = id.replaceFirstChar(Char::uppercase),
            baseUrl = "http://provider.example",
            cloudflareSolverUrl = solverUrl,
            safetyLevel = ProviderSafetyLevel.SAFE,
            enabledByDefault = true,
            isCloudflareProtected = protected,
        )

        /** 解析器测试禁止发起搜索。 */
        override suspend fun search(query: String, category: Category): List<Torrent> = error("unexpected search")
    }
}
