package com.cla.clip.base.general.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import com.cla.clip.base.general.entity.ClipData
import kotlinx.coroutines.flow.Flow

@Dao
interface ClipDao {

    /**
     * 更新或插入一个Clip条目。这是所有数据写入的基础，使用更高效的 @Upsert。
     * @param clip 要更新或插入的Clip对象。
     * @return 更新或插入条目的ID。
     */
    @Upsert
    suspend fun upsertClip(clip: ClipData): Long

    // 1. 基础查询：查找是否存在相同内容
    @Query("SELECT * FROM clips WHERE content = :content AND is_latest = 1 LIMIT 1")
    suspend fun getClipByContent(content: String): ClipData?

    /**
     * 将指定分组中的所有条目的 isLatest 标志设置为 false。
     * 这是在插入新版本之前必须调用的步骤。
     * @param groupId 要重置的分组ID。
     */
    @Query("UPDATE clips SET is_latest = 0 WHERE group_id = :groupId")
    suspend fun resetLatestFlagForGroup(groupId: Long)

    /**
     * 核心事务：新增一个全新的剪贴板条目。
     * 它首先插入条目，然后用返回的ID更新自己的groupId，完成自关联。
     * @param clip 不含ID和groupId的初始Clip对象。
     */
    @Transaction
    suspend fun addNewClip(clip: ClipData) {
        // 先尝试查找旧数据
        val existingClip = getClipByContent(clip.content)

        if (existingClip != null) {
            // === 情况 A：数据库有相同 content ===
            // 使用 newClip 的所有数据，但覆盖回旧数据的 id 和 groupId
            val clipToUpdate = clip.copy(
                id = existingClip.id,
                groupId = existingClip.groupId,
                isLatest = existingClip.isPinned,
            )
            // 执行更新
            upsertClip(clipToUpdate)
        } else {
            // 1. 先插入条目，此时groupId是临时的（通常为0或默认值）
            val newId = upsertClip(clip.copy(id = 0)) // 确保是插入操作
            // 2. 使用新生成的ID更新该条目的groupId，形成一个新分组的“根”
            val rootClip = clip.copy(id = newId, groupId = newId)
            upsertClip(rootClip)
        }
    }

    /**
     * 核心事务：为一个现有的剪贴板条目创建一个新的编辑历史版本。
     * 1. 将该分组所有旧版本的 isLatest 标志置为false。
     * 2. 插入带有新内容的新版本，其 isLatest 默认为true。
     * @param newVersionClip 包含新内容和正确groupId的Clip对象，其id必须为0以确保是新增。
     */
    @Transaction
    suspend fun createNewVersionForClip(newVersionClip: ClipData) {
        resetLatestFlagForGroup(newVersionClip.groupId)
        upsertClip(newVersionClip.copy(id = 0)) // 确保是插入操作
    }

    /**
     * 获取主屏幕上显示的剪贴板列表。
     * 只获取每个分组中最新的、未被置顶的条目，按时间戳降序排列。
     * @return 一个可观察的Clip列表Flow。
     */
    @Query("SELECT * FROM clips WHERE is_latest = 1 AND is_pinned = 0 ORDER BY timestamp DESC")
    fun getLatestClips(): Flow<List<ClipData>>

    /**
     * 获取所有置顶的剪贴板列表。
     * 只获取每个分组中最新的、被置顶的条目，按时间戳降序排列。
     * @return 一个可观察的置顶Clip列表Flow。
     */
    @Query("SELECT * FROM clips WHERE is_latest = 1 AND is_pinned = 1 ORDER BY timestamp DESC")
    fun getPinnedClips(): Flow<List<ClipData>>

    /**
     * 根据分组ID获取一个条目的所有历史版本（包括最新版），按时间戳降序排列。
     * @param groupId 要查询的分组ID。
     * @return 该分组的所有历史记录列表。
     */
    @Query("SELECT * FROM clips WHERE group_id = :groupId ORDER BY timestamp DESC")
    suspend fun getHistoryForGroup(groupId: Long): List<ClipData>

    /**
     * 核心搜索功能：在FTS虚拟表中进行全文检索。
     * @param query 用户的搜索词。FTS5会自动处理分词。
     * @return 匹配的Clip列表，无论新旧都会被返回。
     */
    @Query("SELECT c.* FROM clips c JOIN clips_fts fts ON c.id = fts.rowid WHERE fts.clips_fts MATCH :query")
    fun searchAllClips(query: String): Flow<List<ClipData>>

    /**
     * 删除一个分组下的所有历史记录。
     * @param groupId 要删除的分组ID。
     */
    @Query("DELETE FROM clips WHERE group_id = :groupId")
    suspend fun deleteClipGroup(groupId: Long)

    /**
     * 清空所有剪贴板数据。
     */
    @Query("DELETE FROM clips")
    suspend fun clearAll()
}

///**
// * 类型转换器，用于让Room数据库能够存储和读取自定义的枚举类型。
// */
//class Converters {
//    /**
//     * 将 [ClipType] 枚举转换为一个字符串，以便存入数据库。
//     * @param clipType 要转换的枚举实例。
//     * @return 枚举的名称字符串。
//     */
//    @TypeConverter
//    fun fromClipType(clipType: ClipType) = clipType.name
//
//    /**
//     * 将数据库中的字符串转换回 [ClipType] 枚举。
//     * @param value 从数据库读取的字符串。
//     * @return 对应的枚举实例。
//     */
//    @TypeConverter
//    fun toClipType(value: String) = ClipType.valueOf(value)
//}