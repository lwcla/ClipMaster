package com.cla.clip.shizuku

import android.app.AppOpsManager
import android.app.AppOpsManagerHidden
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.os.Build
import androidx.annotation.Keep
import androidx.core.graphics.createBitmap
import com.cla.clip.base.general.logD
import com.cla.clip.base.general.logE
import dev.rikka.tools.refine.Refine
import org.lsposed.hiddenapibypass.HiddenApiBypass

@Keep
class ClipboardShizukuService(private val context: Context) : IClipboardShizukuService.Stub() {

    companion object {
        const val TAG = "ClipboardShizukuService"
    }

    private lateinit var appOpsManager: AppOpsManager
    private lateinit var packageManager: PackageManager
    private lateinit var shizukuCallback: ShizukuCallback
    private lateinit var opNotedListener: AppOpsManagerHidden.OnOpNotedListener

    override fun exit() {
        logD(TAG) { "exit" }
        destroy()
    }

    override fun destroy() {
        logD(TAG) { "destroy" }
        Refine.unsafeCast<AppOpsManagerHidden>(appOpsManager).stopWatchingNoted(opNotedListener)
    }

    override fun start() {
        logD(TAG) { "start" }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            HiddenApiBypass.addHiddenApiExemptions("Landroid/app")
        }

        appOpsManager = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        packageManager = context.packageManager
        // DO NOT convert it to lambda due to R8 will break it down
        opNotedListener = AppOpsManagerHidden.OnOpNotedListener { op, uid, packageName, attributionTag, flags, result ->
            if (op.isNullOrBlank() || op != "android:write_clipboard") {
                return@OnOpNotedListener
            }

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
        // Allow self to draw floating window
        Refine.unsafeCast<AppOpsManagerHidden>(appOpsManager)
            .setMode(
                AppOpsManager.OPSTR_SYSTEM_ALERT_WINDOW,
                packageManager.getPackageUid(BuildConfig.APPLICATION_ID, 0),
                BuildConfig.APPLICATION_ID,
                AppOpsManager.MODE_ALLOWED
            )
        // Register AppOps Note listener
        Refine.unsafeCast<AppOpsManagerHidden>(appOpsManager)
            .startWatchingNoted(intArrayOf(30), opNotedListener)
    }

    override fun addCallback(shizukuCallback: ShizukuCallback) {
        this.shizukuCallback = shizukuCallback
    }

    private fun insert(packageName: String, appName: String, bitmap: Bitmap?) {
        logD(TAG) { "insert context=${context.applicationInfo.packageName} uid=${context.applicationInfo.uid} packageName=${BuildConfig.APPLICATION_ID} action=${BuildConfig.APPLICATION_ID}.ACTION_CLIP_CHANGED" }
//        val intent = Intent("${BuildConfig.APPLICATION_ID}.ACTION_CLIP_CHANGED").apply {
//            setPackage(BuildConfig.APPLICATION_ID) // 推荐：限制到本 app，避免外部 app 收到
//            putExtra("packageName", packageName)
//            putExtra("appName", appName)
//            putExtra("iconBitmap", bitmap)
//        }
//        context.sendBroadcast(intent)


//        val cr = context.contentResolver
//        val uri = "content://com.cla.clip.master.clip.data".toUri()
//
//        val values = ContentValues().apply {
//            put("packageName", packageName)
//            put("appName", appName)
//            put("iconBitmap", bitmap)
//        }
//
//        cr.insert(uri, values)


//        val cmd = buildString {
//            append("am broadcast ")
//            append("-a ").append(shellEscape("${BuildConfig.APPLICATION_ID}.ACTION_CLIP_CHANGED")).append(" ")
//            append("-p ").append(shellEscape(BuildConfig.APPLICATION_ID)).append(" ")
//            append("--es packageName ").append(shellEscape(packageName ?: "")).append(" ")
//            append("--es appName ").append(shellEscape(appName)).append(" ")
//            if (bitmap != null) {
//                val b64 = android.util.Base64.encodeToString(bitmap, android.util.Base64.NO_WRAP)
//                append("--es iconBitmapBase64 ").append(shellEscape(b64))
//            }
//        }.trim()

//        try {
//            val process = Shizuku.newProcess(arrayOf("sh", "-c", cmd), null, null)
//            val stdout = process.inputStream.bufferedReader().use { it.readText() }
//            val stderr = process.errorStream.bufferedReader().use { it.readText() }
//            val code = process.waitFor()
//            logI(TAG) { "broadcast exit=$code, out=$stdout, err=$stderr" }
//        } catch (e: Exception) {
//            logI(TAG) { "broadcast failed: ${e.message}" }
//        }

        logD(TAG){"starting service via am startservice..."}

        runCatching {
            val cmd = "am start-foreground-service --user 0 ${BuildConfig.APPLICATION_ID}/.service.ClipboardService"
            val process = ProcessBuilder(
                "am",
                "start-foreground-service",
                "--user",
                "0",
                "${BuildConfig.APPLICATION_ID}/.service.ClipboardService"
            )
                .redirectErrorStream(true)
                .start()

            val output = process.inputStream.bufferedReader().use { it.readText() }

            logD(TAG) { "startservice  output=$output" }
            val exitCode = process.waitFor()
            logD(TAG) { "startservice  exit=$exitCode" }

//            if (::shizukuCallback.isInitialized) {
//                shizukuCallback.onOpNoted(packageName, appName, bitmap)
//            }
        }.getOrElse {
            logE(TAG, it) { "startservice failed: " }
        }
    }


    private fun shellEscape(s: String): String {
        return "'" + s.replace("'", "'\\''") + "'"
    }

    // 辅助方法：将 Drawable 转为压缩后的 byte[]
    private fun getIconBitmap(drawable: Drawable?): Bitmap? {
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
    }
}