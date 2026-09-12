package com.cla.clip.feature.torrent.search.provider

import com.cla.clip.feature.torrent.search.model.contract.ProviderSafetyLevel
import com.cla.clip.feature.torrent.search.settings.TorrentSearchSettings

/** 搜索与浏览共用的本机 Provider 选择策略。 */
internal object ProviderSelectionPolicy {
    /** 根据注册表顺序与设置快照返回当前可执行来源。 */
    fun select(
        /** 固定注册表顺序的全部来源。 */
        providers: List<SearchProvider>,
        /** 当前本机启用与安全设置。 */
        settings: TorrentSearchSettings,
    ): List<SearchProvider> {
        /** enabledProviderIds 区分首次推荐集合与用户明确保存的完整集合。 */
        val enabledProviderIds = if (settings.providerSelectionInitialized) {
            settings.enabledProviderIds
        } else {
            defaultProviderIds(providers)
        }
        return providers.filter { /** provider 是按注册表稳定顺序检查的候选来源。 */ provider ->
            provider.descriptor.id in enabledProviderIds &&
                (!settings.safeModeEnabled || provider.descriptor.safetyLevel == ProviderSafetyLevel.SAFE)
        }
    }

    /** 提取注册表推荐来源的稳定 ID，并保持注册表顺序。 */
    fun defaultProviderIds(
        /** 固定注册表顺序的全部来源。 */
        providers: List<SearchProvider>,
    ): Set<ProviderId> = providers
        /** provider 是待检查默认启用标志的注册项。 */
        .filter { provider -> provider.descriptor.enabledByDefault }
        /** provider 是待提取稳定 ID 的默认来源。 */
        .mapTo(linkedSetOf()) { provider -> provider.descriptor.id }
}
