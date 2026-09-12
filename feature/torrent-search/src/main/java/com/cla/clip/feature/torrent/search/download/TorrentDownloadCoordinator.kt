package com.cla.clip.feature.torrent.search.download

import com.cla.clip.feature.torrent.search.network.ResponseTooLargeException
import com.cla.clip.feature.torrent.search.network.TorrentSearchNetworkClient
import com.cla.clip.feature.torrent.search.provider.builtin.common.MagnetInfoHashParser
import com.cla.clip.feature.torrent.search.security.TorrentFileValidator
import com.cla.clip.feature.torrent.search.security.TorrentValidationResult
import java.io.ByteArrayInputStream
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** 下载准备失败固定协议，不携带 URL、文件名或异常正文。 */
internal enum class TorrentDownloadFailureReason(
    /** 低敏日志与 UI 使用的稳定 code。 */
    val code: String,
) {
    /** 下载地址不是合法 HTTP/HTTPS。 */
    INVALID_URL("invalid_url"),
    /** 网络或 HTTP 请求失败。 */
    DOWNLOAD_FAILED("download_failed"),
    /** 二进制响应超过 20 MiB。 */
    TOO_LARGE("too_large"),
    /** 响应不是合法 Torrent。 */
    INVALID_TORRENT("invalid_torrent"),
    /** Torrent infoHash 与当前详情不一致。 */
    IDENTITY_MISMATCH("identity_mismatch"),
    /** SAF 目标文档无法完整写出。 */
    SAVE_FAILED("save_failed"),
}

/** 下载准备结果；Ready 字节仅允许短暂交给一次性 SAF 写出。 */
internal sealed interface TorrentDownloadPreparation {
    /** 已验证且身份一致的 torrent 文件。 */
    data class Ready(
        /** 完整且不超过 20 MiB 的原始 torrent 字节。 */
        val bytes: ByteArray,
        /** 已净化并带 .torrent 后缀的建议文件名。 */
        val suggestedFileName: String,
        /** 从原始 info 字典计算的规范 infoHash。 */
        val infoHash: String,
    ) : TorrentDownloadPreparation

    /** 固定分类失败。 */
    data class Failure(
        /** 页面与日志可使用的稳定原因。 */
        val reason: TorrentDownloadFailureReason,
    ) : TorrentDownloadPreparation
}

/** Torrent 下载低敏日志边界。 */
internal fun interface TorrentDownloadLogger {
    /** 只记录固定阶段、原因和可选字节数。 */
    fun log(operationCode: String, stateCode: String, reasonCode: String?, byteCount: Int?)
}

/** 详情状态机依赖的下载准备窄接口，便于隔离网络与 Bencode 实现。 */
internal fun interface TorrentDownloadPreparer {
    /** 下载并校验 Torrent，成功时返回仅可短暂驻留内存的字节。 */
    suspend fun prepare(downloadUrl: String, expectedInfoHash: String, displayName: String): TorrentDownloadPreparation
}

/** 未装配测试的固定拒绝实现，不执行网络或文件 IO。 */
internal object NoOpTorrentDownloadPreparer : TorrentDownloadPreparer {
    /** 稳定返回 URL 不可用，防止测试默认依赖意外联网。 */
    override suspend fun prepare(downloadUrl: String, expectedInfoHash: String, displayName: String): TorrentDownloadPreparation =
        TorrentDownloadPreparation.Failure(TorrentDownloadFailureReason.INVALID_URL)
}

/** 默认无日志实现，测试和未装配场景不会触碰 Android Log。 */
private object NoOpTorrentDownloadLogger : TorrentDownloadLogger {
    /** 丢弃固定字段。 */
    override fun log(operationCode: String, stateCode: String, reasonCode: String?, byteCount: Int?) = Unit
}

/** 下载、20 MiB 有界读取、Bencode 校验和当前详情身份比对的编排器。 */
internal class TorrentDownloadCoordinator(
    /** 复用 TorrentSearch 专用 OkHttp/DNS/同源跳转边界。 */
    private val networkClient: TorrentSearchNetworkClient,
    /** 下载与 Bencode 扫描离开 Main。 */
    private val ioDispatcher: CoroutineDispatcher,
    /** 只接受固定字段的日志出口。 */
    private val logger: TorrentDownloadLogger = NoOpTorrentDownloadLogger,
) : TorrentDownloadPreparer {
    /** 下载并验证文件；失败前绝不打开 SAF 输出流。 */
    override suspend fun prepare(
        /** 当前详情提供的 HTTP/HTTPS torrent 地址。 */
        downloadUrl: String,
        /** 当前详情的规范 infoHash。 */
        expectedInfoHash: String,
        /** 当前详情名称，仅用于本机建议文件名。 */
        displayName: String,
    ): TorrentDownloadPreparation = withContext(ioDispatcher) {
        logger.log("torrent_download", "started", null, null)
        /** normalizedExpectedHash 使用已有 btih 规范器拒绝损坏详情身份。 */
        val normalizedExpectedHash = MagnetInfoHashParser.normalize(expectedInfoHash)
            ?: return@withContext failed(TorrentDownloadFailureReason.IDENTITY_MISMATCH)
        /** parsedUrl 复用 OkHttp 语义接受任意合法 HTTP/HTTPS 公网、私网或回环地址。 */
        val parsedUrl = downloadUrl.trim().toHttpUrlOrNull()
        if (parsedUrl == null || (parsedUrl.scheme != "http" && parsedUrl.scheme != "https")) {
            return@withContext failed(TorrentDownloadFailureReason.INVALID_URL)
        }
        /** normalizedUrl 保留规范化 host、端口与编码后交给共享网络客户端。 */
        val normalizedUrl = parsedUrl.toString()
        /** bytes 只在内存中保留到 SAF 写出结束。 */
        val bytes = try {
            networkClient.getBytes(normalizedUrl, TorrentFileValidator.MAX_TORRENT_BYTES)
        /** cancellation 记录后保持结构化取消。 */
        } catch (cancellation: CancellationException) {
            logger.log("torrent_download", "cancelled", null, null)
            throw cancellation
        /** tooLarge 使用明确固定分类。 */
        } catch (tooLarge: ResponseTooLargeException) {
            return@withContext failed(TorrentDownloadFailureReason.TOO_LARGE)
        /** failure 不读取网络异常 message。 */
        } catch (failure: Exception) {
            return@withContext failed(TorrentDownloadFailureReason.DOWNLOAD_FAILED)
        }
        /** validation 对完整原始字节执行严格 Bencode 与 infoHash 校验。 */
        val validation = ByteArrayInputStream(bytes).use(TorrentFileValidator::validate)
        /** valid 只从正式成功结果取得。 */
        val valid = validation as? TorrentValidationResult.Valid
            ?: return@withContext failed(TorrentDownloadFailureReason.INVALID_TORRENT, bytes.size)
        if (valid.infoHash != normalizedExpectedHash) {
            return@withContext failed(TorrentDownloadFailureReason.IDENTITY_MISMATCH, bytes.size)
        }
        /** suggestedFileName 只用于 SAF UI，不进入日志。 */
        val suggestedFileName = TorrentDownloadFileNameFormatter.format(displayName)
        logger.log("torrent_download", "completed", null, bytes.size)
        TorrentDownloadPreparation.Ready(bytes, suggestedFileName, valid.infoHash)
    }

    /** 生成固定失败结果并输出低敏日志。 */
    private fun failed(
        /** 稳定失败分类。 */
        reason: TorrentDownloadFailureReason,
        /** 已读取字节数；地址失败或网络失败时为空。 */
        byteCount: Int? = null,
    ): TorrentDownloadPreparation.Failure {
        logger.log("torrent_download", "failed", reason.code, byteCount)
        return TorrentDownloadPreparation.Failure(reason)
    }
}

/** 生成 SAF 建议文件名的纯格式化器。 */
internal object TorrentDownloadFileNameFormatter {
    /** Windows/DocumentProvider 常见禁用字符与控制字符。 */
    private val forbiddenCharacters = Regex("[\\\\/:*?\"<>|\\p{Cc}]")
    /** 不含后缀的最大字符数，给 Provider 可能追加的显示信息留余量。 */
    private const val MAX_BASE_NAME_CHARS = 180

    /** 清理文件名并保证恰好一个可识别 .torrent 后缀。 */
    fun format(
        /** 详情展示名，不可信且禁止进入日志。 */
        displayName: String,
    ): String {
        /** cleanedBaseName 替换禁用字符并移除 Windows 尾随点/空格。 */
        val cleanedBaseName = displayName
            .replace(forbiddenCharacters, "_")
            .trim()
            .trimEnd('.', ' ')
            .take(MAX_BASE_NAME_CHARS)
            .ifEmpty { "download" }
        return if (cleanedBaseName.lowercase(Locale.US).endsWith(".torrent")) cleanedBaseName else "$cleanedBaseName.torrent"
    }
}
