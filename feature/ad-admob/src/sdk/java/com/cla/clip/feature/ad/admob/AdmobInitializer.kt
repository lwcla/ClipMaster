package com.cla.clip.feature.ad.admob

import android.content.Context
import com.google.android.libraries.ads.mobile.sdk.MobileAds
import com.google.android.libraries.ads.mobile.sdk.initialization.InitializationConfig
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

/** 进程级懒初始化；首个有效广告请求才在 IO 线程初始化，结果共享且不自动重试。 */
@Singleton
internal class AdmobInitializer @Inject constructor(
    /** 初始化仅使用应用 Context。 */
    @ApplicationContext private val context: Context,
) {
    /** 只承载单次 SDK 初始化，不启动后台轮询。 */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    /** 保护并发页面首次进入时的一次性初始化。 */
    private val mutex = Mutex()
    /** 缓存进程初始化结果，包括异常；页面超时不重复初始化 SDK。 */
    private var initialization: Deferred<Unit>? = null

    /** 调用方必须先通过隐私与主进程检查；取消只停止本次等待。 */
    suspend fun awaitReady() {
        /** 多个页面共享同一初始化任务。 */
        val pending = mutex.withLock {
            initialization ?: scope.async {
                suspendCancellableCoroutine<Unit> { continuation ->
                    MobileAds.initialize(context, InitializationConfig.Builder(BuildConfig.ADMOB_APP_ID).build()) {
                        if (continuation.isActive) continuation.resume(Unit)
                    }
                }
            }.also { initialization = it }
        }
        pending.await()
    }
}
