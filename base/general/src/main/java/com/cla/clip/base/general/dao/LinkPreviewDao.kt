package com.cla.clip.base.general.dao

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Upsert

/**
 * 链接的预览信息。
 * 独立存在，多个 ClipData 可以共享同一条链接预览。
 * 删除 ClipData 不会删除此记录。
 *
 * @param link 链接 URL（主键）
 * @param title 链接预览的标题。
 * @param description 链接预览的描述。
 * @param imageUrl 链接预览的图片URL。
 * @param siteName 链接预览的网站名。
 */
@Entity(
    tableName = "link_previews",
    indices = [
        Index(value = ["link"]),
    ]
)
data class LinkPreviewData(
    @PrimaryKey
    @ColumnInfo(name = "link")
    val link: String,
    @ColumnInfo(name = "title")
    val title: String?,
    @ColumnInfo(name = "description")
    val description: String?,
    @ColumnInfo(name = "image_url")
    val imageUrl: String?,
    @ColumnInfo(name = "site_name")
    val siteName: String?,
)

@Dao
interface LinkPreviewDao {

    /**
     * 更新或插入一个LinkPreviewData条目。这是所有数据写入的基础，使用更高效的 @Upsert。
     * @param LinkPreviewData 要更新或插入的LinkPreviewData对象。
     * @return 更新或插入条目的ID。
     */
    @Upsert
    suspend fun upsert(data: LinkPreviewData): Long

    /**
     * 根据包名查询对应的LinkPreviewData
     * @param link 要查询的链接。
     * @return 对应的LinkPreviewData对象，如果不存在则返回null。
     */
    @Query("SELECT * FROM link_previews WHERE link = :link LIMIT 1")
    suspend fun loadByLink(link: String): LinkPreviewData?
}