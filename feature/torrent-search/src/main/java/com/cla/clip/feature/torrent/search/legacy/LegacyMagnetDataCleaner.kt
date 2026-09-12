package com.cla.clip.feature.torrent.search.legacy

import java.io.File

/** 旧磁力数据确认页使用的脱敏检测快照；UI 只能读取分类数量。 */
internal data class LegacyMagnetDataSnapshot(
    /** 旧用户数据库及 Room sidecar 文件数。 */
    val databaseFileCount: Int,
    /** Academic Torrents 缓存数据库及 sidecar 文件数。 */
    val cacheFileCount: Int,
    /** 检测时固定的候选路径；清理前仍会重新对照白名单。 */
    internal val targetPaths: Set<String>,
) {
    /** 是否存在必须由用户确认处置的旧数据。 */
    val hasLegacyData: Boolean get() = targetPaths.isNotEmpty()
}

/** 精确清理结果；不向页面或日志暴露路径和文件名。 */
internal data class LegacyMagnetCleanupResult(
    /** 成功删除的固定白名单文件数。 */
    val deletedFileCount: Int,
    /** 因非白名单、类型变化或删除失败而保留的文件数。 */
    val failedFileCount: Int,
) {
    /** 只有全部候选成功删除才允许进入新功能。 */
    val isSuccess: Boolean get() = failedFileCount == 0
}

/** 旧数据清理低敏日志边界，只接收阶段、结果和数量。 */
internal fun interface LegacyMagnetCleanupLogger {
    /** 记录检测/清理阶段，禁止输出内容、路径和文件名。 */
    fun log(operationCode: String, stateCode: String, affectedCount: Int)
}

/** 默认空日志供 JVM 测试使用。 */
internal object NoOpLegacyMagnetCleanupLogger : LegacyMagnetCleanupLogger {
    /** 测试不输出运行时日志。 */
    override fun log(operationCode: String, stateCode: String, affectedCount: Int) = Unit
}

/** 只识别旧实现已知固定文件名的检测与清理器。 */
internal class LegacyMagnetDataCleaner(
    /** 应用私有 databases 目录，不允许调用方传入其父目录。 */
    private val databaseDirectory: File,
    /** 应用私有 cache 目录，只在其固定子目录中识别旧缓存。 */
    private val cacheDirectory: File,
    /** 固定 code 低敏日志出口。 */
    private val logger: LegacyMagnetCleanupLogger = NoOpLegacyMagnetCleanupLogger,
) {
    /** 检测当前存在的精确白名单文件，不扫描其他目录或相似名称。 */
    fun detect(): LegacyMagnetDataSnapshot {
        logger.log("legacy_detect", "started", 0)
        /** databaseTargets 是旧用户数据库主文件和 Room 固定 sidecar。 */
        val databaseTargets = legacyDatabaseTargets().filter(File::isFile)
        /** cacheTargets 是 Academic Torrents 独占缓存库及 Room 固定 sidecar。 */
        val cacheTargets = legacyCacheTargets().filter(File::isFile)
        /** targetPaths 使用规范路径冻结确认时的精确集合。 */
        val targetPaths = (databaseTargets + cacheTargets).mapTo(linkedSetOf()) { target -> target.canonicalPath }
        logger.log("legacy_detect", "completed", targetPaths.size)
        return LegacyMagnetDataSnapshot(databaseTargets.size, cacheTargets.size, targetPaths)
    }

    /** 重新验证 snapshot 每个目标仍属于白名单且仍是普通文件后逐个删除。 */
    fun clean(snapshot: LegacyMagnetDataSnapshot): LegacyMagnetCleanupResult {
        logger.log("legacy_clean", "started", snapshot.targetPaths.size)
        /** allowedPaths 是清理瞬间重新解析的唯一可删除规范路径集合。 */
        val allowedPaths = (legacyDatabaseTargets() + legacyCacheTargets()).mapTo(hashSetOf()) { target -> target.canonicalPath }
        /** deletedCount 统计真实成功删除的文件。 */
        var deletedCount = 0
        /** failedCount 统计被重新验证拒绝或无法删除的文件。 */
        var failedCount = 0
        /** rawPath 是检测快照中的单个不透明内部路径。 */
        for (rawPath in snapshot.targetPaths) {
            /** target 重新从规范路径构造，避免依赖检测时 File 对象状态。 */
            val target = File(rawPath)
            /** canonicalPath 用于阻止 snapshot 注入白名单外目标。 */
            val canonicalPath = runCatching(target::getCanonicalPath).getOrNull()
            if (canonicalPath == null || canonicalPath !in allowedPaths || !target.isFile) {
                failedCount += 1
            } else if (target.delete()) {
                deletedCount += 1
            } else {
                failedCount += 1
            }
        }
        removeLegacyCacheDirectoryIfEmpty()
        /** result 是页面决定继续、重试或退出的脱敏结果。 */
        val result = LegacyMagnetCleanupResult(deletedCount, failedCount)
        logger.log("legacy_clean", if (result.isSuccess) "completed" else "failed", deletedCount)
        return result
    }

    /** 构造旧用户数据库主文件与 Room sidecar 的精确候选。 */
    private fun legacyDatabaseTargets(): List<File> {
        /** suffix 是 Room 主文件或固定 sidecar 后缀。 */
        return LEGACY_DATABASE_SUFFIXES.map { suffix -> File(databaseDirectory, LEGACY_DATABASE_NAME + suffix) }
    }

    /** 构造 Academic Torrents 缓存主文件与 Room sidecar 的精确候选。 */
    private fun legacyCacheTargets(): List<File> {
        /** cacheDatabaseDirectory 是旧模块唯一拥有的固定缓存子目录。 */
        val cacheDatabaseDirectory = File(cacheDirectory, LEGACY_CACHE_DIRECTORY_NAME)
        /** suffix 是 Room 缓存主文件或固定 sidecar 后缀。 */
        return LEGACY_DATABASE_SUFFIXES.map { suffix -> File(cacheDatabaseDirectory, LEGACY_CACHE_DATABASE_NAME + suffix) }
    }

    /** 仅当旧缓存目录已经为空时删除该目录本身。 */
    private fun removeLegacyCacheDirectoryIfEmpty() {
        /** legacyCacheDirectory 是可删除的唯一固定目录。 */
        val legacyCacheDirectory = File(cacheDirectory, LEGACY_CACHE_DIRECTORY_NAME)
        /** children 为 null 时保守保留目录，避免把 IO 异常误判为空。 */
        val children = legacyCacheDirectory.listFiles() ?: return
        if (children.isEmpty()) legacyCacheDirectory.delete()
    }

    /** 从旧模块历史源码核对得到的固定文件名白名单。 */
    companion object {
        /** 旧用户数据库固定名。 */
        private const val LEGACY_DATABASE_NAME = "magnet_feature_database.db"
        /** 旧 Academic Torrents 缓存目录固定名。 */
        private const val LEGACY_CACHE_DIRECTORY_NAME = "magnet_source_cache"
        /** 旧 Academic Torrents 缓存数据库固定名。 */
        private const val LEGACY_CACHE_DATABASE_NAME = "magnet_source_cache.db"
        /** Room 可能留下的主文件、WAL、SHM 与 journal 后缀。 */
        private val LEGACY_DATABASE_SUFFIXES = listOf("", "-wal", "-shm", "-journal")
    }
}
