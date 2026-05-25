package com.cla.clip.base.general.backup

import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.Multibinds

/**
 * 可选功能模块接入备份包的参与者。
 *
 * base 只认识稳定 entry、计数和恢复报告 code，不依赖具体功能模块的 DAO、Repository 或协议模型。
 */
interface BackupFeatureContributor {
    /** 稳定参与者 id，用于排序和日志，不包含用户内容。 */
    val contributorId: String

    /** 读取导出边界，返回值只由参与者自己解释。 */
    suspend fun readHighWaterMarks(): Map<String, Long>

    /** 导出当前功能的 JSONL entry，并把脱敏数量写入 featureCounts。 */
    suspend fun exportJsonl(
        session: BackupPackageBuildSession,
        highWaterMarks: Map<String, Long>,
        featureCounts: MutableMap<String, Int>,
    ): List<BackupPackageFile>

    /** 恢复当前功能的 JSONL entry；相关 entry 缺失时返回空报告。 */
    suspend fun restoreJsonl(
        ref: BackupPackageRef,
        manifest: BackupManifest,
    ): List<BackupRestoreCategoryReport>
}

/** base 默认提供空集合，真实功能模块通过 multibinding 接入。 */
@Module
@InstallIn(SingletonComponent::class)
abstract class BackupFeatureContributorModule {
    @Multibinds
    abstract fun bindBackupFeatureContributors(): Set<@JvmSuppressWildcards BackupFeatureContributor>
}
