package com.cla.clip.master.work

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 备份/恢复任务级互斥门。
 *
 * `BackupRepository` 内部已经保护数据库快照读写，但手动备份、自动备份和恢复还包含 SAF/WebDAV 文件写入、
 * 保留清理等外部副作用；统一在应用进程内串行这些长任务，避免恢复期间生成中间状态备份。
 */
object BackupTaskGate {
    /** 应用进程内的任务锁；只保护备份/恢复副作用，不跨进程承诺。 */
    private val mutex = Mutex()

    /** 串行执行一个完整备份/恢复任务，调用方负责决定失败如何展示或重试。 */
    suspend fun <T> runExclusive(block: suspend () -> T): T = mutex.withLock {
        block()
    }
}
