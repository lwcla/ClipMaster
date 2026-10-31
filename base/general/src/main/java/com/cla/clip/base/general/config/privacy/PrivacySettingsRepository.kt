package com.cla.clip.base.general.config.privacy

import androidx.datastore.preferences.core.Preferences
import com.cla.clip.base.general.config.datastore.HostSettingsKeys
import com.cla.clip.base.general.config.datastore.HostSettingsStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/** 本机首次说明及独立预览设置；不承载系统授权，也没有前台读取许可字段。 */
data class PrivacySettingsSnapshot(
    /** 完成任一首次选择均为 true，不等同于授予 Shizuku 或通知权限。 */
    val firstLaunchCompleted: Boolean,
    /** 自动网页及封面请求许可，缺键默认关闭。 */
    val previewEnabled: Boolean,
)

/** 隐私选择使用宿主事务存储，失败抛出；不导出、不触发备份调度或权限申请。 */
@Singleton
class PrivacySettingsRepository @Inject internal constructor(
    /** 全进程唯一设置介质，不创建第二份 DataStore。 */
    private val store: HostSettingsStore,
) {
    /** 已提交快照流，IO 错误由调用方展示重试或安全禁止网络。 */
    val state: Flow<PrivacySettingsSnapshot> = store.data.map(::readSnapshot).distinctUntilChanged()
    /** 网络消费者只观察预览字段，不被其他设置写入反复唤醒。 */
    val previewEnabled: Flow<Boolean> = state.map { it.previewEnabled }.distinctUntilChanged()

    /** 缺键只代表尚未处理说明；正常重启与升级均保留已提交的选择，IO 失败向外传播。 */
    suspend fun initialize() {
        /** 在同一事务内初始化两个隐私字段，其他设置或 APK 更新历史不能替代用户选择。 */
        store.edit { preferences ->
            if (preferences[HostSettingsKeys.FIRST_LAUNCH_COMPLETED] == null) {
                // 系统清除数据后 APK 仍可能保留更新时间；首次标记缺失时必须重新说明且禁止自动预览。
                preferences[HostSettingsKeys.FIRST_LAUNCH_COMPLETED] = false
                preferences[HostSettingsKeys.LINK_PREVIEW_ENABLED] = false
            }
        }
    }

    /** 原子处理首次选择；返回是否首次提交成功，重复回调不覆盖后续手动设置。 */
    suspend fun completeFirstLaunch(enablePreview: Boolean): Boolean {
        /** 仅在本次事务真正变更时允许调用方安排权限引导。 */
        var completedNow = false
        store.edit { preferences ->
            if (preferences[HostSettingsKeys.FIRST_LAUNCH_COMPLETED] != true) {
                preferences[HostSettingsKeys.FIRST_LAUNCH_COMPLETED] = true
                preferences[HostSettingsKeys.LINK_PREVIEW_ENABLED] = enablePreview
                completedNow = true
            }
        }
        return completedNow
    }

    /** 手动开关只保存联网偏好，不联动 Shizuku、通知或前台读取。 */
    suspend fun setPreviewEnabled(enabled: Boolean) {
        store.edit { preferences ->
            check(preferences[HostSettingsKeys.FIRST_LAUNCH_COMPLETED] == true)
            preferences[HostSettingsKeys.LINK_PREVIEW_ENABLED] = enabled
        }
    }

    /** 读取一次已提交值；损坏/IO 错误不能冒充首次状态。 */
    suspend fun snapshot(): PrivacySettingsSnapshot = readSnapshot(store.snapshot())

    /** 缺键在完成初始化前按未处理、无网络许可对待。 */
    private fun readSnapshot(preferences: Preferences): PrivacySettingsSnapshot = PrivacySettingsSnapshot(
        firstLaunchCompleted = preferences[HostSettingsKeys.FIRST_LAUNCH_COMPLETED] ?: false,
        previewEnabled = preferences[HostSettingsKeys.LINK_PREVIEW_ENABLED] ?: false,
    )
}
