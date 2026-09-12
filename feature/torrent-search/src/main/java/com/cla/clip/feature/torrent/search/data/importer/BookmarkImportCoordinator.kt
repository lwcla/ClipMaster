package com.cla.clip.feature.torrent.search.data.importer

import com.cla.clip.feature.torrent.search.data.repository.BookmarkRepository
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first

/** 单次导入持久化后的低敏计数。 */
internal data class BookmarkPersistenceReport(
    /** 成功写入的新书签数量。 */
    val importedCount: Int,
    /** 解析时因坏条目跳过的数量。 */
    val skippedCount: Int,
    /** 文件内或数据库内已存在的稳定身份数量。 */
    val duplicateCount: Int,
    /** 单项数据库写入失败但批次继续的数量。 */
    val writeFailureCount: Int,
)

/** 将 05C 解析结果逐项写入 Repository，隔离单项失败并传播取消。 */
internal class BookmarkImportCoordinator @Inject constructor(
    /** 书签稳定身份与 Room 写入边界。 */
    private val repository: BookmarkRepository,
) {
    /** 持久化全部合法项并返回固定计数，不接触 URI 或 ContentResolver。 */
    suspend fun persist(parsed: BookmarkImportResult.Success): BookmarkPersistenceReport {
        /** importedCount 累计新写入数量。 */
        var importedCount = 0
        /** duplicateCount 合并文件内重复与数据库已有身份。 */
        var duplicateCount = parsed.duplicateCount
        /** writeFailureCount 累计单项失败但不终止批次。 */
        var writeFailureCount = 0
        /** 每个合法书签独立查重和写入，单项失败不终止批次。 */
        for (bookmark in parsed.bookmarks) {
            try {
                if (repository.observeBookmarked(bookmark.infoHash).first()) {
                    duplicateCount += 1
                } else if (repository.save(bookmark)) {
                    importedCount += 1
                } else {
                    writeFailureCount += 1
                }
            /** 取消必须终止整个导入批次并向上传播。 */
            } catch (cancellation: CancellationException) {
                throw cancellation
            /** 介质异常只累计固定失败计数并继续下一项。 */
            } catch (exception: Exception) {
                writeFailureCount += 1
            }
        }
        return BookmarkPersistenceReport(importedCount, parsed.skippedCount, duplicateCount, writeFailureCount)
    }
}
