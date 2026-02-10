package com.cla.clip.base.general.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.cla.clip.base.general.dao.data.SourceApp

@Dao
interface SourceAppDao {

    /**
     * 更新或插入一个SourceApp条目。这是所有数据写入的基础，使用更高效的 @Upsert。
     * @param sourceApp 要更新或插入的SourceApp对象。
     * @return 更新或插入条目的ID。
     */
    @Upsert
    suspend fun upsertSourceApp(sourceApp: SourceApp): Long

    /**
     * 根据包名查询对应的SourceApp条目。
     * @param packageName 要查询的应用包名。
     * @return 对应的SourceApp对象，如果不存在则返回null。
     */
    @Query("SELECT * FROM source_apps WHERE package_name = :packageName LIMIT 1")
    suspend fun getSourceAppByPackageName(packageName: String): SourceApp?
}