package com.cla.clip.base.general.repository.sourceapp

import com.cla.clip.base.general.dao.SourceAppDao
import com.cla.clip.base.general.dao.SourceAppData
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import javax.inject.Inject
import javax.inject.Singleton

/** 来源展示的数据库边界，不依赖 Compose 或系统图标服务。 */
@Singleton
class SourceAppPresentationRepository @Inject constructor(
    /** 既有来源表 DAO，读写不增加备份或 schema 契约。 */
    private val dao: SourceAppDao,
) {
    /** 活跃集合由 app 订阅会话聚合，图标变化只重新查询来源元数据。 */
    fun observe(packages: Set<String>): Flow<List<SourceAppData>> =
        dao.observePresentations(packages.sorted()).distinctUntilChanged()

    /** 查询失败向上传播，不能将数据库错误解释为缓存缺失。 */
    suspend fun find(packageName: String): SourceAppData? = dao.loadByPackageName(packageName)

    /** 正式文件先发布，条件不满足返回 false 表示并发冲突或来源已删除。 */
    suspend fun commit(expected: SourceAppData, path: String, hash: String, color: Int?): Boolean =
        dao.updateIconIfUnchanged(expected.packageName, expected.iconPath, expected.iconHash, path, hash, color) > 0

    /** 延迟清理必须核对整个来源表中的引用，不仅检查活跃包名。 */
    suspend fun references(): Set<String> = dao.loadIconReferences().toSet()
}
