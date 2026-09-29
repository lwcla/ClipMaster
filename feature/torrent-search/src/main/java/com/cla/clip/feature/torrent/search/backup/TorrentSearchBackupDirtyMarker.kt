package com.cla.clip.feature.torrent.search.backup

import com.cla.clip.base.general.backup.settings.BackupRuntimeRepository

/** TorrentSearch 跨存储备份标记边界；调用方等待标记提交后才报告写入成功。 */
internal fun interface TorrentSearchBackupDirtyMarker {
    /** 在业务数据写入后挂起直到宿主变更版本持久化；失败向调用方传播。 */
    suspend fun markDirty()
}

/** JVM 测试与显式构造使用的空实现，不访问真实宿主设置文件。 */
internal object NoOpTorrentSearchBackupDirtyMarker : TorrentSearchBackupDirtyMarker {
    /** 测试环境不执行跨存储副作用。 */
    override suspend fun markDirty() = Unit
}

/** 生产实现将 TorrentSearch 白名单变化接入宿主版本化备份状态。 */
internal class BackupRuntimeTorrentSearchBackupDirtyMarker(
    /** 负责持久化变更版本的宿主仓库。 */
    private val backupRuntimeRepository: BackupRuntimeRepository,
) : TorrentSearchBackupDirtyMarker {
    /** 等待版本提交；不能在应用作用域另起协程后提前返回成功。 */
    override suspend fun markDirty() = backupRuntimeRepository.markChanged()
}
