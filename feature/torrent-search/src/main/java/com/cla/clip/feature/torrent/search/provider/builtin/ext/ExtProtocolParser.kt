package com.cla.clip.feature.torrent.search.provider.builtin.ext

import com.cla.clip.feature.torrent.search.model.contract.Category
import com.cla.clip.feature.torrent.search.provider.builtin.common.BuiltinProviderUrlPolicy
import com.cla.clip.feature.torrent.search.provider.builtin.common.MagnetInfoHashParser
import java.security.MessageDigest
import java.util.Locale
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.jsoup.nodes.Document

/** Ext 表单与描述 JSON 解析器；无效单条响应统一降级为空。 */
internal class ExtJsonParser(
    /** JSON tree 与字段校验使用的计算调度器。 */
    private val parsingDispatcher: CoroutineDispatcher,
) {
    /** 表单响应必须为对象且 url 是含合法 btih 的 magnet。 */
    suspend fun parseMagnet(rawJson: String): String? = withContext(parsingDispatcher) {
        /** JSON 语法或顶层错误属于单条补全失败。 */
        val root = runCatching { Json.parseToJsonElement(rawJson) }.getOrNull() as? JsonObject
            ?: return@withContext null
        /** url 必须是非空 JSON string。 */
        val magnetUri = root.stringOrNull("url")?.takeIf(String::isNotBlank) ?: return@withContext null
        MagnetInfoHashParser.parse(magnetUri) ?: return@withContext null
        magnetUri
    }

    /** 描述 JSON 只接受对象中的 data 字符串，内容保留为不可信 HTML。 */
    suspend fun parseDescription(rawJson: String): String? = withContext(parsingDispatcher) {
        /** 非法 JSON、非对象或非字符串 data 均按无描述降级。 */
        val root = runCatching { Json.parseToJsonElement(rawJson) }.getOrNull() as? JsonObject
            ?: return@withContext null
        root.stringOrNull("data")
    }
}

/** Ext 页面字段解析器；集中限制 token/ID/slug 与模型 URL。 */
internal class ExtPageValueParser(
    /** 页面链接只能解析到 manifest 审计的精确 HTTPS host。 */
    private val urlPolicy: BuiltinProviderUrlPolicy,
) {
    /** 从 script data 中匹配固定 window 属性赋值，不执行 JavaScript。 */
    fun extractScriptToken(document: Document, propertyName: String): String? {
        /** pattern 仅接受单引号字符串及可选分号，禁止求值任意表达式。 */
        val pattern = Regex("window\\." + Regex.escape(propertyName) + "\\s*=\\s*'([^']*)'\\s*;?")
        /** script 是当前静态节点，只读取 data 文本并提取首个固定赋值。 */
        val rawToken = document.select("script").asSequence()
            // script 是当前静态节点，只读取 data 文本，不执行其中代码。
            .mapNotNull { script -> pattern.find(script.data())?.groupValues?.getOrNull(1) }
            .firstOrNull()
        return boundedToken(rawToken)
    }

    /** 类别链接只接受同站单段路径并映射固定字符串；未知值回退 OTHER。 */
    fun parseCategory(candidate: String?, pageUrl: String): Category {
        /** categoryUrl 无效或跨 host 时不抛出页面级失败。 */
        val categoryUrl = resolveOptionalModelUrl(pageUrl, candidate) ?: return Category.OTHER
        /** 路径去除空尾段后必须只有一个类别 segment。 */
        val segments = categoryUrl.toHttpUrl().pathSegments.filter(String::isNotEmpty)
        if (segments.size != 1) return Category.OTHER
        return EXT_CATEGORIES[segments.single()] ?: Category.OTHER
    }

    /** 模型 URL 解析失败或不满足精确 host 时返回 null，不让单条污染顶层结果。 */
    fun resolveOptionalModelUrl(baseUrl: String, candidate: String?): String? =
        runCatching { urlPolicy.resolveOptionalHttpsUrl(baseUrl, candidate) }.getOrNull()

    /** torrent ID 与 slug 必须为 1..256 字符，避免放大后续表单或查询。 */
    fun boundedId(rawValue: String?): String? = boundedValue(rawValue, MAX_ID_LENGTH)

    /** session 与 page token 必须为 1..1024 字符。 */
    fun boundedToken(rawValue: String?): String? = boundedValue(rawValue, MAX_TOKEN_LENGTH)

    /** 页面字段统一限制非空和最大长度。 */
    private fun boundedValue(rawValue: String?, maxLength: Int): String? {
        /** value 是当前未信任页面字段，只在长度范围内返回。 */
        val value = rawValue ?: return null
        /** candidate 是当前非空页面字段，只在最大长度内保留。 */
        return value.takeIf { candidate -> candidate.isNotBlank() && candidate.length <= maxLength }
    }

    /** Ext 页面字段长度常量。 */
    private companion object {
        /** torrent ID 和 slug 的最大字符数。 */
        private const val MAX_ID_LENGTH = 256
        /** session 与 page token 的最大字符数。 */
        private const val MAX_TOKEN_LENGTH = 1_024
    }
}

/** Ext 固定 SHA-256 协议计算器；名称沿用上源 HMAC 叫法但算法无密钥。 */
internal object ExtHmacSha256 {
    /** 按 UTF-8 的 torrentId|epochSeconds|pageToken 计算小写 SHA-256。 */
    fun compute(torrentId: String, epochSeconds: Long, pageToken: String): String {
        /** 固定协议正文仅在内存存在，不进入网络日志或异常。 */
        val data = "$torrentId|$epochSeconds|$pageToken".toByteArray(Charsets.UTF_8)
        /** 每次调用创建独立摘要实例，避免跨协程共享可变状态。 */
        val digest = MessageDigest.getInstance("SHA-256").digest(data)
        /** byte 是当前摘要字节，按无符号两位小写 hex 输出。 */
        return digest.joinToString(separator = "") { byte -> "%02x".format(Locale.US, byte.toInt() and 0xff) }
    }
}

/** Ext 页面类别路径到稳定领域分类的固定映射。 */
private val EXT_CATEGORIES = mapOf(
    "anime" to Category.ANIME,
    "applications" to Category.APPS,
    "books" to Category.BOOKS,
    "games" to Category.GAMES,
    "movies" to Category.MOVIES,
    "music" to Category.MUSIC,
    "other" to Category.OTHER,
    "tv" to Category.SERIES,
    "xxx" to Category.PORN,
)

/** 只读取 JSON string；数字、布尔、null 与复合类型按缺失处理。 */
private fun JsonObject.stringOrNull(key: String): String? {
    /** primitive 必须明确是 JSON string，避免宽松转换协议值。 */
    val primitive = this[key] as? JsonPrimitive ?: return null
    if (!primitive.isString) return null
    return primitive.contentOrNull
}