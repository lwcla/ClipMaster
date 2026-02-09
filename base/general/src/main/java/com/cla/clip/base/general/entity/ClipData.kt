package com.cla.clip.base.general.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Fts4
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 在 Room 数据库中，indices 属性用于在数据库表的特定列上创建索引 (Index)。索引的主要作用是加快查询速度。
 * 以下是具体的作用和原理：
 * 提高查询性能：
 * 如果没有索引，当你根据某个字段（例如 timestamp）进行查找或排序时，数据库可能需要扫描整张表（全表扫描）。
 * 有了索引，数据库可以快速定位到符合条件的行，极大地减少查找时间，特别是在通过 WHERE 子句筛选或 ORDER BY 排序时。
 * 强制唯一性 (可选)：
 * 虽然你的代码中没有使用，但 Index 注解有一个 unique = true 属性。如果设置了它，就可以确保被索引列的值在表中是唯一的，防止重复数据插入。
 * 外键约束优化：
 * 当该列作为外键使用时，添加索引可以避免在父表更新或删除时导致的子表全表扫描，从而防止性能下降甚至死锁。
 *
 * @param id 每条记录的唯一ID。
 * @param groupId 分组ID。用于将一条剪贴板的多个历史版本关联起来。对于一个全新的条目，它的 groupId 等于它自己的 id。
 * @param content 核心内容（文本、图片URI、链接URL）。
 * @param timestamp “最后修改”时间戳。
 * @param isPinned 是否置顶。
 * @param colorTag 颜色分类标签。
 * @param linkTitle 链接预览的标题。
 * @param linkDescription 链接预览的描述。
 * @param linkImageUrl 链接预览的图片URL。
 * @param linkSiteName 链接预览的网站名。
 * @param sourceAppPackage 来源应用的包名。
 * @param sourceAppName 来源应用的显示名称。
 * @param sourceAppIconPath 来源应用缓存在本地的图标路径。
 */
@Entity(
    tableName = "clips",
    indices = [
        Index(value = ["timestamp"]),
        Index(value = ["group_id"]),
        Index(value = ["is_latest"]),
        Index(value = ["color_tag"]),
        Index(value = ["source_app_name"]),
        Index(value = ["content"]),
    ]
)
data class ClipData(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    @ColumnInfo(name = "group_id")
    val groupId: Long,
    @ColumnInfo(name = "is_latest", defaultValue = "1")
    val isLatest: Boolean = true,
    @ColumnInfo(name = "content")
    val content: String,
    @ColumnInfo(name = "timestamp")
    val timestamp: Long,
    @ColumnInfo(name = "is_pinned", defaultValue = "0")
    val isPinned: Boolean = false,
    @ColumnInfo(name = "color_tag")
    val colorTag: String? = null,
    @ColumnInfo(name = "link_title")
    val linkTitle: String? = null,
    @ColumnInfo(name = "link_description")
    val linkDescription: String? = null,
    @ColumnInfo(name = "link_image_url")
    val linkImageUrl: String? = null,
    @ColumnInfo(name = "link_site_name")
    val linkSiteName: String? = null,
    @ColumnInfo(name = "source_app_package")
    val sourceAppPackage: String? = null,
    @ColumnInfo(name = "source_app_name")
    val sourceAppName: String? = null,
    @ColumnInfo(name = "source_app_icon_path")
    val sourceAppIconPath: String? = null
)

/**
 * FTS虚拟表，用于对Clip表中的文本字段进行全文检索。
 * 字段必须与Clip实体中要被索引的字段完全对应。
 */
@Fts4(contentEntity = ClipData::class)
@Entity(tableName = "clips_fts")
data class ClipFts(
    // 必须与Clip实体中的可搜索字段完全对应
    @ColumnInfo(name = "content")
    val content: String,
    @ColumnInfo(name = "link_title")
    val linkTitle: String?,
    @ColumnInfo(name = "link_description")
    val linkDescription: String?,
    @ColumnInfo(name = "link_site_name")
    val linkSiteName: String?,
    @ColumnInfo(name = "source_app_name")
    val sourceAppName: String?,
    @ColumnInfo(name = "source_app_package")
    val sourceAppPackage: String?
)