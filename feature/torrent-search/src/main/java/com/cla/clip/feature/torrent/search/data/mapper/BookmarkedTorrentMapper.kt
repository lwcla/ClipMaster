package com.cla.clip.feature.torrent.search.data.mapper

import com.cla.clip.feature.torrent.search.data.model.BookmarkedTorrent
import com.cla.clip.feature.torrent.search.model.contract.Torrent

/** 将搜索/浏览结果转换为可持久化书签快照。 */
internal fun Torrent.toBookmarkedTorrent(): BookmarkedTorrent = BookmarkedTorrent(
    infoHash = infoHash,
    name = name,
    providerId = providerId,
    providerName = providerName,
    size = size,
    seeders = seeders,
    peers = peers,
    uploadEpochMillis = uploadEpochMillis,
    category = category,
    descriptionPageUrl = descriptionPageUrl,
    magnetUri = magnetUri,
    fileDownloadLink = fileDownloadLink,
)

/** 将书签快照恢复为 06B 结果卡和详情内存选择态共用的 Torrent。 */
internal fun BookmarkedTorrent.toTorrent(): Torrent = Torrent(
    infoHash = infoHash,
    name = name,
    providerId = providerId,
    providerName = providerName,
    size = size,
    seeders = seeders,
    peers = peers,
    uploadEpochMillis = uploadEpochMillis,
    category = category,
    descriptionPageUrl = descriptionPageUrl,
    magnetUri = magnetUri,
    fileDownloadLink = fileDownloadLink,
)
