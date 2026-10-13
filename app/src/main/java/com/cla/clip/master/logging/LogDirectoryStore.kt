package com.cla.clip.master.logging

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import android.os.FileObserver
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import java.io.File
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/** 公共日志元数据与固定文件族归属；不读取正文、不删除历史会话或任意目录。 */
@Singleton
internal class LogDirectoryStore @Inject constructor(
    /** 平台访问只持有应用 Context，独立于页面存活。 */
    @param:ApplicationContext private val context: Context,
) {
    /** 固定公共目录，规范化后仍必须处于公共 Download 下。 */
    @Suppress("DEPRECATION")
    val directory: File get() = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "ClipMaster/logs")

    /** 主进程独占准备入口；旧安装文件族未取得归属时拒绝轮转覆盖。 */
    @Synchronized
    fun prepare(): File {
        requireLegacyPermission()
        /** 下载目录仅用于范围校验，不把外部传入路径当保存目标。 */
        @Suppress("DEPRECATION")
        val downloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS).canonicalFile
        /** 目标不可经符号路径跳出固定 Download/ClipMaster/logs。 */
        val target = directory.canonicalFile
        check(target.path == File(downloads, "ClipMaster/logs").path)
        /** 安装内归属标记不参与系统/用户备份，重装不冒领旧受控文件。 */
        val ownership = File(context.noBackupFilesDir, "log_capture_owner")
        if (target.exists() && !target.isDirectory) throw IOException("log_directory_invalid")
        if (!target.exists() && !target.mkdirs()) throw IOException("log_directory_create_failed")
        LogDirectoryMetadataReader.validateManagedFiles(target, ownership.isFile)
        if (!ownership.isFile) ownership.writeText("owned-v1")
        /** 只追加固定基础文件；由实际进程反馈创建和轮转权限错误。 */
        return File(target, LogCapturePolicy.fileName)
    }

    /** 缺失目录为 0；权限或枚举失败显式抛出，调用方显示失败而非虚构空目录。 */
    fun count(): Int {
        requireLegacyPermission()
        return LogDirectoryMetadataReader.count(directory)
    }

    /** 页面订阅期间保留父锚点并事件重绑；逐条 MODIFY 不触发重复统计。 */
    @OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
    fun observeCount(): Flow<Int> = channelFlow {
        /** 原生回调只提交合并信号，不访问目录或操作 observer 集合。 */
        val signals = Channel<Unit>(Channel.CONFLATED)
        /** 始终只由本 IO 协程读写，取消时完整释放三层监测。 */
        var observers = emptyList<FileObserver>()
        /** 包括目录自身移出；忽略 MODIFY 和停止观察产生的其他内核事件。 */
        val mask = FileObserver.CREATE or FileObserver.DELETE or FileObserver.MOVED_FROM or
            FileObserver.MOVED_TO or FileObserver.DELETE_SELF or FileObserver.MOVE_SELF
        signals.trySend(Unit)
        try {
            // 每个身份事件都重新注册，路径相同也可能已换 inode，不能仅比较路径字符串。
            for (signal in signals) {
                observers.forEach { it.stopWatching() }
                observers = LogDirectoryWatchPlan.paths(directory).map { watched ->
                    /** 目标不存在时仍观察父目录，逐级创建事件会再次进入本循环。 */
                    @Suppress("DEPRECATION")
                    object : FileObserver(watched.path, mask) {
                        /** 只提交有效身份事件；已停止观察的 IGNORED 不制造重绑循环。 */
                        override fun onEvent(event: Int, path: String?) {
                            if (event and mask != 0) signals.trySend(Unit)
                        }
                    }
                }
                observers.forEach { it.startWatching() }
                send(signal)
            }
        } finally {
            observers.forEach { it.stopWatching() }
            signals.close()
        }
    }.buffer(Channel.CONFLATED).debounce(150).map { count() }.flowOn(Dispatchers.IO)

    /** 旧 Android 的共享存储写权限由前台开关申请，后台只报告不可用。 */
    private fun requireLegacyPermission() {
        if (Build.VERSION.SDK_INT < 29 && context.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            throw SecurityException("log_storage_permission_missing")
        }
    }
}
