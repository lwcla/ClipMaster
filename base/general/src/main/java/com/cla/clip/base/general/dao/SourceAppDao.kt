package com.cla.clip.base.general.dao

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Upsert

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
data class SourceAppData(
    @PrimaryKey
    @ColumnInfo(name = "package_name")
    val packageName: String,
    @ColumnInfo(name = "app_name")
    val appName: String,
    @ColumnInfo(name = "icon_path")
    val iconPath: String?,
    // 从图标提取出的主色，存储在这里，所有该应用的剪贴板记录共用
    @ColumnInfo(name = "primary_color")
    val primaryColor: Int?,
    // 图标的hash，避免重复保存图标和取色
    @ColumnInfo(name = "icon_hash")
    val iconHash: String?
)

@Dao
interface SourceAppDao {

    /**
     * 更新或插入一个SourceApp条目。这是所有数据写入的基础，使用更高效的 @Upsert。
     * @param sourceApp 要更新或插入的SourceApp对象。
     * @return 更新或插入条目的ID。
     */
    @Upsert
    suspend fun upsert(sourceApp: SourceAppData): Long

    /**
     * 根据包名查询对应的SourceApp条目。
     * @param packageName 要查询的应用包名。
     * @return 对应的SourceApp对象，如果不存在则返回null。
     */
    @Query("SELECT * FROM source_apps WHERE package_name = :packageName LIMIT 1")
    suspend fun loadByPackageName(packageName: String): SourceAppData?
}