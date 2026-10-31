package com.cla.clip.master.onboarding

import com.cla.clip.base.general.config.privacy.PrivacySettingsRepository
import com.cla.clip.base.general.config.privacy.PrivacySettingsSnapshot
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

/** 串行初始化本机首次状态；只认已提交的处理标记，不从 APK 更新历史推断用户选择。 */
@Singleton
internal class FirstLaunchInitializer @Inject constructor(
    /** 两个本机隐私字段的唯一仓库。 */ private val settings: PrivacySettingsRepository,
) {
    /** Application、Activity 与连接器并发进入时只初始化一次。 */
    private val mutex = Mutex()
    /** 仅代表本进程已初始化，不代表用户同意可选功能。 */
    private var initialized = false

    /** 首次缺键由仓库初始化为未处理；IO 失败向调用方传播，正常重启不覆盖已有选择。 */
    suspend fun initialize(): PrivacySettingsSnapshot {
        mutex.withLock {
            if (!initialized) {
                // 持久化成功后才缓存本进程已初始化，失败时允许后续调用重试。
                settings.initialize()
                initialized = true
            }
        }
        return settings.snapshot()
    }
}
