package com.cla.clip.feature.torrent.search.cloudflare

import androidx.lifecycle.ViewModel
import com.cla.clip.feature.torrent.search.provider.ProviderId
import com.cla.clip.feature.torrent.search.provider.SearchProvider
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

/** 挑战页唯一需要的非敏感显示与导航信息，不包含 Cookie。 */
internal data class CloudflareChallengeTarget(
    /** 已知 Provider 的展示名。 */
    val providerName: String,
    /** 注册表声明的 HTTP/HTTPS 初始挑战地址。 */
    val url: String,
)

/** 从固定 Provider 注册表解析挑战目标的纯规则。 */
internal object CloudflareChallengeTargetResolver {
    /** 只有稳定 ID 命中且明确受 Cloudflare 保护的来源才能进入 WebView。 */
    fun resolve(
        /** 固定内置来源与动态来源合集。 */
        providers: List<SearchProvider>,
        /** 路由中解析成功的稳定来源身份。 */
        providerId: ProviderId,
    ): CloudflareChallengeTarget? {
        /** provider 必须精确命中稳定 ID 且声明挑战能力。 */
        val provider = providers.firstOrNull { /** candidate 是注册表中的待匹配来源。 */ candidate ->
            candidate.descriptor.id == providerId && candidate.descriptor.isCloudflareProtected
        } ?: return null
        /** challengeUrl 优先使用显式求解地址，否则回退该来源基础地址。 */
        val challengeUrl = provider.descriptor.cloudflareSolverUrl ?: provider.descriptor.baseUrl
        if (!CloudflareNavigationPolicy(challengeUrl).allows(challengeUrl)) return null
        return CloudflareChallengeTarget(provider.descriptor.displayName, challengeUrl)
    }
}

/** 挑战路由解析器；不保存 Cookie、URL 历史或 WebView 实例。 */
@HiltViewModel
internal class CloudflareChallengeViewModel @Inject constructor(
    /** 固定 Provider 注册表用于按稳定 ID 解析目标。 */
    private val providers: List<@JvmSuppressWildcards SearchProvider>,
) : ViewModel() {
    /** 同步解析已知挑战目标；非法/普通 Provider 返回 null。 */
    fun resolve(providerId: ProviderId): CloudflareChallengeTarget? = CloudflareChallengeTargetResolver.resolve(providers, providerId)
}
