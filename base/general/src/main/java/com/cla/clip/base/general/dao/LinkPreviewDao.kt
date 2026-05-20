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
    /** 预览对应的原始链接 URL，作为主键；多个剪贴板记录可指向同一个预览。 */
    val link: String,

    @ColumnInfo(name = "title")
    /** 网页标题，可能为空；UI 展示时需要回退到链接或剪贴板内容。 */
    val title: String?,

    @ColumnInfo(name = "description")
    /** 网页描述信息，可能为空；仅作为列表/详情辅助展示，不参与主键判断。 */
    val description: String?,

    @ColumnInfo(name = "image_url")
    /** 预览图 URL，可能为空；图片加载失败不影响剪贴板记录本身。 */
    val imageUrl: String?,

    @ColumnInfo(name = "site_name")
    /** 站点名称，可能为空；来自 meta 标签或解析器推断。 */
    val siteName: String?,
)

@Dao
/**
 * 链接预览 DAO。
 *
 * 预览记录以链接为主键独立缓存，剪贴板记录只通过 link 字段关联读取，不随单条剪贴板删除而级联删除。
 */
interface LinkPreviewDao {

    /**
     * 更新或插入一个LinkPreviewData条目。这是所有数据写入的基础，使用更高效的 @Upsert。
     * @param data 要更新或插入的 LinkPreviewData 对象。
     * @return 更新或插入条目的ID。
     */
    @Upsert
    suspend fun upsert(data: LinkPreviewData): Long

    /**
     * 备份恢复批量写入链接预览缓存。
     *
     * 链接预览以 URL 为主键，重复恢复只会刷新同一行，不会产生重复缓存。
     */
    @Upsert
    suspend fun upsertAllForBackup(data: List<LinkPreviewData>)

    /**
     * 备份导出读取全部链接预览缓存。
     *
     * 预览缓存可能被多条剪贴记录共享，因此不按剪贴记录筛选；恢复后关系继续通过 link 字段重建。
     */
    @Query("SELECT * FROM link_previews ORDER BY link ASC")
    suspend fun loadAllForBackup(): List<LinkPreviewData>

    /** 分页导出链接预览缓存；主键是 link，使用字典序游标避免一次性读取完整缓存。 */
    @Query("SELECT * FROM link_previews WHERE link > :lastLink ORDER BY link ASC LIMIT :limit")
    suspend fun loadPageForBackup(lastLink: String, limit: Int): List<LinkPreviewData>

    /** 备份恢复按稳定主键批量查询已有链接预览，用于生成幂等恢复报告。 */
    @Query("SELECT * FROM link_previews WHERE link IN (:links)")
    suspend fun loadByLinksForBackup(links: List<String>): List<LinkPreviewData>

    /**
     * 根据包名查询对应的LinkPreviewData
     * @param link 要查询的链接。
     * @return 对应的LinkPreviewData对象，如果不存在则返回null。
     */
    @Query("SELECT * FROM link_previews WHERE link = :link LIMIT 1")
    suspend fun loadByLink(link: String): LinkPreviewData?
}
