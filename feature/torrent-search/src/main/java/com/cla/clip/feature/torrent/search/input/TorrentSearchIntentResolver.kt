package com.cla.clip.feature.torrent.search.input

import android.app.SearchManager
import android.content.Intent
import com.cla.clip.feature.torrent.search.EXTRA_TORRENT_SEARCH_INITIAL_QUERY
import com.cla.clip.feature.torrent.search.EXTRA_TORRENT_SEARCH_LAUNCH_SOURCE
import com.cla.clip.feature.torrent.search.api.TorrentSearchLaunchSource

/** 从 Android Intent 抽出的无平台行为输入，便于独立验证 action 与 extra 优先级。 */
internal data class TorrentSearchIntentPayload(
    /** Android action；null 表示宿主显式启动或缺失动作。 */
    val action: String? = null,
    /** Intent MIME 类型；SEND/PROCESS_TEXT 只接受 text/plain。 */
    val mimeType: String? = null,
    /** feature 显式启动传入的候选查询。 */
    val explicitQuery: String? = null,
    /** feature 显式启动传入的稳定来源 code。 */
    val explicitLaunchSourceCode: String? = null,
    /** ACTION_SEARCH 的 SearchManager.QUERY 文本。 */
    val searchQuery: String? = null,
    /** ACTION_SEND 的 EXTRA_TEXT 文本。 */
    val sharedText: String? = null,
    /** ACTION_PROCESS_TEXT 的 EXTRA_PROCESS_TEXT 文本。 */
    val processedText: String? = null,
)

/** Activity 与后续搜索页共享的单次启动快照，不进入持久化或外部序列化协议。 */
internal data class TorrentSearchLaunchRequest(
    /** 删除控制字符并限制到 512 字符后的页面预填文本。 */
    val query: String,
    /** 当前输入是否允许页面在隐私披露通过后自动搜索。 */
    val canAutoSearch: Boolean,
    /** 原始安全文本是否因超过上限而被截断。 */
    val wasTruncated: Boolean,
    /** 解析后的稳定启动来源；系统 action 不信任外部伪造来源。 */
    val launchSource: TorrentSearchLaunchSource,
)

/** 集中解析显式启动和三个系统文本入口，确保所有路径复用同一输入边界。 */
internal object TorrentSearchIntentResolver {
    /** SEND/PROCESS_TEXT 唯一允许消费的 MIME，避免图片或二进制分享误入查询链路。 */
    private const val MIME_TEXT_PLAIN = "text/plain"

    /** 按 action 选择可信文本字段和稳定来源，再统一执行控制字符与长度规范化。 */
    fun resolve(
        /** 从 Android Intent 提取的原始字段；所有字符串都按不可信输入处理。 */
        payload: TorrentSearchIntentPayload,
    ): TorrentSearchLaunchRequest {
        /** selectedInput 是当前 action 唯一允许读取的文本与来源组合。 */
        val selectedInput = when (payload.action) {
            Intent.ACTION_SEARCH -> payload.searchQuery to TorrentSearchLaunchSource.ActionSearch
            Intent.ACTION_SEND -> payload.sharedText.takeIf { payload.hasPlainTextMimeType() } to TorrentSearchLaunchSource.Send
            Intent.ACTION_PROCESS_TEXT -> payload.processedText.takeIf { payload.hasPlainTextMimeType() } to TorrentSearchLaunchSource.ProcessText
            else -> payload.explicitQuery to TorrentSearchLaunchSource.fromCode(payload.explicitLaunchSourceCode)
        }
        /** normalizedInput 复用功能内唯一的控制字符、空白和 512 字符规则。 */
        val normalizedInput = TorrentSearchInputNormalizer.normalize(selectedInput.first)
        return TorrentSearchLaunchRequest(
            query = normalizedInput.query,
            canAutoSearch = normalizedInput.canAutoSearch,
            wasTruncated = normalizedInput.wasTruncated,
            launchSource = selectedInput.second,
        )
    }

    /** MIME 比较忽略大小写，但不接受带参数或其他 text 子类型，保持 Manifest 契约精确。 */
    private fun TorrentSearchIntentPayload.hasPlainTextMimeType(): Boolean {
        return mimeType.equals(MIME_TEXT_PLAIN, ignoreCase = true)
    }
}

/** 薄 Android 适配器只读取系统定义 extra；动作选择和安全决策交给纯 resolver。 */
internal fun Intent.toTorrentSearchIntentPayload(): TorrentSearchIntentPayload {
    return TorrentSearchIntentPayload(
        action = action,
        mimeType = type,
        explicitQuery = getStringExtra(EXTRA_TORRENT_SEARCH_INITIAL_QUERY),
        explicitLaunchSourceCode = getStringExtra(EXTRA_TORRENT_SEARCH_LAUNCH_SOURCE),
        searchQuery = getStringExtra(SearchManager.QUERY),
        sharedText = getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString(),
        processedText = getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)?.toString(),
    )
}
