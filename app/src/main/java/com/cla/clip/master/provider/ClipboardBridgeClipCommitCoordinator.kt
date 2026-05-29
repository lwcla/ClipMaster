package com.cla.clip.master.provider

import android.content.Context
import com.cla.clip.base.general.repository.ClipRepository
import com.cla.clip.base.general.utils.logE
import com.cla.clip.base.general.utils.logI
import com.cla.clip.base.general.utils.logW
import com.cla.clip.master.utils.ClipHelper
import com.cla.clip.master.utils.ClipProcessResult
import com.cla.clip.shizuku.ClipboardBridgeClipPayload
import com.cla.clip.shizuku.ClipboardBridgeContract
import dagger.Lazy
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import org.jsoup.Jsoup
import java.io.File
import javax.inject.Inject

/**
 * Provider 剪贴 payload 提交协调器。
 *
 * 只负责 commit_clip 阶段的 payload 解析、HTML fallback、来源图标缓存复用和文本入库，不读取系统剪贴板。
 */
class ClipboardBridgeClipCommitCoordinator @Inject constructor(
    /** 应用 Context，用于读取 payload 临时目录。 */
    @param:ApplicationContext private val appContext: Context,
    /** Provider 剪贴 payload 临时传输目录管理器。 */
    private val payloadStore: ClipboardBridgeClipPayloadStore,
    /** 剪贴板入库助手，复用现有去重、链接解析、备份 dirty 和通知逻辑。 */
    private val clipHelper: Lazy<ClipHelper>,
    /** 剪贴仓库，用于复用旧来源图标字段。 */
    private val clipRepository: Lazy<ClipRepository>,
) {
    companion object {
        /** 剪贴 payload 提交日志标签，用于定位 commit_clip 阶段结果。 */
        private const val TAG = "ClipboardBridgeClipCommit"

        /** commit_clip 同步等待上限，避免 shell content call 长时间挂起。 */
        private const val COMMIT_TIMEOUT_MS = 3_000L
    }

    /**
     * 提交剪贴 payload 并按现有文本规则入库。
     *
     * @param request Provider commit_clip 请求参数。
     */
    suspend fun commit(request: ClipboardBridgeRequest): ClipboardBridgeResult {
        return try {
            withTimeout(COMMIT_TIMEOUT_MS) {
                commitInternal(request)
            }
        } catch (exception: TimeoutCancellationException) {
            logW(TAG) { "Provider 剪贴 payload 提交超时 eventId=${request.eventId} packageName=${request.packageName}" }
            ClipboardBridgeResult.of(
                resultCode = ClipboardBridgeContract.CODE_TIMEOUT,
                clipStatus = ClipboardBridgeContract.CLIP_STATUS_COMMIT_FAILED
            )
        } catch (throwable: Throwable) {
            logE(TAG, throwable) { "Provider 剪贴 payload 提交失败 eventId=${request.eventId} packageName=${request.packageName}" }
            ClipboardBridgeResult.of(
                resultCode = ClipboardBridgeContract.CODE_COMMIT_FAILED,
                clipStatus = ClipboardBridgeContract.CLIP_STATUS_COMMIT_FAILED
            )
        } finally {
            payloadStore.deletePayload(appContext, request.eventId)
        }
    }

    /**
     * 执行剪贴 payload 提交的核心流程。
     *
     * @param request Provider commit_clip 请求参数。
     */
    private suspend fun commitInternal(request: ClipboardBridgeRequest): ClipboardBridgeResult {
        /** payload 读取结果；缺失和畸形要映射成稳定 resultCode。 */
        val readResult = payloadStore.readPayload(appContext, request.eventId)
        /** Provider 成功解析出的 payload；失败时提前返回，不继续入库。 */
        val payload = when (readResult) {
            ClipboardBridgeClipPayloadReadResult.Missing -> {
                return ClipboardBridgeResult.of(
                    resultCode = ClipboardBridgeContract.CODE_PAYLOAD_MISSING,
                    clipStatus = ClipboardBridgeContract.CLIP_STATUS_PAYLOAD_MISSING
                )
            }
            ClipboardBridgeClipPayloadReadResult.Invalid -> {
                return ClipboardBridgeResult.of(
                    resultCode = ClipboardBridgeContract.CODE_INVALID_PAYLOAD,
                    clipStatus = ClipboardBridgeContract.CLIP_STATUS_INVALID_PAYLOAD
                )
            }
            is ClipboardBridgeClipPayloadReadResult.Read -> readResult.payload
        }

        /** 从 payload 中解析出的可入库文本；为空时按空内容或不支持类型处理。 */
        val clipText = resolveText(payload)
        if (clipText.isNullOrBlank()) {
            /** 当前 payload 是否携带第一版支持的文本 MIME，用于区分空文本和 URI/图片等不支持类型。 */
            val hasTextMime = payload.mimeTypes.any { mimeType -> mimeType.startsWith("text/") }
            /** 当前 payload 是否更像不支持类型；有 MIME 但没有文本 MIME 时返回 unsupported。 */
            val unsupported = payload.mimeTypes.isNotEmpty() && !hasTextMime
            return ClipboardBridgeResult.of(
                resultCode = if (unsupported) {
                    ClipboardBridgeContract.CODE_UNSUPPORTED_CLIP_TYPE
                } else if (payload.mimeTypes.isEmpty()) {
                    ClipboardBridgeContract.CODE_NO_CLIP
                } else {
                    ClipboardBridgeContract.CODE_OK
                },
                clipStatus = if (unsupported) {
                    ClipboardBridgeContract.CLIP_STATUS_UNSUPPORTED_CLIP_TYPE
                } else if (payload.mimeTypes.isEmpty()) {
                    ClipboardBridgeContract.CLIP_STATUS_NO_CLIP
                } else {
                    ClipboardBridgeContract.CLIP_STATUS_DUPLICATE_OR_EMPTY
                },
                textLength = payload.text?.length,
                htmlLength = payload.htmlText?.length,
                mimeTypes = payload.mimeTypes
            )
        }

        /** 旧来源图标缓存；剪贴入库可先复用旧图标，图标独立协程后续再补齐。 */
        val sourceAppData = request.packageName
            ?.let { packageName -> clipRepository.get().loadSourceApp(packageName) }

        /** 旧来源图标文件是否真实存在；失效路径不能继续写回剪贴记录。 */
        val hasReadableCachedIcon = sourceAppData?.iconPath
            ?.takeIf { it.isNotBlank() }
            ?.let { iconPath -> File(iconPath).exists() }
            ?: false

        /** 本次剪贴记录可复用的旧图标路径。 */
        val iconPath = sourceAppData?.iconPath?.takeIf { hasReadableCachedIcon }

        /** 本次剪贴记录可复用的旧图标主色。 */
        val iconColor = sourceAppData?.primaryColor?.takeIf { hasReadableCachedIcon }

        /** 本次剪贴记录可复用的旧图标 hash。 */
        val cachedIconHash = sourceAppData?.iconHash?.takeIf { hasReadableCachedIcon }

        /** 文本入库结果；DuplicateOrEmpty 表示沿用现有去重语义没有新增记录。 */
        val processResult = clipHelper.get().processClipText(
            contentText = clipText,
            packageName = request.packageName,
            appName = request.appName,
            iconPath = iconPath,
            iconColor = iconColor,
            iconHash = cachedIconHash,
            capturedAtMillis = payload.capturedAtMillis
        )

        /** 本次是否真实写入或更新数据库剪贴记录。 */
        val clipCommitted = processResult == ClipProcessResult.Saved
        /** 对外暴露的剪贴处理状态，不包含剪贴正文。 */
        val clipStatus = if (clipCommitted) {
            ClipboardBridgeContract.CLIP_STATUS_SAVED
        } else {
            ClipboardBridgeContract.CLIP_STATUS_DUPLICATE_OR_EMPTY
        }

        logI(TAG) {
            "Provider 剪贴 payload 提交完成 eventId=${request.eventId} packageName=${request.packageName} " +
                "clipStatus=$clipStatus textLength=${clipText.length} htmlLength=${payload.htmlText?.length} " +
                "hasCachedIcon=${!iconPath.isNullOrBlank()}"
        }
        return ClipboardBridgeResult.of(
            resultCode = ClipboardBridgeContract.CODE_OK,
            saved = clipCommitted,
            readClip = true,
            clipCommitted = clipCommitted,
            clipStatus = clipStatus,
            textLength = clipText.length,
            htmlLength = payload.htmlText?.length,
            mimeTypes = payload.mimeTypes,
            iconStatus = if (!iconPath.isNullOrBlank()) {
                ClipboardBridgeContract.ICON_STATUS_REUSED
            } else {
                ClipboardBridgeContract.ICON_STATUS_PLACEHOLDER
            }
        )
    }

    /**
     * 从 payload 解析可入库文本。
     *
     * @param payload Shizuku 写入的剪贴 payload。
     */
    private fun resolveText(payload: ClipboardBridgeClipPayload): String? {
        /** 普通文本优先；非空时不再解析 HTML，避免保留富文本原文。 */
        val plainText = payload.text?.trim()?.takeIf { text -> text.isNotBlank() }
        if (plainText != null) {
            return plainText
        }

        /** HTML fallback 转出的纯文本；原始 HTML 不持久化也不输出日志。 */
        val htmlText = payload.htmlText?.takeIf { html -> html.isNotBlank() } ?: return null
        /** Jsoup 转换后的纯文本；为空白时交给调用方按空内容处理。 */
        val convertedText = Jsoup.parse(htmlText).text().trim()
        logI(TAG) {
            "Provider 剪贴 payload HTML fallback htmlLength=${htmlText.length} convertedLength=${convertedText.length}"
        }
        return convertedText.takeIf { text -> text.isNotBlank() }
    }
}
