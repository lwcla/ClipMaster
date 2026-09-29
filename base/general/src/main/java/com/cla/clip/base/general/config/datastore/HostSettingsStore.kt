package com.cla.clip.base.general.config.datastore

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import javax.inject.Inject

/** 宿主设置介质边界；只有领域仓库可以接触原始 Preferences。 */
internal class HostSettingsStore @Inject constructor(
    /** 同一进程内唯一的宿主设置 DataStore 绑定。 */
    @param:HostSettingsDataStore private val dataStore: DataStore<Preferences>,
) {
    /** 原始快照流；IO 或损坏错误直接向领域仓库传播。 */
    val data: Flow<Preferences> = dataStore.data

    /** 读取一份已提交快照；读取失败直接抛出，不以空设置冒充新安装。 */
    suspend fun snapshot(): Preferences = data.first()

    /** 在单次 DataStore 事务内执行同步修改；正常返回代表持久化完成。 */
    suspend fun edit(block: (MutablePreferences) -> Unit): Preferences = dataStore.edit { preferences ->
        // 当前事务的可写快照只在回调期间有效，禁止外泄到领域仓库。
        block(preferences)
    }
}
