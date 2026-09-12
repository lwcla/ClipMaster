package com.cla.clip.feature.torrent.search.ui.search

import com.cla.clip.feature.torrent.search.model.contract.SortCriteria
import com.cla.clip.feature.torrent.search.model.contract.SortOrder
import com.cla.clip.feature.torrent.search.model.contract.Torrent
import java.util.Locale

/** 搜索结果纯排序器；未知字段始终排在已知值后，避免降序时空值浮到顶部。 */
internal object TorrentResultSorter {
    /** 按稳定排序枚举返回新列表，不改变 Provider 原始结果。 */
    fun sort(
        /** 已按 infoHash 去重的候选结果。 */
        torrents: List<Torrent>,
        /** 页面选择的排序字段。 */
        criteria: SortCriteria,
        /** 页面选择的排序方向。 */
        order: SortOrder,
    ): List<Torrent> {
        return torrents.sortedWith { /** left 是当前比较左值，right 是当前比较右值。 */ left, right ->
            compareTorrent(left, right, criteria, order)
        }
    }

    /** 比较两条结果；infoHash 作为最终稳定 tie-breaker。 */
    private fun compareTorrent(
        /** 左侧候选。 */
        left: Torrent,
        /** 右侧候选。 */
        right: Torrent,
        /** 当前排序字段。 */
        criteria: SortCriteria,
        /** 当前排序方向。 */
        order: SortOrder,
    ): Int {
        /** primaryComparison 根据字段选择可空安全比较。 */
        val primaryComparison = when (criteria) {
            SortCriteria.NAME -> compareKnown(left.name.lowercase(Locale.ROOT), right.name.lowercase(Locale.ROOT), order)
            SortCriteria.SEEDERS -> compareNullable(left.seeders?.toLong(), right.seeders?.toLong(), order)
            SortCriteria.PEERS -> compareNullable(left.peers?.toLong(), right.peers?.toLong(), order)
            SortCriteria.FILE_SIZE -> compareNullable(parseFileSizeBytes(left.size), parseFileSizeBytes(right.size), order)
            SortCriteria.DATE -> compareNullable(left.uploadEpochMillis, right.uploadEpochMillis, order)
        }
        return primaryComparison.takeIf { /** comparison 是当前字段比较结果。 */ comparison -> comparison != 0 }
            ?: left.infoHash.compareTo(right.infoHash)
    }

    /** 比较两个已知值，并只对主值应用升降序。 */
    private fun <T : Comparable<T>> compareKnown(left: T, right: T, order: SortOrder): Int {
        /** comparison 是自然升序结果。 */
        val comparison = left.compareTo(right)
        return if (order == SortOrder.ASCENDING) comparison else -comparison
    }

    /** 比较两个可空值；无论方向如何，未知值都排在已知值后。 */
    private fun <T : Comparable<T>> compareNullable(left: T?, right: T?, order: SortOrder): Int {
        if (left == null) return if (right == null) 0 else 1
        if (right == null) return -1
        return compareKnown(left, right, order)
    }

    /** 解析常见 B/KB/MB/GB/TB 展示文本用于本地排序，未知格式返回 null。 */
    private fun parseFileSizeBytes(
        /** Provider 返回的短大小展示文本。 */
        sizeText: String?,
    ): Double? {
        /** match 提取数值和单位，不接受额外正文。 */
        val match = SIZE_PATTERN.matchEntire(sizeText?.trim().orEmpty()) ?: return null
        /** amount 是有限且非负的十进制数值。 */
        val amount = match.groupValues[1].replace(',', '.').toDoubleOrNull()?.takeIf {
            /** value 是待检查有限性和非负边界的大小数值。 */ value ->
            value.isFinite() && value >= 0.0
        } ?: return null
        /** multiplier 把二进制/常见十进制展示单位统一映射为字节量级。 */
        val multiplier = when (match.groupValues[2].uppercase(Locale.ROOT)) {
            "B" -> 1.0
            "KB", "KIB" -> 1024.0
            "MB", "MIB" -> 1024.0 * 1024.0
            "GB", "GIB" -> 1024.0 * 1024.0 * 1024.0
            "TB", "TIB" -> 1024.0 * 1024.0 * 1024.0 * 1024.0
            else -> return null
        }
        return amount * multiplier
    }

    /** 文件大小严格语法，只允许有限数字、可选空白和已知单位。 */
    private val SIZE_PATTERN = Regex("^([0-9]+(?:[.,][0-9]+)?)\\s*(B|KB|KiB|MB|MiB|GB|GiB|TB|TiB)$", RegexOption.IGNORE_CASE)
}
