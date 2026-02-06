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
import androidx.core.graphics.scale
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
            if (op.isNullOrBlank()) {
                return@OnOpNotedListener
            }

            val packageInfo = packageName?.let { packageManager.getPackageInfo(it, 0) }
            val name = packageInfo?.applicationInfo?.loadLabel(packageManager)?.toString().takeUnless { it.isNullOrBlank() } ?: "Unknown"

            // 获取图标 Drawable
            val iconDrawable = packageInfo?.applicationInfo?.loadIcon(packageManager)
            // 转换为压缩数据
            val iconBytes = getCompressedIconData(iconDrawable) ?: ByteArray(0) // 如果没有图标，传空数组


            logD(TAG) {
                """
                name=$name
                iconBytes=${iconBytes.size}
                packageName=${packageName} 
                uid=$uid
                op=$op
            """.trimIndent()
            }

            shizukuCallback.onOpNoted(op, packageName, name, iconBytes)
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

    // 辅助方法：将 Drawable 转为压缩后的 byte[]
    private fun getCompressedIconData(drawable: Drawable?): ByteArray? {
        drawable ?: return null

        val size = 72

        // 1. 限制尺寸 (比如限制在 72x72 或 96x96，足够列表显示即可)
        // 如果原图太大，这里需要缩小
        val width = if (drawable.intrinsicWidth > size) size else drawable.intrinsicWidth
        val height = if (drawable.intrinsicHeight > size) size else drawable.intrinsicHeight

        val bitmap = if (drawable is BitmapDrawable) {
            // 如果原本就是 BitmapDrawable，且尺寸合适，直接用；否则缩放
            if (drawable.bitmap.width <= size) {
                drawable.bitmap
            } else {
                drawable.bitmap.scale(width, height)
            }
        } else {
            // 其他 Drawable (如 AdaptiveIconDrawable) 手动绘制
            val bitmap = createBitmap(width, height)
            val canvas = Canvas(bitmap)
            drawable.setBounds(0, 0, canvas.width, canvas.height)
            drawable.draw(canvas)
            bitmap
        }

        // 2. 压缩为 PNG 格式的字节流
        val stream = ByteArrayOutputStream()
        // PNG 是无损压缩，适合图标；质量参数对 PNG 无效，填 100 即可
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)
        val byteArray = stream.toByteArray()

        // 如果不是复用的 bitmap，记得 recycle 防止内存泄露（视情况而定，createBitmap生成的一般需要）
        // 但在 Service 且传递完就销毁的场景，GC 会处理，严谨点可以不做 recycle 避免时序问题

        return byteArray
    }
}