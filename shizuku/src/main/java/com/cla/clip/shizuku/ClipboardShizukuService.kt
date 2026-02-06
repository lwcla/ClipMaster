package com.cla.clip.shizuku

import android.app.AppOpsManager
import android.app.AppOpsManagerHidden
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.annotation.Keep
import dev.rikka.tools.refine.Refine
import org.lsposed.hiddenapibypass.HiddenApiBypass

@Keep
class ClipboardShizukuService(private val context: Context) : IClipboardShizukuService.Stub() {
    private lateinit var appOpsManager: AppOpsManager
    private lateinit var packageManager: PackageManager
    private lateinit var shizukuCallback: ShizukuCallback
    private lateinit var opNotedListener: AppOpsManagerHidden.OnOpNotedListener

    override fun exit() {
        destroy()
    }

    override fun destroy() {
        // LogUtil._d("ClipboardShizukuService destroy")
        Refine.unsafeCast<AppOpsManagerHidden>(appOpsManager).stopWatchingNoted(opNotedListener)
    }

    override fun start() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            HiddenApiBypass.addHiddenApiExemptions("Landroid/app")
        };
        // LogUtil._d("ClipboardShizukuService init")
        appOpsManager = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        packageManager = context.packageManager
        // DO NOT convert it to lambda due to R8 will break it down
        opNotedListener = object : AppOpsManagerHidden.OnOpNotedListener {
            override fun onOpNoted(op: String?, uid: Int, packageName: String?, attributionTag: String?, flags: Int, result: Int) {


                val packageInfo = packageName?.let { packageManager.getPackageInfo(it, 0) }
                val name = packageInfo?.applicationInfo?.loadLabel(packageManager).toString()
//                val icon = packageInfo?.applicationInfo?.loadIcon(packageManager)

                println("ClipboardShizukuService onOpNoted: packageName=$packageName name=$name ${packageInfo?.applicationInfo?.name}")


//                Toast.makeText(context, "onOpNoted: packageName=$packageName name=$name ${packageInfo?.applicationInfo?.name}", Toast.LENGTH_SHORT).show()


                shizukuCallback.onOpNoted(op, uid, packageName, name.ifBlank { packageInfo?.applicationInfo?.name }, attributionTag, flags, result)
            }
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
}