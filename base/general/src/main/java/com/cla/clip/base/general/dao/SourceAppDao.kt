package com.cla.clip.base.general.dao

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

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
    /** 来源应用包名，作为主键；剪贴板记录通过 source_app_package 关联到这里。 */
    val packageName: String,

    @ColumnInfo(name = "app_name")
    /** 用户可见应用名，来自 PackageManager；无法解析时调用方应提供兜底名称。 */
    val appName: String,

    @ColumnInfo(name = "icon_path")
    /** 应用图标缓存路径，可能为空；图标文件由 FileUtils.saveIcon 写入应用私有目录。 */
    val iconPath: String?,

    @ColumnInfo(name = "primary_color")
    /** 从图标提取的主色 ARGB Int，可能为空；所有同来源剪贴板记录共用，避免重复取色。 */
    val primaryColor: Int?,

    @ColumnInfo(name = "icon_hash")
    /** 图标内容稳定哈希，用于判断图标是否变化，避免重复保存图标和重复提取主色。 */
    val iconHash: String?
)

@Dao
/**
 * 来源应用 DAO。
 *
 * 负责缓存来源应用名称、图标和主色信息，供列表、详情和搜索来源筛选复用。
 */
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

    /**
     * 加载所有已经记录过的来源 App，供搜索页构建来源筛选器。
     *
     * 返回 Flow 是为了让新复制内容写入来源表后，筛选器能自动刷新；排序同时使用应用名和包名，
     * 避免同名应用或空名称导致列表顺序不稳定。
     */
    @Query("SELECT * FROM source_apps ORDER BY app_name COLLATE NOCASE ASC, package_name ASC")
    fun loadAllSourceApps(): Flow<List<SourceAppData>>
}
