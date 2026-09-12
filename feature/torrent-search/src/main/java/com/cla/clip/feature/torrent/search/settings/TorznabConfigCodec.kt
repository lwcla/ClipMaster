package com.cla.clip.feature.torrent.search.settings

import com.cla.clip.feature.torrent.search.model.contract.Category
import com.cla.clip.feature.torrent.search.model.contract.ProviderSafetyLevel
import com.cla.clip.feature.torrent.search.torznab.TorznabConfig
import com.cla.clip.feature.torrent.search.torznab.TorznabConfigValidationResult
import com.cla.clip.feature.torrent.search.torznab.TorznabConfigValidator
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/** Torznab 非敏感配置 JSON codec；API Key 明文不属于输入或输出协议。 */
internal object TorznabConfigCodec {
    /** JSON DOM 只解析显式字段，不依赖反射、类名或 Kotlin 属性名。 */
    private val json = Json { ignoreUnknownKeys = true }

    /** 按固定字段顺序编码配置列表，供独立 DataStore 单 key 原子保存。 */
    fun encode(configs: List<TorznabConfig>): String = buildJsonArray {
        /** config 是当前写入的非敏感动态来源配置。 */
        configs.forEach { config ->
            add(
                buildJsonObject {
                    put("id", config.id.value)
                    put("name", config.name)
                    put("feed_url", config.feedUrl)
                    put("category", config.category.code)
                    put("safety", config.safetyLevel.code)
                    put("has_api_key", config.hasApiKey)
                },
            )
        }
    }.toString()

    /** 容错读取配置；整包损坏回退空列表，单项损坏只跳过该项。 */
    fun decode(rawJson: String?): List<TorznabConfig> {
        if (rawJson.isNullOrBlank()) return emptyList()
        /** elements 只接受数组顶层，其他 JSON 形状视为损坏设置。 */
        val elements = runCatching { json.parseToJsonElement(rawJson).jsonArray }.getOrNull()
            ?: return emptyList()
        return elements
            /** element 是当前准备转换并重新校验的 JSON 配置项。 */
            .mapNotNull { element -> runCatching { decodeConfig(element.jsonObject) }.getOrNull() }
            /** config 使用稳定 ID 去重，保留用户列表中第一个有效配置。 */
            .distinctBy { config -> config.id }
    }

    /** 从显式 JSON 字段构造配置，并复用正式校验器统一 URL 与名称规则。 */
    private fun decodeConfig(jsonObject: JsonObject): TorznabConfig? {
        /** rawId 是持久协议中的动态 Provider 稳定身份。 */
        val rawId = jsonObject["id"]?.jsonPrimitive?.contentOrNull ?: return null
        /** rawName 是用户展示名，仍需经控制字符清洗和长度校验。 */
        val rawName = jsonObject["name"]?.jsonPrimitive?.contentOrNull ?: return null
        /** rawFeedUrl 是不含 query、fragment 或 userinfo 的 HTTP/HTTPS 地址。 */
        val rawFeedUrl = jsonObject["feed_url"]?.jsonPrimitive?.contentOrNull ?: return null
        /** category 使用稳定 code，未知值按 ALL 兼容。 */
        val category = Category.fromCode(jsonObject["category"]?.jsonPrimitive?.contentOrNull)
        /** safetyLevel 使用稳定 code，未知值保守回退 UNSAFE。 */
        val safetyLevel = ProviderSafetyLevel.fromCode(jsonObject["safety"]?.jsonPrimitive?.contentOrNull)
        /** hasApiKey 只是密钥存在元数据，缺失或损坏时按 false。 */
        val hasApiKey = jsonObject["has_api_key"]?.jsonPrimitive?.booleanOrNull ?: false
        /** result 复用唯一正式校验入口，防止损坏存储绕过运行时边界。 */
        val result = TorznabConfigValidator.validate(
            rawId = rawId,
            rawName = rawName,
            rawFeedUrl = rawFeedUrl,
            category = category,
            safetyLevel = safetyLevel,
            hasApiKey = hasApiKey,
        )
        return (result as? TorznabConfigValidationResult.Valid)?.config
    }
}
