package com.cla.clip.feature.torrent.search.provider

import com.cla.clip.feature.torrent.search.model.contract.Category
import com.cla.clip.feature.torrent.search.model.contract.ProviderSafetyLevel
import com.cla.clip.feature.torrent.search.model.contract.Torrent
import com.cla.clip.feature.torrent.search.settings.TorrentSearchSettings
import org.junit.Assert.assertEquals
import org.junit.Test

/** Provider 选择策略的默认启用、用户选择和安全模式回归。 */
class ProviderSelectionPolicyTest {
    /** 新安装只选择注册表默认启用且符合安全模式的来源。 */
    @Test
    fun selectUsesRegistryDefaultsBeforeSelectionIsInitialized() {
        /** defaultSafe 是新安装应自动采用的安全来源。 */
        val defaultSafe = FakeProvider("default_safe", enabledByDefault = true, ProviderSafetyLevel.SAFE)
        /** defaultUnsafe 即使默认标记错误也必须被安全模式拦截。 */
        val defaultUnsafe = FakeProvider("default_unsafe", enabledByDefault = true, ProviderSafetyLevel.UNSAFE)
        /** disabledSafe 未被注册表推荐，不应在新安装时执行。 */
        val disabledSafe = FakeProvider("disabled_safe", enabledByDefault = false, ProviderSafetyLevel.SAFE)

        /** selected 是策略根据默认设置选出的稳定有序来源。 */
        val selected = ProviderSelectionPolicy.select(
            providers = listOf(defaultSafe, defaultUnsafe, disabledSafe),
            settings = TorrentSearchSettings(),
        )

        assertEquals(listOf(defaultSafe), selected)
    }

    /** 用户明确初始化为空集合后不得回退到默认来源。 */
    @Test
    fun selectHonorsExplicitlyEmptySelection() {
        /** provider 是注册表默认启用来源。 */
        val provider = FakeProvider("default_safe", enabledByDefault = true, ProviderSafetyLevel.SAFE)
        /** settings 表示用户已经明确关闭所有来源。 */
        val settings = TorrentSearchSettings(providerSelectionInitialized = true)

        assertEquals(emptyList<SearchProvider>(), ProviderSelectionPolicy.select(listOf(provider), settings))
    }

    /** 关闭安全模式后仍只采用用户明确启用的来源并保持注册表顺序。 */
    @Test
    fun selectAllowsEnabledUnsafeProvidersWhenSafeModeIsOff() {
        /** first 是注册表中的第一个不安全来源。 */
        val first = FakeProvider("first", enabledByDefault = false, ProviderSafetyLevel.UNSAFE)
        /** second 是注册表中的第二个安全来源。 */
        val second = FakeProvider("second", enabledByDefault = false, ProviderSafetyLevel.SAFE)
        /** settings 以反序集合启用两者，结果仍应按注册表顺序。 */
        val settings = TorrentSearchSettings(
            enabledProviderIds = linkedSetOf(second.descriptor.id, first.descriptor.id),
            safeModeEnabled = false,
            providerSelectionInitialized = true,
        )

        assertEquals(listOf(first, second), ProviderSelectionPolicy.select(listOf(first, second), settings))
    }

    /** 最小 Provider fake 只承载选择策略所需元数据，不执行真实网络。 */
    private class FakeProvider(
        /** 稳定测试 ID。 */
        rawId: String,
        /** 注册表默认启用标志。 */
        enabledByDefault: Boolean,
        /** 本地安全等级。 */
        safetyLevel: ProviderSafetyLevel,
    ) : SearchProvider {
        /** 合成描述符不含真实站点或用户数据。 */
        override val descriptor = ProviderDescriptor(
            id = ProviderId(rawId),
            displayName = "Fixture",
            baseUrl = "https://fixture.invalid",
            supportedCategories = setOf(Category.ALL),
            safetyLevel = safetyLevel,
            enabledByDefault = enabledByDefault,
        )

        /** 选择策略测试不应触发网络，若误调用则立即失败。 */
        override suspend fun search(query: String, category: Category): List<Torrent> = error("unexpected network")
    }
}
