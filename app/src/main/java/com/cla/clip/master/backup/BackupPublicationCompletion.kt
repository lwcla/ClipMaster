package com.cla.clip.master.backup

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/** 已确认的发布结论不能被结果存储异常反转；只在发布副作用完成后进入保存阶段。 */
internal class BackupPublicationCompletion(
    /** 保存实际结论，失败参数仅来自发布副作用，不来自结果存储。 */
    private val record: suspend (Boolean, Throwable?) -> Unit,
) {
    /** 发布失败记录失败并传播原错误；发布成功后的写盘失败使用独立契约。 */
    suspend fun <T> publish(block: suspend () -> T): T {
        /** 已确认发布结果，保存异常不进入此 try/catch。 */
        val published = try { block() } catch (failure: Throwable) {
            if (failure is CancellationException) throw failure
            BackupResultPersistence.commit(publicationSucceeded = false) { record(false, failure) }
            throw failure
        }
        BackupResultPersistence.commit(publicationSucceeded = true) { record(true, null) }
        return published
    }
}

/** 同一失败契约复用于本地结果和远端原子终态保存，不允许调用方将其当作发布失败。 */
internal object BackupResultPersistence {
    /** 已确认发布后屏蔽结果写盘阶段的协程取消；存储失败显式传播，不自动重写为失败。 */
    suspend fun <T> commit(publicationSucceeded: Boolean, block: suspend () -> T): T = withContext(NonCancellable) {
        try { block() } catch (failure: Throwable) {
            throw BackupResultPersistenceFailure(publicationSucceeded, failure)
        }
    }
}

/** 只保存结论及 cause，不保存内容或地址；页面据此区分文件发布与状态保存。 */
internal class BackupResultPersistenceFailure(
    /** true 表示产物已发布，不能在恢复后伪造一次备份失败。 */
    val publicationSucceeded: Boolean,
    /** 原始存储错误用于低敏类型诊断，禁止打印其原文。 */
    cause: Throwable,
) : Exception("result_store_failed", cause)
