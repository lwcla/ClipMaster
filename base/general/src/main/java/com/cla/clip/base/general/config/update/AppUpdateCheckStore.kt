package com.cla.clip.base.general.config.update

import androidx.datastore.preferences.core.Preferences
import com.cla.clip.base.general.config.datastore.HostSettingsKeys
import com.cla.clip.base.general.config.datastore.HostSettingsStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import javax.inject.Inject

/** App 自升级检查时间快照；0 表示本次安装尚未记录检查。 */
data class AppUpdateCheckSnapshot(
    /** 上次轻量检查时间，单位毫秒且非负。 */
    val lastCheckAt: Long,
)

/** App 自升级的本机限频状态，避免下载或广告设置混入该职责。 */
class AppUpdateCheckStore @Inject internal constructor(
    /** 宿主唯一 DataStore 介质。 */
    private val store: HostSettingsStore,
) {
    /** 已提交检查时间流；无关设置更新不重复发射。 */
    val data: Flow<AppUpdateCheckSnapshot> = store.data.map(::mapSnapshot).distinctUntilChanged()

    /** 读取真实检查时间；IO 失败交由升级入口安全处理。 */
    suspend fun snapshot(): AppUpdateCheckSnapshot = mapSnapshot(store.snapshot())

    /** 保存检查时间；异常负值归一为从未检查。 */
    suspend fun setLastCheckAt(value: Long) {
        /** 非负的最终检查时间，避免未来限频计算使用负值。 */
        val normalized = value.coerceAtLeast(0L)
        store.edit { preferences -> preferences[HostSettingsKeys.APP_UPDATE_LAST_CHECK_AT] = normalized }
    }

    /** 从原始键构造一次安全的限频状态。 */
    private fun mapSnapshot(preferences: Preferences): AppUpdateCheckSnapshot {
        /** 历史异常值不会用于跳过新一轮检查。 */
        val lastCheckAt = (preferences[HostSettingsKeys.APP_UPDATE_LAST_CHECK_AT] ?: 0L).coerceAtLeast(0L)
        return AppUpdateCheckSnapshot(lastCheckAt)
    }

    companion object {
        /** 自动检查间隔为滚动 24 小时窗口。 */
        const val AUTO_CHECK_INTERVAL_MILLIS = 24L * 60L * 60L * 1000L
    }
}
