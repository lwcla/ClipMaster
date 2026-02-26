package com.cla.clip.base.general.dao.data

import androidx.room.ColumnInfo
import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.Fts4
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.Relation

/**
 * 来源应用的信息实体类。
 *
 * @param packageName 应用的包名，作为主键使用。
 * @param appName 应用的名称。
 * @param iconPath 应用图标的存储路径。
 * @param primaryColor 从图标提取出的主色，存储在这里，所有该应用的剪贴板记录共用。
 */
@Entity(
    tableName = "source_apps",
    indices = [
        Index(value = ["package_name"]),
    ]
)
data class SourceApp(
    @PrimaryKey
    @ColumnInfo(name = "package_name")
    val packageName: String,
    @ColumnInfo(name = "app_name")
    val appName: String,
    @ColumnInfo(name = "icon_path")
    val iconPath: String?,
    // 从图标提取出的主色，存储在这里，所有该应用的剪贴板记录共用
    @ColumnInfo(name = "primary_color")
    val primaryColor: Int?
)

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
 * @param content 核心内容（文本、图片URI、链接URL）。
 * @param timestamp “最后修改”时间戳。
 * @param pinnedTime 置顶的时间戳。
 * @param linkTitle 链接预览的标题。
 * @param linkDescription 链接预览的描述。
 * @param linkImageUrl 链接预览的图片URL。
 * @param linkSiteName 链接预览的网站名。
 * @param sourceAppPackage 来源应用的包名。
 */
@Entity(
    tableName = "clips",
    indices = [
        Index(value = ["timestamp"]),
        Index(value = ["source_app_package"]),
        Index(value = ["content"]),
        Index(value = ["pinned_time"]),
    ]
)
data class ClipData(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    @ColumnInfo(name = "content")
    val content: String,
    @ColumnInfo(name = "timestamp")
    val timestamp: Long,
    @ColumnInfo(name = "pinned_time")
    val pinnedTime: Long = 0,
    @ColumnInfo(name = "link_title")
    val linkTitle: String? = null,
    @ColumnInfo(name = "link_description")
    val linkDescription: String? = null,
    @ColumnInfo(name = "link_image_url")
    val linkImageUrl: String? = null,
    @ColumnInfo(name = "link_site_name")
    val linkSiteName: String? = null,
    // 仅仅保留包名，用于和 SourceApp 表关联
    @ColumnInfo(name = "source_app_package")
    val sourceAppPackage: String? = null,
)

/**
 * 这是一个 POJO (Plain Old Java Object)，不是 Entity。
 * 它用于接收 Room 查询返回的组合数据。
 *
 * POJO 是 Plain Old Java Object 的缩写，直译为“简单的旧式 Java 对象”。
 * 这个概念最早是为了强调这一类对象非常纯粹：
 * 不继承特定的父类（比如不继承 HttpServlet）。
 * 不实现特定的接口（比如不实现 EntityBean）。
 * 不包含复杂的业务逻辑。
 * 没有被特定框架的规则“污染”。
 * 它通常只包含**字段（Fields）**以及对应的 Getter/Setter 方法（在 Kotlin 中就是属性）。
 * 在你的代码中，这句话的含义是：
 * 在 Room 数据库的语境下，区分 Entity 和 POJO 非常重要：
 * Entity (ClipData, SourceApp):
 * 对应数据库的一张表。
 * 每一个字段通常对应表中的一列。
 * 你对它做修改，最终是要存回数据库文件的。
 * POJO (ClipWithSourceApp):
 * 不对应数据库中的任何表（它没有 @Entity 注解，也不会在数据库里生成 clip_with_source_app 这张表）。
 * 它只是一个临时的容器。
 * 它的作用是：当你查询时，Room 会先把 ClipData (Entity) 查出来，再把对应的 SourceApp (Entity) 查出来，然后把这两坨数据打包塞进这个 POJO 里交给你。
 * 简单总结：
 * Entity 是数据库的存储格式。
 * POJO 是你为了方便业务层使用而定义的数据组合格式。
 */
data class ClipWithSourceApp(
    // 将 ClipData 的字段展开嵌入到这里
    @Embedded
    val clip: ClipData,

    // 定义关联关系
    @Relation(
        parentColumn = "source_app_package", // ClipData 中的关联字段
        entityColumn = "package_name"        // SourceApp 中的关联字段
    )
    val sourceApp: SourceApp? // 如果找不到对应的 App 信息，这里可能为 null
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
    @ColumnInfo(name = "source_app_package")
    val sourceAppPackage: String?
)