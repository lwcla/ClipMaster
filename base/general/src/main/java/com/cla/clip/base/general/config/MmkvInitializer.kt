package com.cla.clip.base.general.config

import android.content.Context
import com.cla.clip.base.general.utils.logD
import com.tencent.mmkv.MMKV

/**
 * MMKV 进程内初始化入口。
 *
 * ContentProvider 冷启动会早于 Application.onCreate()，因此需要一个可在 Provider 和 Application 中重复调用的幂等入口。
 */
object MmkvInitializer {

    /** 日志标签，用于确认 MMKV 初始化来源和冷启动时序。 */
    private const val TAG = "MmkvInitializer"

    /** 当前进程已经完成初始化时返回的 MMKV 根目录；为空表示本进程还没有走过统一初始化入口。 */
    @Volatile
    private var initializedRootDir: String? = null

    /**
     * 确保当前进程已经初始化 MMKV。
     *
     * @param context 用于定位 app 私有 MMKV 目录的 Context；调用方应传入 Application 或 Provider 的应用 Context。
     * @param reason 本次初始化请求来源，只记录稳定原因码，避免把用户数据写入日志。
     */
    fun ensureInitialized(context: Context, reason: String): String {
        /** 已缓存的 MMKV 根目录；命中时说明当前进程已经完成初始化，可以直接复用。 */
        val cachedRootDir = initializedRootDir
        if (cachedRootDir != null) {
            logD(TAG) { "MMKV 已初始化 reason=$reason rootDir=$cachedRootDir" }
            return cachedRootDir
        }

        return synchronized(this) {
            /** 加锁后重新读取的 MMKV 根目录；用于避免并发冷启动时重复初始化。 */
            val lockedRootDir = initializedRootDir
            if (lockedRootDir != null) {
                logD(TAG) { "MMKV 已初始化 reason=$reason rootDir=$lockedRootDir" }
                lockedRootDir
            } else {
                /** 应用级 Context；使用 applicationContext 避免持有 Provider 或其他短生命周期 Context。 */
                val appContext = context.applicationContext ?: context
                /** MMKV 根目录；MMKV.initialize() 本身负责准备默认实例的 native 状态。 */
                val rootDir = MMKV.initialize(appContext)
                initializedRootDir = rootDir
                logD(TAG) { "MMKV 初始化完成 reason=$reason rootDir=$rootDir" }
                rootDir
            }
        }
    }
}
