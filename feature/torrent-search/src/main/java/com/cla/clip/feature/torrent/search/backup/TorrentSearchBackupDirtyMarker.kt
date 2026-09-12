package com.cla.clip.feature.torrent.search.backup

import com.cla.clip.base.general.config.AppSetting

/** TorrentSearch 写入跨安装数据后的 dirty 标记边界，避免 Repository 直接依赖全局配置。 */
internal fun interface TorrentSearchBackupDirtyMarker {
    /** 标记下次自动备份需要包含最新 TorrentSearch 白名单数据。 */
    fun markDirty()
}

/** JVM 测试与显式构造使用的空实现，不会初始化 MMKV。 */
internal object NoOpTorrentSearchBackupDirtyMarker : TorrentSearchBackupDirtyMarker {
    /** 空实现保持既有纯 JVM 测试无 Android 全局状态。 */
    override fun markDirty() = Unit
}

/** 生产实现复用宿主 schema v4 的统一 dirty 状态。 */
internal object AppSettingTorrentSearchBackupDirtyMarker : TorrentSearchBackupDirtyMarker {
    /** 只写统一布尔标记，实际调度继续由 app 层负责。 */
    override fun markDirty() = AppSetting.markBackupDirty()
}
