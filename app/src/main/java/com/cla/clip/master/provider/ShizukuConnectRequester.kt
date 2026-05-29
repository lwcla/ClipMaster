package com.cla.clip.master.provider

import com.cla.clip.master.utils.ShizukuConnectRequestResult
import com.cla.clip.master.utils.ShizukuConnector
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Provider 身份查询使用的 Shizuku 连接请求薄封装。
 *
 * 该类只提交 best-effort 异步连接请求，不等待真实 bind 完成，避免 Provider 冷启动路径过早耦合完整连接流程。
 */
@Singleton
class ShizukuConnectRequester @Inject constructor(
    /** Shizuku 连接器；真实绑定仍由连接器内部协程和互斥锁控制。 */
    private val shizukuConnector: ShizukuConnector,
) {
    /**
     * 请求连接最新 Shizuku 服务。
     *
     * @param reasonCode 请求原因码，用于日志观察是否由身份查询频繁触发。
     */
    fun request(reasonCode: String): ShizukuConnectRequestResult {
        return shizukuConnector.requestConnect(reasonCode)
    }
}
