package com.cla.clip.base.general.config.logging

import com.cla.clip.base.general.config.datastore.HostSettingsKeys
import com.cla.clip.base.general.config.datastore.HostSettingsStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/** 日志设置只管理已提交本机开关；不持有录制器、页面或备份调度。 */
@Singleton
class LogSettingsRepository @Inject internal constructor(
    /** 复用宿主唯一介质，避免创建第二个活跃 DataStore。 */
    private val store: HostSettingsStore,
) {
    /** 新安装默认关闭；IO 错误传播，调用方不得伪装为默认值。 */
    val enabled: Flow<Boolean> = store.data.map { it[HostSettingsKeys.LOG_CAPTURE_ENABLED] ?: false }.distinctUntilChanged()

    /** 返回代表持久化成功，页面只消费提交后的值。 */
    suspend fun setEnabled(enabled: Boolean) {
        store.edit { preferences -> preferences[HostSettingsKeys.LOG_CAPTURE_ENABLED] = enabled }
    }
}
