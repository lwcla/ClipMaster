package com.cla.clip.master.work

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 备份/恢复任务级互斥门。
 *
 * 串行生成与恢复，避免生成中间状态快照；完整只读包的本地保存和云端上传不进入此锁。
 */
object BackupTaskGate {
    /** 应用进程内的任务锁；只保护备份/恢复副作用，不跨进程承诺。 */
    private val mutex = Mutex()

    /** 串行执行一个完整备份/恢复任务，调用方负责决定失败如何展示或重试。 */
    suspend fun <T> runExclusive(block: suspend () -> T): T = mutex.withLock {
        block()
    }
}
