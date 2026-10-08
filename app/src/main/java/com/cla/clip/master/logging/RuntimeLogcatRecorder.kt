package com.cla.clip.master.logging

import android.content.Context
import android.os.Build
import android.os.Process
import android.util.Log
import com.cla.clip.master.BuildConfig
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/** 开发阶段持续读取本应用进程 Logcat；应用活着才工作，不创建后台保活任务。 */
internal object RuntimeLogcatRecorder {
    /** 此标签只输出固定启动/失败诊断，不能回放已采集正文产生循环。 */
    private const val TAG = "RuntimeLogcatRecorder"
    /** 同一进程重复初始化不会打开两个 reader 或覆盖同一日志。 */
    private val started = AtomicBoolean(false)

    /** Application attach 时尽早启动；存储和 logcat 都在独立线程执行。 */
    fun start(context: Context) {
        if (!started.compareAndSet(false, true)) return
        /** Application 尚未构造完成时使用基础应用 Context，不持有 Activity。 */
        val appContext = context.applicationContext ?: context
        /** 回读起点略早于 attach，覆盖后台线程创建文件期间已经输出的初始化日志。 */
        val since = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.ROOT).format(Date(System.currentTimeMillis() - 1000))
        try {
            Thread({
                // 捕获到的异常只终止日志线程，不能影响 Application/Worker。
                try { record(appContext, since) }
                catch (failure: Exception) {
                    Log.w(TAG, "日志录制停止 reasonCode=log_record_failed type=${failure.javaClass.simpleName}")
                }
            }, "ClipMasterLogRecorder").apply { isDaemon = true }.start()
        } catch (failure: Exception) {
            Log.w(TAG, "日志线程未启动 reasonCode=log_thread_failed type=${failure.javaClass.simpleName}")
        }
    }

    /** 只采本 PID 的各级日志与栈；流失败/退出均释放子进程，不通过业务日志递归留证。 */
    private fun record(context: Context, since: String) {
        /** Android 当前应用 PID，不请求 root 或 READ_LOGS。 */
        val pid = Process.myPid()
        /** 时间便于查找，随机身份保证同毫秒启动也不会覆写旧会话。 */
        val session = SimpleDateFormat("yyyyMMdd-HHmmss-SSS", Locale.ROOT).format(Date()) + "-$pid-${UUID.randomUUID()}"
        /** 平台存储只管理本会话卷，分卷纯规则不依赖 Android。 */
        val store = DownloadLogStore(context, session)
        RollingLogOutput(AppLogRecordingPolicy.maxFileBytes, store::open).use { output ->
            output.appendLine("日志录制开始 SDK=${Build.VERSION.SDK_INT} pid=$pid version=${BuildConfig.VERSION_NAME} session=$session")
            /** 参数列表不用 shell 拼接；系统仅允许普通应用读取自己的 UID 日志。 */
            val process = ProcessBuilder(AppLogRecordingPolicy.command(pid, since)).redirectErrorStream(true).start()
            try {
                process.inputStream.bufferedReader(Charsets.UTF_8).use { reader ->
                    while (!Thread.currentThread().isInterrupted) {
                        /** 无日志时阻塞等待，不轮询、不持有页面或备份任务锁。 */
                        val line = reader.readLine() ?: break
                        output.appendLine(line)
                    }
                }
                if (!Thread.currentThread().isInterrupted) {
                    output.appendLine("日志录制结束 reasonCode=logcat_exited exitCode=${process.waitFor()}")
                }
            } finally {
                // 即使写入、读取或关闭异常，也销毁只属于本次采集的 logcat。
                process.destroy()
            }
        }
    }
}
