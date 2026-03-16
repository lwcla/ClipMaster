package com.cla.clip.shizuku

import android.app.AppOpsManager
import android.app.AppOpsManagerHidden
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.os.Build
import androidx.annotation.Keep
import androidx.core.graphics.createBitmap
import androidx.core.net.toUri
import com.cla.clip.base.general.logD
import dev.rikka.tools.refine.Refine
import org.lsposed.hiddenapibypass.HiddenApiBypass
import java.io.ByteArrayOutputStream

@Keep
class ClipboardShizukuService(private val context: Context) : IClipboardShizukuService.Stub() {

    companion object {
        const val TAG = "ClipboardShizukuService"
    }

    private lateinit var appOpsManager: AppOpsManager
    private lateinit var packageManager: PackageManager
//    private lateinit var shizukuCallback: ShizukuCallback
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
            val bitmapBytes = bitmap?.toByteArray()

            logD(TAG) {
                """
                op=$op
                packageName=${packageName} 
                uid=$uid
                name=$name
                bitmap=${bitmap?.width} x ${bitmap?.height}
                bitmapBytes=${bitmapBytes?.size}
                result=$result
            """.trimIndent()
            }

            inset(packageName, name, bitmapBytes)
//            shizukuCallback.onOpNoted(packageName, name, bitmap)
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
//        this.shizukuCallback = shizukuCallback
    }

    private fun inset(packageName: String, name: String, bitmap: ByteArray?) {
        val cr = context.contentResolver
        val uri = "content://com.cla.clip.master.clip.data".toUri()

        val values = ContentValues().apply {
            put("packageName", packageName)
            put("appName", name)
            put("iconBitmap", bitmap)
        }

        cr.insert(uri, values)
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

    /** 辅助方法：将 Bitmap 压缩为 PNG 格式的 byte[]，以便通过 Binder 传输。 */
    private fun Bitmap.toByteArray(): ByteArray {
        val outputStream = ByteArrayOutputStream()
        compress(Bitmap.CompressFormat.PNG, 100, outputStream)
        return outputStream.toByteArray()
    }
}