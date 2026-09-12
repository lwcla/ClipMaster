package com.cla.clip.feature.torrent.search.data.model

import com.cla.clip.feature.torrent.search.model.contract.Category
import com.cla.clip.feature.torrent.search.provider.ProviderId

/** 页面与备份层消费的书签领域模型，不暴露 Room 注解或列名。 */
internal data class BookmarkedTorrent(
    /** 40 位小写十六进制 infoHash 稳定身份。 */
    val infoHash: String,
    /** 用户可见 torrent 名称。 */
    val name: String,
    /** 产出结果的稳定 Provider ID。 */
    val providerId: ProviderId,
    /** Provider 展示名快照。 */
    val providerName: String,
    /** 上游展示格式的大小文本。 */
    val size: String? = null,
    /** 做种数快照。 */
    val seeders: UInt? = null,
    /** 连接节点数快照。 */
    val peers: UInt? = null,
    /** 上传时间 UTC epochMillis。 */
    val uploadEpochMillis: Long? = null,
    /** 稳定分类。 */
    val category: Category? = null,
    /** 可选详情地址。 */
    val descriptionPageUrl: String? = null,
    /** 可选磁力链接。 */
    val magnetUri: String? = null,
    /** 可选 torrent 文件下载地址。 */
    val fileDownloadLink: String? = null,
    /** 最近加入或刷新书签的 UTC epochMillis；保存时由 Repository 覆盖。 */
    val bookmarkedAtEpochMillis: Long = 0L,
)

/** 页面消费的安全搜索历史领域模型。 */
internal data class TorrentSearchHistory(
    /** 用户最后一次提交的安全展示文本。 */
    val query: String,
    /** Locale.ROOT 小写的稳定去重键。 */
    val normalizedQuery: String,
    /** 最近提交时间 UTC epochMillis。 */
    val updatedAtEpochMillis: Long,
)

/** 页面消费的已查看领域模型。 */
internal data class ViewedTorrent(
    /** 40 位小写十六进制 infoHash 稳定身份。 */
    val infoHash: String,
    /** 最近查看时间 UTC epochMillis。 */
    val viewedAtEpochMillis: Long,
)
