package com.cla.clip.master.backup.feedback

import androidx.work.WorkInfo
import com.cla.clip.base.general.backup.settings.BackupResultTarget
import com.cla.clip.master.backup.BackupExecutionCoordinator
import com.cla.clip.master.backup.BackupResultPersistenceFailure
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/** 真实结果提交与可替换提示边界，不依赖 Android Toast 或真实后台调度。 */
class BackupCompletionFeedbackTest {
    /** 手动成功只在结果保存之后提示，生成 Worker 正常返回不能替代这个证据。 */
    @Test fun manualSuccessIsShownAfterResultIsSaved() = runBlocking {
        /** 顺序记录用于防止保存尚未完成就宣称备份成功。 */
        val events = mutableListOf<String>()
        /** 提示替身只观察已经确认的成功或失败，不伪造 Android 可见性。 */
        val feedback = BackupCompletionFeedback(show = { succeeded -> events += "show:$succeeded" })
        assertTrue(feedback.complete("manual", BackupResultTarget.WebDav, true) {
            // 条件事务接受本轮，随后才允许提示。
            events += "saved"
            true
        })
        assertEquals(listOf("saved", "show:true"), events)
    }

    /** 真正保存的失败也需要提示，不能只提醒成功而让用户不知道后台失败。 */
    @Test fun manualFailureIsShownAsFailure() = runBlocking {
        /** 仅记录提示的结论，不保存异常正文或地址。 */
        val shown = mutableListOf<Boolean>()
        /** 该边界与生成失败、入队失败及上传失败共用真实失败提交契约。 */
        val feedback = BackupCompletionFeedback(show = { succeeded -> shown += succeeded })
        assertTrue(feedback.complete("manual", BackupResultTarget.WebDav, false) { true })
        assertEquals(listOf(false), shown)
    }

    /** 用户明确自动成功和失败都不提示；未知来源也不能被当成手动。 */
    @Test fun automaticAndUnknownSourcesNeverShowToast() = runBlocking {
        /** 来源和结论覆盖静默范围，不改变各任务真实结果保存。 */
        for (source in listOf("auto", "unknown", "")) {
            for (succeeded in listOf(true, false)) {
                /** 保存调用次数应保持一次，即使该来源没有提示。 */
                var saves = 0
                /** 所有非手动来源的提示必须为空。 */
                val shown = mutableListOf<Boolean>()
                /** 测试替换 UI 副作用，使用真实反馈规则。 */
                val feedback = BackupCompletionFeedback(show = { result -> shown += result })
                assertTrue(feedback.complete(source, BackupResultTarget.WebDav, succeeded) { saves++; true })
                assertEquals(1, saves)
                assertTrue("自动或未知来源不得弹出 Toast", shown.isEmpty())
            }
        }
    }

    /** 同包本地镜像与云端分开提交，不能产生两条相互矛盾的完成提示。 */
    @Test fun localMirrorDoesNotShowCloudCompletion() = runBlocking {
        /** 本地成功及失败都不进入手动云端提示。 */
        for (succeeded in listOf(true, false)) {
            /** 本地结果已保存，但不应触发云端文案。 */
            var shown = false
            /** 回调若被误调用会暴露目标筛选错误。 */
            val feedback = BackupCompletionFeedback(show = { shown = true })
            assertTrue(feedback.complete("manual", BackupResultTarget.Local, succeeded) { true })
            assertFalse(shown)
        }
    }

    /** 事务拒绝过期或终结请求时，不能用 Worker 正常退出制造成功 Toast。 */
    @Test fun rejectedResultDoesNotShowToast() = runBlocking {
        /** 本次提交被拒绝的任务没有完成提示。 */
        var shown = false
        /** 真实判断使用条件提交返回值，而不是观察历史时间。 */
        val feedback = BackupCompletionFeedback(show = { shown = true })
        assertFalse(feedback.complete("manual", BackupResultTarget.WebDav, true) { false })
        assertFalse(shown)
    }

    /** 重复进入只能依靠一次成功的结果事务发送一次，不重放最近历史结果。 */
    @Test fun repeatedCompletionOnlyShowsOnce() = runBlocking {
        /** 模拟当前目标已终结的事务状态。 */
        var finished = false
        /** 记录真实完成提示次数。 */
        val shown = mutableListOf<Boolean>()
        /** 两次请求共用同一事务替身和真实提示规则。 */
        val feedback = BackupCompletionFeedback(show = { succeeded -> shown += succeeded })
        // attempt 是重复请求次数；第二次事务拒绝已经终结的任务。
        repeat(2) { attempt ->
            assertEquals(attempt == 0, feedback.complete("manual", BackupResultTarget.WebDav, true) {
                if (finished) false else { finished = true; true }
            })
        }
        assertEquals(listOf(true), shown)
    }

    /** 保存失败继续传播既有结果存储异常，不能发成功提示或改写成上传失败。 */
    @Test fun resultStoreFailureDoesNotShowCompletion() = runBlocking {
        /** 合成存储故障不包含文件路径或用户数据。 */
        val failure = IllegalStateException("synthetic_store")
        /** 存储未确认时没有提示。 */
        var shown = false
        /** 反馈必须保留既有发布与结果保存的失败分类。 */
        val feedback = BackupCompletionFeedback(show = { shown = true })
        try {
            feedback.complete("manual", BackupResultTarget.WebDav, true) { throw failure }
            fail("结果保存失败必须传播")
        } catch (caught: BackupResultPersistenceFailure) {
            assertTrue(caught.publicationSucceeded)
            assertSame(failure, caught.cause)
        }
        assertFalse(shown)
    }

    /** Toast 展示错误或取消只是 UI 副作用失败，不能反转已经保存的备份。 */
    @Test fun toastErrorsAndCancellationDoNotReverseSavedResult() = runBlocking {
        /** 两类提示故障均必须与任务结果隔离。 */
        for (failure in listOf(IllegalStateException("synthetic_toast"), CancellationException("synthetic_toast_cancel"))) {
            /** 保存只能执行一次，不能因显示失败再提交失败或重传。 */
            var saves = 0
            /** 低敏诊断替身只保留同一个异常对象供断言。 */
            val reported = mutableListOf<Throwable>()
            /** 展示替身主动失败，测试实际隔离而不是 mock 调用次数。 */
            val feedback = BackupCompletionFeedback(show = { throw failure }, onShowFailure = { reported += it })
            assertTrue(feedback.complete("manual", BackupResultTarget.WebDav, true) { saves++; true })
            assertEquals(1, saves)
            assertEquals(listOf(failure), reported)
        }
    }

    /** 连诊断回调都失败时仍保留结果，防止日志故障被上层当成上传异常。 */
    @Test fun toastDiagnosticFailureIsAlsoIsolated() = runBlocking {
        /** UI 和诊断均失败的合成依赖。 */
        val feedback = BackupCompletionFeedback(show = { error("synthetic_toast") },
            onShowFailure = { error("synthetic_diagnostic") })
        assertTrue(feedback.complete("manual", BackupResultTarget.WebDav, true) { true })
    }

    /** 提交尚在等待时没有提示，真实完成信号不能提前于结果落盘。 */
    @Test fun waitingForResultCommitDoesNotShowToast() = runBlocking {
        /** 可控事务完成信号，避免时间等待及真实 DataStore。 */
        val saved = CompletableDeferred<Boolean>()
        /** 提交期间以及提交后的提示记录。 */
        val shown = mutableListOf<Boolean>()
        /** 用真实反馈流程等待替代事务。 */
        val feedback = BackupCompletionFeedback(show = { succeeded -> shown += succeeded })
        /** 立即进入挂起保存点，测试期间不依赖线程调度速度。 */
        val completion = async(start = CoroutineStart.UNDISPATCHED) {
            feedback.complete("manual", BackupResultTarget.WebDav, true) { saved.await() }
        }
        assertTrue(shown.isEmpty())
        saved.complete(true)
        assertTrue(completion.await())
        assertEquals(listOf(true), shown)
    }

    /** 真正系统暂停沿用排队规则，不经过终态提交或 Toast。 */
    @Test fun systemPauseDoesNotCommitOrNotify() = runBlocking {
        /** 模拟等待重调度的次数。 */
        var queues = 0
        /** 系统暂停不能出现失败保存。 */
        var saves = 0
        /** 系统暂停不能出现完成提示。 */
        var shown = false
        /** 让已有中断边界调用真实的新反馈流程，以保护两者接线语义。 */
        val feedback = BackupCompletionFeedback(show = { shown = true })
        assertTrue(BackupExecutionCoordinator.finishInterruption(BackupResultTarget.WebDav,
            WorkInfo.STOP_REASON_DEVICE_STATE, requeue = { queues++; true }, recordFailure = {
                feedback.complete("manual", BackupResultTarget.WebDav, false) { saves++; true }
            }))
        assertEquals(1, queues)
        assertEquals(0, saves)
        assertFalse(shown)
    }
}
