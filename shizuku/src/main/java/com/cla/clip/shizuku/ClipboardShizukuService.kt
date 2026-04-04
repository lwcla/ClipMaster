package com.cla.clip.shizuku

import android.app.AppOpsManager
import android.app.AppOpsManagerHidden
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.os.Build
import androidx.core.graphics.createBitmap
import com.cla.clip.base.general.hasOverlayPermission
import com.cla.clip.base.general.logD
import com.cla.clip.base.general.logE
import com.cla.clip.base.general.logI
import com.cla.clip.base.general.utils.exceptionHandler
import dev.rikka.tools.refine.Refine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.lsposed.hiddenapibypass.HiddenApiBypass
import java.util.concurrent.atomic.AtomicBoolean

class ClipboardShizukuService(private val context: Context) : IClipboardShizukuService.Stub() {

    companion object {
        const val TAG = "ClipboardShizukuService"
    }

    private val appOpsManager by lazy { context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager }

    private val packageManager by lazy { context.packageManager }
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob() + exceptionHandler)
    private var callFlow = MutableStateFlow<ShizukuCallback?>(null)
    private var isRunning = AtomicBoolean(false)

    private var opNotedListener: AppOpsManagerHidden.OnOpNotedListener? = null

    private var job: Job? = null

    override fun exit() {
        logD(TAG) { "exit" }
        destroy()
    }

    override fun destroy() {
        logD(TAG) { "destroy" }
        isRunning.set(false)
        removeListener()
    }

    override fun start() {
        logD(TAG) { "start" }
        if (isRunning.get()) {
            logD(TAG) { "Service already running, skip" }
            return
        }
        isRunning.set(true)

        removeListener()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            HiddenApiBypass.addHiddenApiExemptions("Landroid/app")
        }

        // 先去授予悬浮窗权限，之后添加监听，否则剪贴板回调之后，发现还没有悬浮窗权限，就没办法读取剪贴板数据
        // 开启悬浮窗权限
        Refine.unsafeCast<AppOpsManagerHidden>(appOpsManager)
            .setMode(
                AppOpsManager.OPSTR_SYSTEM_ALERT_WINDOW,
                packageManager.getPackageUid(BuildConfig.APPLICATION_ID, 0),
                BuildConfig.APPLICATION_ID,
                AppOpsManager.MODE_ALLOWED
            )

        // DO NOT convert it to lambda due to R8 will break it down
        opNotedListener = ClipboardListener(this)

        // 监听剪贴板事件
        Refine.unsafeCast<AppOpsManagerHidden>(appOpsManager).startWatchingNoted(intArrayOf(30), opNotedListener)
    }

    override fun setCallback(shizukuCallback: ShizukuCallback) {
        callFlow.update { shizukuCallback }
    }

    fun handleOpNoted(packageName: String?) {
        if (!context.hasOverlayPermission()) {
            // 开启悬浮窗权限
            Refine.unsafeCast<AppOpsManagerHidden>(appOpsManager)
                .setMode(
                    AppOpsManager.OPSTR_SYSTEM_ALERT_WINDOW,
                    packageManager.getPackageUid(BuildConfig.APPLICATION_ID, 0),
                    BuildConfig.APPLICATION_ID,
                    AppOpsManager.MODE_ALLOWED
                )
        }

        job?.cancel()
        job = serviceScope.launch {
            delay(100) // 防抖
            val packageInfo = packageName?.let { packageManager.getPackageInfo(it, 0) }
            val name = packageInfo?.applicationInfo?.loadLabel(packageManager)?.toString().takeUnless { it.isNullOrBlank() } ?: "Unknown"
            // 获取图标 Drawable
            // Android 的 Bitmap 类实现了 Parcelable，并且针对 Binder 传输做了特殊优化（会将大图片数据放在 Ashmem 匿名共享内存中，而不是 Binder 缓冲区，只传递文件描述符）
            val bitmap = getIconBitmap(packageInfo?.applicationInfo?.loadIcon(packageManager))

            logD(TAG) { "OnOpNotedListener packageName=${packageName} name=$name bitmap=${bitmap?.width} x ${bitmap?.height}" }
            insert(packageName, name, bitmap)
        }
    }

    private fun removeListener() {
        opNotedListener?.let { listener ->
            runCatching {
                Refine.unsafeCast<AppOpsManagerHidden>(appOpsManager).stopWatchingNoted(listener)
            }.getOrElse {
                logE(TAG, it) { "停止监听剪贴板事件失败" }
            }
        }
        opNotedListener = null
    }

    private suspend fun insert(packageName: String?, appName: String, bitmap: Bitmap?) {
        // 1) fast path: 先试一次
        val cb = callFlow.value
        if (cb != null) {
            try {
                cb.onOpNoted(packageName, appName, bitmap)
                logD(TAG) { "第一次发送剪贴板信息 成功" }
                return
            } catch (e: android.os.DeadObjectException) {
                callFlow.update { current -> if (current === cb) null else current }
                logE(TAG) { "第一次发送失败 DeadObjectException" }
            } catch (e: android.os.RemoteException) {
                callFlow.update { current -> if (current === cb) null else current }
                logE(TAG) { "第一次发送失败 RemoteException" }
            } catch (tr: Throwable) {
                logE(TAG, tr) { "第一次发送失败" }
            }
        }

        // 2) 发送失败，可能是 callback 进程被系统杀死了，尝试启动前台服务唤醒它（部分 Android 12+ 设备可能对 start-foreground-service 有额外限制，但对 startservice 没有）
        val okCmd = startForegroundService()
        logI(TAG) { "callBack已经失活，尝试启动前台服务 okCmd=${okCmd}" }
        if (okCmd) {
            // 3) 等待 callback 重连（务必加超时，防止永久挂起）
            val rebound = withTimeoutOrNull(2_000) {
                callFlow.filterNotNull().first()
            }

            logD(TAG) { "等待前台服务重连，结果 rebound=${rebound != null}" }
            if (rebound != null) {
                currentCoroutineContext().ensureActive()
                // 4) 再投递一次
                try {
                    rebound.onOpNoted(packageName, appName, bitmap)
                    logD(TAG) { "第二次发送剪贴板信息 成功" }
                    return
                } catch (e: android.os.DeadObjectException) {
                    callFlow.update { current -> if (current === rebound) null else current }
                    logE(TAG, e) { "重连后回调仍断开" }
                } catch (e: android.os.RemoteException) {
                    callFlow.update { current -> if (current === rebound) null else current }
                    logE(TAG, e) { "重连后回调失败" }
                } catch (tr: Throwable) {
                    logE(TAG, tr) { "重连后回调失败" }
                }
            }
        }

        logE(TAG) { "前台服务启动失败或者启动超时，启动普通服务" }
        // 5) 兼容方案：启动普通服务（部分 Android 12+ 设备可能对 start-foreground-service 有额外限制，但对 startservice 没有）
        val okCompat = startService()
        logI(TAG) { "启动普通服务 okCompat=${okCompat}" }

        val rebound = withTimeoutOrNull(5_000) {
            callFlow.filterNotNull().first()
        }

        logD(TAG) { "等待普通服务重连，结果 rebound=${rebound != null}" }
        if (rebound != null) {
            currentCoroutineContext().ensureActive()
            try {
                rebound.onOpNoted(packageName, appName, bitmap)
                logD(TAG) { "第三次发送剪贴板信息 成功" }
            } catch (e: android.os.DeadObjectException) {
                callFlow.update { current -> if (current === rebound) null else current }
                logE(TAG, e) { "兼容方案重连后回调仍断开" }
            } catch (e: android.os.RemoteException) {
                callFlow.update { current -> if (current === rebound) null else current }
                logE(TAG, e) { "兼容方案重连后回调失败" }
            }
        }
    }

    /** 启动前台服务 */
    private fun startForegroundService(): Boolean {
        val process = ProcessBuilder(
            "am",
            "start-foreground-service",
            "--user", "0",
            "-n", "${BuildConfig.APPLICATION_ID}/.service.ClipboardService"
        ).redirectErrorStream(true).start()

        val exitCode = process.waitFor()
        val output = process.inputStream.bufferedReader().use { it.readText() }
        logD(TAG) { "start-foreground-service  exit=$exitCode  output=$output" }

        return (exitCode == 0) && !output.contains("Error:", ignoreCase = true)
    }

    /** 启动普通服务 */
    private fun startService(): Boolean {
        // 命令执行失败，可能是 Android 12+ 的限制导致的，尝试使用 startservice 作为兼容方案
        val process = ProcessBuilder(
            "am",
            "startservice",
            "--user", "0",
            "-n", "${BuildConfig.APPLICATION_ID}/.service.ClipboardService"
        ).redirectErrorStream(true).start()

        val exitCode = process.waitFor()
        val output = process.inputStream.bufferedReader().use { it.readText() }
        logD(TAG) { "startservice  exit=$exitCode  output=$output" }

        return (exitCode == 0) && !output.contains("Error:", ignoreCase = true)
    }

    // 辅助方法：将 Drawable 转为 Bitmap，并限制最大尺寸为 72x72
    private fun getIconBitmap(drawable: Drawable?): Bitmap? = runCatching {
        drawable ?: return null

        val size = 72

        val width = if (drawable.intrinsicWidth > size) size else drawable.intrinsicWidth
        val height = if (drawable.intrinsicHeight > size) size else drawable.intrinsicHeight

        // 如果本身就是合适大小的 BitmapDrawable，直接复用
        if (drawable is BitmapDrawable && drawable.bitmap.width <= size && drawable.bitmap.height <= size) {
            return drawable.bitmap
        }

        // 否则绘制一个新的 Bitmap
        val bitmap = createBitmap(width, height)
        val canvas = Canvas(bitmap)
        drawable.setBounds(0, 0, canvas.width, canvas.height)
        drawable.draw(canvas)
        return bitmap
    }.getOrNull()
}