package com.cla.clip.base.general.config.installation

import androidx.datastore.preferences.core.Preferences
import com.cla.clip.base.general.config.NumericInstallIdGenerator
import com.cla.clip.base.general.config.datastore.HostSettingsKeys
import com.cla.clip.base.general.config.datastore.HostSettingsStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import javax.inject.Inject

/** 已提交的安装与 Shizuku 进程身份快照；空安装 ID 表示尚未生成。 */
data class InstallationIdentitySnapshot(
    /** 安装级纯数字身份，不能作为用户身份或日志字段。 */
    val installId: String,
    /** 主进程认可的完整 Shizuku 进程名。 */
    val expectedProcessName: String,
)

/** 安装身份仓库；身份生成始终在宿主 DataStore 原子事务内执行。 */
class InstallationIdentityRepository @Inject internal constructor(
    /** 宿主唯一 DataStore 介质。 */
    private val store: HostSettingsStore,
) {
    /** 已提交身份流；缺失 ID 不会在读取时触发隐式磁盘写入。 */
    val data: Flow<InstallationIdentitySnapshot> = store.data.map(::mapSnapshot).distinctUntilChanged()

    /** 读取一次已提交身份；调用方需要身份时应先调用 getOrCreateInstallId。 */
    suspend fun snapshot(): InstallationIdentitySnapshot = mapSnapshot(store.snapshot())

    /** 在事务中校验或生成唯一安装 ID，并且仅返回已持久化的值。 */
    suspend fun getOrCreateInstallId(): String {
        /** 本次事务最终提交的安装 ID，绝不提前向调用方发射临时值。 */
        var committedId = ""
        store.edit { preferences ->
            /** 当前文件中的身份；旧格式和损坏值会在开发阶段替换。 */
            val storedId = preferences[HostSettingsKeys.INSTALL_ID]
            if (NumericInstallIdGenerator.isValid(storedId)) {
                committedId = storedId.orEmpty()
            } else {
                /** 新安装身份只生成一次，写入与并发读改写由 DataStore 串行化。 */
                val generatedId = NumericInstallIdGenerator.generate()
                preferences[HostSettingsKeys.INSTALL_ID] = generatedId
                committedId = generatedId
            }
        }
        return committedId
    }

    /** 保存主进程期望的完整进程名；空白值仅用于明确清除异常状态。 */
    suspend fun setExpectedProcessName(value: String) {
        /** 去除复制或拼接时可能带入的首尾空白。 */
        val normalized = value.trim()
        store.edit { preferences -> preferences[HostSettingsKeys.EXPECTED_PROCESS_NAME] = normalized }
    }

    /** 映射已提交身份；读取阶段不生成新值，避免 Provider 冷启动死锁。 */
    private fun mapSnapshot(preferences: Preferences): InstallationIdentitySnapshot {
        /** 只有满足当前 20 位数字契约的身份才向调用方展示。 */
        val storedId = preferences[HostSettingsKeys.INSTALL_ID]?.takeIf(NumericInstallIdGenerator::isValid).orEmpty()
        /** 非空的期望进程名保留完整值，只裁剪首尾空白。 */
        val processName = preferences[HostSettingsKeys.EXPECTED_PROCESS_NAME]?.trim().orEmpty()
        return InstallationIdentitySnapshot(storedId, processName)
    }
}
