package com.cla.clip.base.general.dao.data

import androidx.room.Embedded
import androidx.room.Relation
import com.cla.clip.base.general.dao.ClipData
import com.cla.clip.base.general.dao.LinkPreviewData
import com.cla.clip.base.general.dao.SourceAppData


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
data class ClipDetail(
    // 将 ClipData 的字段展开嵌入到这里
    @Embedded
    val clip: ClipData,

    // 定义关联关系
    @Relation(
        parentColumn = "source_app_package", // ClipData 中的关联字段
        entityColumn = "package_name"        // SourceApp 中的关联字段
    )
    val sourceApp: SourceAppData?, // 如果找不到对应的 App 信息，这里可能为 null

    @Relation(
        parentColumn = "link",        // ClipData.link（URL 字符串）
        entityColumn = "link"         // LinkPreviewData.link（URL 字符串）
    )
    val linkPreview: LinkPreviewData?  // 可能为 null（没有链接的情况）
)