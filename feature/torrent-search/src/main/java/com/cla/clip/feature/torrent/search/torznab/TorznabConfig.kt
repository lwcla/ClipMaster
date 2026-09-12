package com.cla.clip.feature.torrent.search.torznab

import com.cla.clip.feature.torrent.search.model.contract.Category
import com.cla.clip.feature.torrent.search.model.contract.ProviderSafetyLevel
import com.cla.clip.feature.torrent.search.provider.ProviderId
import java.net.URI
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** Torznab 配置稳定身份；格式与 ProviderId 一致，可直接用于动态 Provider 关联。 */
@JvmInline
internal value class TorznabConfigId(
    /** 带 torznab_ 命名空间的小写稳定 ID，禁止展示名和 URL 进入身份。 */
    val value: String,
) {
    /** 构造时复用全局 ProviderId 协议并保留动态来源命名空间。 */
    init {
        ProviderId(value)
        require(value.startsWith(TORZNAB_ID_PREFIX) && value.length > TORZNAB_ID_PREFIX.length)
    }
}

/** Torznab 动态 Provider 身份前缀，避免与 43 个内置 ID 共用命名空间。 */
private const val TORZNAB_ID_PREFIX = "torznab_"

/** 不含 API Key 明文的 Torznab 配置；密钥只通过独立安全仓库访问。 */
internal data class TorznabConfig(
    /** 可映射动态 Provider 的稳定身份。 */
    val id: TorznabConfigId,
    /** 用户可见名称，不能用作持久主键。 */
    val name: String,
    /** 不含 userinfo、query 或 fragment 的 HTTP/HTTPS feed URL。 */
    val feedUrl: String,
    /** 此索引器的稳定分类。 */
    val category: Category,
    /** 本机安全模式使用的稳定安全等级。 */
    val safetyLevel: ProviderSafetyLevel,
    /** 是否已有独立加密密钥，仅用于页面状态，不代表密钥可解密。 */
    val hasApiKey: Boolean,
)

/** Torznab 配置校验失败稳定协议。 */
internal enum class TorznabConfigFailureReason(
    /** 对外稳定 reasonCode。 */
    val code: String,
) {
    /** 配置 ID 不满足稳定 Provider 身份格式。 */
    INVALID_ID("invalid_id"),
    /** 展示名清洗后为空或超过 80 字符。 */
    INVALID_NAME("invalid_name"),
    /** feed URL 不满足 Torznab 安全边界。 */
    INVALID_URL("invalid_url"),
}

/** Torznab 配置校验结果。 */
internal sealed interface TorznabConfigValidationResult {
    /** 配置可安全进入后续持久层或 Provider。 */
    data class Valid(
        /** 已规范化且不含密钥的配置。 */
        val config: TorznabConfig,
    ) : TorznabConfigValidationResult

    /** 输入存在稳定分类的错误。 */
    data class Invalid(
        /** 页面可映射的失败分类。 */
        val reason: TorznabConfigFailureReason,
    ) : TorznabConfigValidationResult
}

/** 构造并规范化 Torznab 非敏感配置，防止 API Key 被误嵌入 URL。 */
internal object TorznabConfigValidator {
    /** 展示名最大字符数。 */
    private const val MAX_NAME_CHARS = 80

    /** 校验所有输入并返回不含原文的失败分类。 */
    fun validate(
        /** UI 生成或既有存储读取的稳定 ID。 */
        rawId: String,
        /** 用户输入的展示名。 */
        rawName: String,
        /** 用户输入的 Torznab feed URL。 */
        rawFeedUrl: String,
        /** 用户选择的稳定分类。 */
        category: Category,
        /** 用户选择的安全等级。 */
        safetyLevel: ProviderSafetyLevel,
        /** 安全仓库是否已有该配置的密钥。 */
        hasApiKey: Boolean,
    ): TorznabConfigValidationResult {
        /** id 使用强类型构造器复用动态 Provider 格式。 */
        val id = runCatching { TorznabConfigId(rawId) }.getOrNull()
            ?: return TorznabConfigValidationResult.Invalid(TorznabConfigFailureReason.INVALID_ID)
        /** name 删除不可见控制字符并裁剪边界空白。 */
        val name = buildString(rawName.length) {
            /** character 是当前展示名字符。 */
            for (character in rawName) if (!character.isISOControl()) append(character)
        }.trim()
        if (name.isEmpty() || name.length > MAX_NAME_CHARS) {
            return TorznabConfigValidationResult.Invalid(TorznabConfigFailureReason.INVALID_NAME)
        }
        /** normalizedFeedUrl 只裁剪边界空白，不对缺失 authority 的原始输入做猜测。 */
        val normalizedFeedUrl = rawFeedUrl.trim()
        /** rawAuthority 先证明原输入显式包含 authority，避免三斜杠被宽松补成主机名。 */
        val rawAuthority = runCatching { URI(normalizedFeedUrl).rawAuthority }.getOrNull()
        if (rawAuthority.isNullOrBlank()) {
            return TorznabConfigValidationResult.Invalid(TorznabConfigFailureReason.INVALID_URL)
        }
        /** parsedUrl 交给 OkHttp 处理 IPv6、端口和编码细节。 */
        val parsedUrl = normalizedFeedUrl.toHttpUrlOrNull()
            ?: return TorznabConfigValidationResult.Invalid(TorznabConfigFailureReason.INVALID_URL)
        /** validScheme 按自用可用性约定接受任意合法主机的 HTTP 与 HTTPS。 */
        val validScheme = parsedUrl.scheme == "http" || parsedUrl.scheme == "https"
        /** containsSecretBearingParts 拒绝 userinfo、query 和 fragment，避免密钥进入普通配置。 */
        val containsSecretBearingParts = parsedUrl.username.isNotEmpty() ||
            parsedUrl.password.isNotEmpty() ||
            parsedUrl.query != null ||
            parsedUrl.fragment != null
        if (!validScheme || containsSecretBearingParts) {
            return TorznabConfigValidationResult.Invalid(TorznabConfigFailureReason.INVALID_URL)
        }
        return TorznabConfigValidationResult.Valid(
            TorznabConfig(
                id = id,
                name = name,
                feedUrl = parsedUrl.toString(),
                category = category,
                safetyLevel = safetyLevel,
                hasApiKey = hasApiKey,
            ),
        )
    }
}
