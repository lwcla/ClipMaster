package com.cla.clip.master.onboarding

import android.content.Context
import com.cla.clip.base.general.config.privacy.PrivacySettingsRepository
import com.cla.clip.base.general.config.privacy.PrivacySettingsSnapshot
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/** 本机包安装信息只用于一次旧版迁移；首次完成标记始终以已提交设置为准。 */
@Singleton
internal class FirstLaunchInitializer @Inject constructor(
    /** 应用 Context 不持有页面。 */ @param:ApplicationContext private val context: Context,
    /** 两个本机隐私字段的唯一仓库。 */ private val settings: PrivacySettingsRepository,
) {
    /** Application、Activity 与连接器并发进入时只初始化一次。 */
    private val mutex = Mutex()
    /** 仅代表本进程已初始化，不代表用户同意可选功能。 */
    private var initialized = false

    /** IO 失败向调用方传播，禁止使用伪造的已处理状态。 */
    suspend fun initialize(): PrivacySettingsSnapshot {
        mutex.withLock {
            if (!initialized) {
                /** 在 IO 调度器读取自身包元数据，无应用列表扫描。 */
                val existingInstallation = withContext(Dispatchers.IO) {
                    @Suppress("DEPRECATION")
                    // 安装时间早于更新时间说明覆盖安装；已有设置优先，后续升级不会覆盖新安装的 false。
                    val info = context.packageManager.getPackageInfo(context.packageName, 0)
                    info.firstInstallTime < info.lastUpdateTime
                }
                settings.initialize(existingInstallation)
                initialized = true
            }
        }
        return settings.snapshot()
    }
}
