package com.cla.clip.base.general.config.clip

import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import com.cla.clip.base.general.config.ClipItemQuickAction
import com.cla.clip.base.general.config.ClipSourceBlockRules
import com.cla.clip.base.general.config.datastore.HostSettingsKeys
import com.cla.clip.base.general.config.datastore.HostSettingsStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import javax.inject.Inject

/** 一份已提交的剪贴设置快照；页面与后台任务不接触原始 Preferences。 */
data class ClipSettingsSnapshot(
    /** 普通剪贴项快捷动作，未知 code 安全回退复制。 */
    val quickAction: ClipItemQuickAction,
    /** 规范化且确定顺序的来源过滤名单。 */
    val blockedPackages: Set<String>,
    /** 回收站滚动保留天数，始终在 1 至 3650 范围。 */
    val recycleBinRetentionDays: Int,
)

/** 剪贴领域设置仓库；名单更新及备份变更版本在同一事务内完成。 */
class ClipSettingsRepository @Inject internal constructor(
    /** 宿主唯一 DataStore 介质，原始键不向调用方暴露。 */
    private val store: HostSettingsStore,
) {
    companion object {
        /** 回收站默认保留三十天，与现有清理策略一致。 */
        const val DEFAULT_RECYCLE_BIN_RETENTION_DAYS = 30
        /** 最短保留一天，防止输入零导致立即清理。 */
        const val MIN_RECYCLE_BIN_RETENTION_DAYS = 1
        /** 最长保留十年，避免时间换算和长期空间承诺失控。 */
        const val MAX_RECYCLE_BIN_RETENTION_DAYS = 3650
    }

    /** 已提交设置的领域流；其他领域写入不触发相同快照的刷新。 */
    val data: Flow<ClipSettingsSnapshot> = store.data.map(::mapSnapshot).distinctUntilChanged()

    /** 读取一次真实设置；介质损坏或 IO 失败向调用方抛出。 */
    suspend fun snapshot(): ClipSettingsSnapshot = mapSnapshot(store.snapshot())

    /** 保存快捷动作；只有业务值真实变化才推进备份版本。 */
    suspend fun setQuickAction(value: ClipItemQuickAction) {
        store.edit { preferences ->
            if (ClipItemQuickAction.fromStorageValue(preferences[HostSettingsKeys.CLIP_ITEM_QUICK_ACTION]) != value) {
                preferences[HostSettingsKeys.CLIP_ITEM_QUICK_ACTION] = value.storageValue
                preferences.markBackupChanged()
            }
        }
    }

    /** 裁剪并保存回收站保留天数；无变化时不推进备份版本。 */
    suspend fun setRecycleBinRetentionDays(value: Int) {
        /** 受产品边界限制的目标天数。 */
        val normalizedValue = value.coerceIn(MIN_RECYCLE_BIN_RETENTION_DAYS, MAX_RECYCLE_BIN_RETENTION_DAYS)
        store.edit { preferences ->
            /** 当前有效天数；对异常旧值同样裁剪。 */
            val currentValue = (preferences[HostSettingsKeys.RECYCLE_BIN_RETENTION_DAYS] ?: DEFAULT_RECYCLE_BIN_RETENTION_DAYS)
                .coerceIn(MIN_RECYCLE_BIN_RETENTION_DAYS, MAX_RECYCLE_BIN_RETENTION_DAYS)
            if (currentValue != normalizedValue) {
                preferences[HostSettingsKeys.RECYCLE_BIN_RETENTION_DAYS] = normalizedValue
                preferences.markBackupChanged()
            }
        }
    }

    /** 一次事务恢复可备份设置；本机名单优先并集，实际变化只推进一次备份版本。 */
    suspend fun restoreFromBackup(
        quickAction: ClipItemQuickAction?,
        recycleBinRetentionDays: Int?,
        blockedPackages: List<String>,
    ): Boolean {
        /** 本次恢复是否真实改变设置，只有提交成功后才向上返回。 */
        var changed = false
        store.edit { preferences ->
            /** 当前快捷动作；备份缺失字段时保留本机设置。 */
            val currentAction = ClipItemQuickAction.fromStorageValue(preferences[HostSettingsKeys.CLIP_ITEM_QUICK_ACTION])
            if (quickAction != null && quickAction != currentAction) {
                preferences[HostSettingsKeys.CLIP_ITEM_QUICK_ACTION] = quickAction.storageValue
                changed = true
            }
            /** 当前有效保留天数；异常旧值仅在读取时裁剪。 */
            val currentRetention = (preferences[HostSettingsKeys.RECYCLE_BIN_RETENTION_DAYS] ?: DEFAULT_RECYCLE_BIN_RETENTION_DAYS)
                .coerceIn(MIN_RECYCLE_BIN_RETENTION_DAYS, MAX_RECYCLE_BIN_RETENTION_DAYS)
            /** 备份目标天数；缺失字段不覆盖本机设置。 */
            val targetRetention = recycleBinRetentionDays?.coerceIn(MIN_RECYCLE_BIN_RETENTION_DAYS, MAX_RECYCLE_BIN_RETENTION_DAYS)
            if (targetRetention != null && targetRetention != currentRetention) {
                preferences[HostSettingsKeys.RECYCLE_BIN_RETENTION_DAYS] = targetRetention
                changed = true
            }
            if (blockedPackages.isNotEmpty()) {
                /** 本机现有名单优先保留，防止并集达到上限时挤掉本机新增项。 */
                val currentPackages = readBlockedPackages(preferences)
                /** 备份名单按共享规则规范化，非法包名不进入设置。 */
                val backupPackages = ClipSourceBlockRules.normalizePackageSet(blockedPackages)
                /** 可写的并集先放本机项，再填充备份项直至达到上限。 */
                val mergedPackages = LinkedHashSet(currentPackages)
                backupPackages.forEach { packageName ->
                    if (mergedPackages.size < ClipSourceBlockRules.MAX_PACKAGE_COUNT) mergedPackages.add(packageName)
                }
                /** 最终名单按共享规则排序，供 UI 和下次导出稳定读取。 */
                val normalizedMerged = ClipSourceBlockRules.normalizePackageSet(mergedPackages)
                if (normalizedMerged != currentPackages) {
                    preferences[HostSettingsKeys.BLOCKED_CLIP_SOURCE_PACKAGES] = normalizedMerged
                    changed = true
                }
            }
            if (changed) preferences.markBackupChanged()
        }
        return changed
    }
    /** 原子加入有效包名；重复、非法或达到上限返回 false，IO 失败仍抛出。 */
    suspend fun addBlockedPackage(value: String): Boolean {
        /** 本次事务是否真实改变名单，只有提交成功后才返回。 */
        var changed = false
        store.edit { preferences ->
            /** 输入的规范化包名；无效输入不会触碰持久化值。 */
            val packageName = ClipSourceBlockRules.normalizeSinglePackage(value)
            /** 当前已规范化名单；必须在事务内读取以免并发写覆盖。 */
            val current = readBlockedPackages(preferences)
            if (packageName != null && packageName !in current && current.size < ClipSourceBlockRules.MAX_PACKAGE_COUNT) {
                preferences[HostSettingsKeys.BLOCKED_CLIP_SOURCE_PACKAGES] = ClipSourceBlockRules.normalizePackageSet(current + packageName)
                preferences.markBackupChanged()
                changed = true
            }
        }
        return changed
    }

    /** 原子移除有效包名；不存在时返回 false，IO 失败仍抛出。 */
    suspend fun removeBlockedPackage(value: String): Boolean {
        /** 本次事务是否真实改变名单。 */
        var changed = false
        store.edit { preferences ->
            /** 输入的规范化包名；空值和危险字符不参与删除。 */
            val packageName = ClipSourceBlockRules.normalizeSinglePackage(value)
            /** 当前事务中的最新名单。 */
            val current = readBlockedPackages(preferences)
            if (packageName != null && packageName in current) {
                preferences[HostSettingsKeys.BLOCKED_CLIP_SOURCE_PACKAGES] = ClipSourceBlockRules.normalizePackageSet(current - packageName)
                preferences.markBackupChanged()
                changed = true
            }
        }
        return changed
    }

    /** 原子替换名单；输入按既有规则规范化，等价名单返回 false。 */
    suspend fun replaceBlockedPackages(values: Iterable<String?>): Boolean {
        /** 本次事务是否真实改变名单。 */
        var changed = false
        store.edit { preferences ->
            /** 目标名单在事务内规范化，避免懒序列与并发读写脱节。 */
            val target = ClipSourceBlockRules.normalizePackageSet(values)
            /** 当前事务中的最新名单。 */
            val current = readBlockedPackages(preferences)
            if (target != current) {
                preferences[HostSettingsKeys.BLOCKED_CLIP_SOURCE_PACKAGES] = target
                preferences.markBackupChanged()
                changed = true
            }
        }
        return changed
    }

    /** 将原始设置映射为可安全消费的一致快照，不在读取时隐式修复文件。 */
    private fun mapSnapshot(preferences: Preferences): ClipSettingsSnapshot {
        /** 当前快捷动作；未知持久化值按现有规则回退复制。 */
        val action = ClipItemQuickAction.fromStorageValue(preferences[HostSettingsKeys.CLIP_ITEM_QUICK_ACTION])
        /** 当前来源过滤名单；排序保证备份与 UI 输出稳定。 */
        val packages = readBlockedPackages(preferences)
        /** 当前回收站天数；异常值仅在内存中裁剪。 */
        val retentionDays = (preferences[HostSettingsKeys.RECYCLE_BIN_RETENTION_DAYS] ?: DEFAULT_RECYCLE_BIN_RETENTION_DAYS)
            .coerceIn(MIN_RECYCLE_BIN_RETENTION_DAYS, MAX_RECYCLE_BIN_RETENTION_DAYS)
        return ClipSettingsSnapshot(action, packages, retentionDays)
    }

    /** 从当前事务或快照中读取并规范化来源过滤名单。 */
    private fun readBlockedPackages(preferences: Preferences): Set<String> {
        return ClipSourceBlockRules.normalizePackageSet(preferences[HostSettingsKeys.BLOCKED_CLIP_SOURCE_PACKAGES].orEmpty())
    }

    /** 同事务推进备份版本；耗尽 Long 范围时失败而非静默标记 clean。 */
    private fun MutablePreferences.markBackupChanged() {
        /** 当前可备份数据版本；新安装从 1 开始保持待备份。 */
        val current = this[HostSettingsKeys.CHANGE_REVISION] ?: 1L
        check(current < Long.MAX_VALUE) { "backup revision exhausted" }
        this[HostSettingsKeys.CHANGE_REVISION] = current + 1L
    }
}
