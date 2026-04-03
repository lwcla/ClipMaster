package com.cla.clip.shizuku

import android.app.AppOpsManager
import android.app.AppOpsManagerHidden
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.os.Build
import androidx.annotation.Keep
import androidx.core.graphics.createBitmap
import com.cla.clip.base.general.logD
import com.cla.clip.base.general.logE
import com.cla.clip.base.general.logI
import com.cla.clip.base.general.logV
import com.cla.clip.base.general.utils.exceptionHandler
import dev.rikka.tools.refine.Refine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.lsposed.hiddenapibypass.HiddenApiBypass

@Keep
class ClipboardShizukuService(private val context: Context) : IClipboardShizukuService.Stub() {

    companion object {
        const val TAG = "ClipboardShizukuService"
    }

    private var appOpsManager: AppOpsManager? = null
    private lateinit var opNotedListener: AppOpsManagerHidden.OnOpNotedListener

    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(Dispatchers.IO + serviceJob + exceptionHandler)

    private var job: Job? = null

    private var callFlow = MutableStateFlow<ShizukuCallback?>(null)

    override fun exit() {
        logD(TAG) { "exit" }
        destroy()
    }

    override fun destroy() {
        logD(TAG) { "destroy" }
        appOpsManager?.let {
            Refine.unsafeCast<AppOpsManagerHidden>(appOpsManager).stopWatchingNoted(opNotedListener)
        }
    }

    override fun start() {
        logD(TAG) { "start" }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            HiddenApiBypass.addHiddenApiExemptions("Landroid/app")
        }

        val appOpsManager = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        this.appOpsManager = appOpsManager
        val packageManager = context.packageManager
        // DO NOT convert it to lambda due to R8 will break it down
        opNotedListener = AppOpsManagerHidden.OnOpNotedListener { op, uid, packageName, attributionTag, flags, result ->
            if (op.isNullOrBlank() || op != "android:write_clipboard" || packageName == BuildConfig.APPLICATION_ID) {
                return@OnOpNotedListener
            }

            job?.cancel()
            job = serviceScope.launch {
                val packageInfo = packageName?.let { packageManager.getPackageInfo(it, 0) }
                val name = packageInfo?.applicationInfo?.loadLabel(packageManager)?.toString().takeUnless { it.isNullOrBlank() } ?: "Unknown"
                // 获取图标 Drawable
                // Android 的 Bitmap 类实现了 Parcelable，并且针对 Binder 传输做了特殊优化（会将大图片数据放在 Ashmem 匿名共享内存中，而不是 Binder 缓冲区，只传递文件描述符）
                val bitmap = getIconBitmap(packageInfo?.applicationInfo?.loadIcon(packageManager))

                logD(TAG) {
                    """
                    op=$op
                    packageName=${packageName} 
                    uid=$uid
                    name=$name
                    bitmap=${bitmap?.width} x ${bitmap?.height}
                    result=$result
                """.trimIndent()
                }
                insert(packageName, name, bitmap)
            }
        }
        // 开启悬浮窗权限
        Refine.unsafeCast<AppOpsManagerHidden>(appOpsManager)
            .setMode(
                AppOpsManager.OPSTR_SYSTEM_ALERT_WINDOW,
                packageManager.getPackageUid(BuildConfig.APPLICATION_ID, 0),
                BuildConfig.APPLICATION_ID,
                AppOpsManager.MODE_ALLOWED
            )
        // 监听剪贴板事件
        Refine.unsafeCast<AppOpsManagerHidden>(appOpsManager).startWatchingNoted(intArrayOf(30), opNotedListener)
    }

    override fun setCallback(shizukuCallback: ShizukuCallback) {
        callFlow.update { shizukuCallback }
    }

    private suspend fun insert(packageName: String, appName: String, bitmap: Bitmap?) {

        // 1) fast path: 先试一次
        val cb = callFlow.value
        if (cb != null) {
            try {
                logD(TAG) { "insert : 尝试第一次发送剪贴板信息" }
                cb.onOpNoted(packageName, appName, bitmap)
                logD(TAG) { "insert : 尝试第一次发送剪贴板信息 成功" }
                return
            } catch (e: android.os.DeadObjectException) {
                logE(TAG) { "insert : 第一次发送失败 DeadObjectException" }
                callFlow.update { current ->
                    if (current === cb) null else current
                }
            } catch (e: android.os.RemoteException) {
                logE(TAG) { "insert : 第一次发送失败 RemoteException" }
                callFlow.update { current ->
                    if (current === cb) null else current
                }
            }
        }

        logI(TAG) { "callBack已经失活，需要去启动ClipboardService" }

        // 2) 拉起 app/service
        startClipboardServiceCompat()

        // 3) 等待 callback 重连（务必加超时，防止永久挂起）
        val rebound = withTimeoutOrNull(5_000) {
            callFlow.filterNotNull().first()
        } ?: run {
            logE(TAG) { "等待 ShizukuCallback 超时" }
            return
        }

        currentCoroutineContext().ensureActive()
        // 4) 再投递一次
        try {
            logV(TAG) { "准备调用回调函数通知剪贴板事件" }
            rebound.onOpNoted(packageName, appName, bitmap)
            logV(TAG) { "调用回调函数通知剪贴板事件完成---->" }
        } catch (e: android.os.DeadObjectException) {
            callFlow.update { current ->
                if (current === rebound) null else current
            }
            logE(TAG, e) { "重连后回调仍断开" }
        } catch (e: android.os.RemoteException) {
            callFlow.update { current ->
                if (current === rebound) null else current
            }
            logE(TAG, e) { "重连后回调失败" }
        }
    }

    private fun startClipboardServiceCompat(){
        val process = ProcessBuilder(
            "am",
            "start-foreground-service",
            "--user", "0",
            "-n", "${BuildConfig.APPLICATION_ID}/.service.ClipboardService"
        ).redirectErrorStream(true).start()

        val exitCode = process.waitFor()
        val output = process.inputStream.bufferedReader().use { it.readText() }
        logD(TAG) { "start-foreground-service  exit=$exitCode  output=$output" }

        val okCmd = (exitCode == 0) && !output.contains("Error:", ignoreCase = true)
        if (!okCmd) {
            logE(TAG) { "尝试使用 start-foreground-service 兼容方案失败，准备使用 startservice 兼容方案" }
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

            val okStartServiceCmd = (exitCode == 0) && !output.contains("Error:", ignoreCase = true)
            if (!okStartServiceCmd) {
                logE(TAG) { "尝试使用 startservice 兼容方案失败，无法启动 ClipboardService，剪贴板事件将无法正常通知到前台服务" }
                return
            }
        }
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