package com.cla.clip.shizuku

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/**
 * Shizuku 直读剪贴板后写入 Provider 的脱敏传输 payload。
 *
 * payload 只承载第一版支持的文本与 HTML 字段；URI、Intent 和图片等二进制内容不进入协议，避免临时文件扩大成授权搬运通道。
 */
data class ClipboardBridgeClipPayload(
    /** payload 协议版本，Provider 解析时必须与 `CLIP_PAYLOAD_VERSION` 一致。 */
    val version: Int,
    /** 本次剪贴事件 ID，同时也是 Provider 临时文件名主体。 */
    val eventId: String,
    /** Shizuku 回调入口或直读成功时记录的捕获时间，优先用于数据库剪贴时间。 */
    val capturedAtMillis: Long,
    /** 系统剪贴板描述中的 MIME 类型列表，用于 app 侧判断空文本是空剪贴还是不支持类型。 */
    val mimeTypes: List<String>,
    /** 首个剪贴 item 的普通文本，可能为空表示需要尝试 HTML fallback 或判定不支持。 */
    val text: String?,
    /** 首个剪贴 item 的 HTML 文本，app 侧只把它转成纯文本 fallback，不持久化原始 HTML。 */
    val htmlText: String?,
) {
    /**
     * 序列化为 UTF-8 JSON 字符串。
     *
     * 该字符串会通过 `content write` stdin 传输，不能拼接到 shell 命令参数里。
     */
    fun toJsonString(): String {
        /** JSON 根对象，字段名是 Provider payload v1 的稳定协议。 */
        val json = buildJsonObject {
            put(KEY_VERSION, version)
            put(KEY_EVENT_ID, eventId)
            put(KEY_CAPTURED_AT_MILLIS, capturedAtMillis)
            put(KEY_MIME_TYPES, buildJsonArray {
                mimeTypes.forEach { mimeType ->
                    add(JsonPrimitive(mimeType))
                }
            })
            put(KEY_TEXT, text?.let(::JsonPrimitive) ?: JsonNull)
            put(KEY_HTML_TEXT, htmlText?.let(::JsonPrimitive) ?: JsonNull)
        }
        return json.toString()
    }

    companion object {
        /** JSON 字段：payload 协议版本。 */
        private const val KEY_VERSION = "version"

        /** JSON 字段：当前剪贴事件 ID。 */
        private const val KEY_EVENT_ID = "eventId"

        /** JSON 字段：Shizuku 侧捕获时间戳。 */
        private const val KEY_CAPTURED_AT_MILLIS = "capturedAtMillis"

        /** JSON 字段：剪贴板 MIME 类型列表。 */
        private const val KEY_MIME_TYPES = "mimeTypes"

        /** JSON 字段：普通文本内容。 */
        private const val KEY_TEXT = "text"

        /** JSON 字段：HTML 文本内容。 */
        private const val KEY_HTML_TEXT = "htmlText"

        /** JSON 解析器；只用于当前轻量 payload，不需要全局注册序列化模型。 */
        private val jsonParser = Json { ignoreUnknownKeys = true }

        /**
         * 解析 Provider 临时文件中的 JSON 字符串。
         *
         * @param text `content write` 写入的完整 JSON 文本；解析失败时抛出异常，由调用方映射成 invalid_payload。
         */
        fun fromJsonString(text: String): ClipboardBridgeClipPayload {
            /** JSON 根对象；异常会交给调用方转成稳定失败码。 */
            val json = jsonParser.parseToJsonElement(text).jsonObject

            /** MIME 类型 JSON 数组；缺失时按空列表处理，兼容早期探针数据。 */
            val mimeTypesJson = json[KEY_MIME_TYPES]

            /** MIME 类型列表；只保留字符串项，避免畸形数组污染诊断字段。 */
            val mimeTypes = if (mimeTypesJson == null) {
                emptyList()
            } else {
                mimeTypesJson.jsonArray
                    .mapNotNull { item -> item.jsonPrimitive.contentOrNull?.takeIf { value -> value.isNotBlank() } }
            }

            return ClipboardBridgeClipPayload(
                version = json[KEY_VERSION]?.jsonPrimitive?.intOrNull ?: -1,
                eventId = json[KEY_EVENT_ID]?.jsonPrimitive?.contentOrNull.orEmpty(),
                capturedAtMillis = json[KEY_CAPTURED_AT_MILLIS]?.jsonPrimitive?.longOrNull ?: 0L,
                mimeTypes = mimeTypes,
                text = json[KEY_TEXT]?.jsonPrimitive?.contentOrNull,
                htmlText = json[KEY_HTML_TEXT]?.jsonPrimitive?.contentOrNull
            )
        }
    }
}
