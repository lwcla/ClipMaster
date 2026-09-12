package com.cla.clip.feature.torrent.search.backup

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** schema v4 中 TorrentSearch entry、计数和恢复报告的唯一稳定命名源。 */
internal object TorrentSearchBackupContract {
    /** 书签 JSONL entry。 */
    const val BOOKMARK_PATH = "data/features/torrent_search/bookmarks_v1.jsonl"
    /** 搜索历史 JSONL entry。 */
    const val HISTORY_PATH = "data/features/torrent_search/histories_v1.jsonl"
    /** 已查看 JSONL entry。 */
    const val VIEWED_PATH = "data/features/torrent_search/viewed_v1.jsonl"
    /** 非敏感设置 JSON entry。 */
    const val SETTINGS_PATH = "data/features/torrent_search/settings_v1.json"
    /** 书签 manifest 数量稳定 key。 */
    const val BOOKMARK_COUNT_KEY = "torrent_search_bookmark_count"
    /** 搜索历史 manifest 数量稳定 key。 */
    const val HISTORY_COUNT_KEY = "torrent_search_search_history_count"
    /** 已查看 manifest 数量稳定 key。 */
    const val VIEWED_COUNT_KEY = "torrent_search_viewed_item_count"
    /** 设置 manifest 数量稳定 key。 */
    const val SETTINGS_COUNT_KEY = "torrent_search_setting_count"
    /** 书签恢复报告稳定 code。 */
    const val BOOKMARK_REPORT_CODE = "torrent_search_bookmarks"
    /** 搜索历史恢复报告稳定 code。 */
    const val HISTORY_REPORT_CODE = "torrent_search_search_histories"
    /** 已查看恢复报告稳定 code。 */
    const val VIEWED_REPORT_CODE = "torrent_search_viewed_items"
    /** 设置恢复报告稳定 code。 */
    const val SETTINGS_REPORT_CODE = "torrent_search_settings"
    /** 旧磁力备份只用于报告不兼容跳过，绝不迁移。 */
    const val LEGACY_INCOMPATIBLE_REPORT_CODE = "torrent_search_legacy_incompatible"
    /** 旧磁力 v1/v2 备份 entry 精确白名单。 */
    val legacyPaths: Set<String> = setOf(
        "data/magnet_search_histories.json",
        "data/magnet_search_histories.jsonl",
        "data/magnet_download_records.json",
        "data/magnet_download_records.jsonl",
    )
}

/** TorrentSearch 书签备份 v1；字段名是独立于 Room 与 Kotlin 命名的稳定外部协议。 */
@Serializable
internal data class TorrentSearchBackupBookmarkV1(
    /** 40 位小写十六进制 torrent 身份，也是幂等恢复主键。 */
    @SerialName("info_hash") val infoHash: String,
    /** 用户可见名称快照；允许跨安装恢复书签列表。 */
    @SerialName("name") val name: String,
    /** 产出结果的稳定 Provider ID，仅用于展示来源，不恢复 Provider 配置。 */
    @SerialName("provider_id") val providerId: String,
    /** Provider 展示名快照。 */
    @SerialName("provider_name") val providerName: String,
    /** 上游大小展示文本；未知时为空。 */
    @SerialName("size_text") val sizeText: String? = null,
    /** 做种数快照；未知时为空。 */
    @SerialName("seeders") val seeders: Long? = null,
    /** 连接节点数快照；未知时为空。 */
    @SerialName("peers") val peers: Long? = null,
    /** 上传时间 UTC epochMillis；未知时为空。 */
    @SerialName("upload_epoch_millis") val uploadEpochMillis: Long? = null,
    /** 分类稳定 code；未知时为空。 */
    @SerialName("category_code") val categoryCode: String? = null,
    /** 详情页地址快照；不包含 Provider 凭据。 */
    @SerialName("description_page_url") val descriptionPageUrl: String? = null,
    /** 可直接打开的磁力链接快照。 */
    @SerialName("magnet_uri") val magnetUri: String? = null,
    /** torrent 文件下载地址快照。 */
    @SerialName("file_download_link") val fileDownloadLink: String? = null,
    /** 最近一次加入或刷新书签的 UTC epochMillis，用于恢复冲突判断。 */
    @SerialName("bookmarked_at_epoch_millis") val bookmarkedAtEpochMillis: Long,
)

/** TorrentSearch 搜索历史备份 v1；只保存清洗后的展示值和稳定去重键。 */
@Serializable
internal data class TorrentSearchBackupHistoryV1(
    /** 规范化查询，是跨安装幂等恢复主键。 */
    @SerialName("normalized_query") val normalizedQuery: String,
    /** 用户最后提交的清洗后展示写法。 */
    @SerialName("query") val query: String,
    /** 最近提交时间 UTC epochMillis，用于恢复冲突判断。 */
    @SerialName("updated_at_epoch_millis") val updatedAtEpochMillis: Long,
)

/** TorrentSearch 已查看备份 v1；不复制标题、URL 或其他内容。 */
@Serializable
internal data class TorrentSearchBackupViewedV1(
    /** 40 位小写十六进制 torrent 身份，也是幂等恢复主键。 */
    @SerialName("info_hash") val infoHash: String,
    /** 最近查看时间 UTC epochMillis，用于恢复冲突判断。 */
    @SerialName("viewed_at_epoch_millis") val viewedAtEpochMillis: Long,
)

/** TorrentSearch 非敏感设置白名单 v1；新增本机状态时不得默认加入此协议。 */
@Serializable
internal data class TorrentSearchBackupSettingsV1(
    /** 子协议版本，独立于总备份 schema v4。 */
    @SerialName("version") val version: Int = TORRENT_SEARCH_SETTINGS_VERSION,
    /** 默认排序字段稳定 code。 */
    @SerialName("sort_criteria") val sortCriteriaCode: String,
    /** 默认排序方向稳定 code。 */
    @SerialName("sort_order") val sortOrderCode: String,
)

/** 当前 TorrentSearch 设置子协议版本。 */
internal const val TORRENT_SEARCH_SETTINGS_VERSION = 1
