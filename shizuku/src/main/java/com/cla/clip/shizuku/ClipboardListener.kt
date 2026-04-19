package com.cla.clip.shizuku

import android.app.AppOpsManagerHidden
import androidx.annotation.Keep

@Keep
class ClipboardListener(
    private val owner: ClipboardShizukuService
) : AppOpsManagerHidden.OnOpNotedListener {

    override fun onOpNoted(
        op: String,
        uid: Int,
        packageName: String,
        attributionTag: String?,
        flags: Int,
        result: Int
    ) {
        if (op != "android:write_clipboard" || packageName == BuildConfig.APPLICATION_ID) {
            return
        }
        owner.handleOpNoted(packageName)
    }

    override fun onOpNoted(code: Int, uid: Int, packageName: String?, attributionTag: String?, flags: Int, result: Int) {
        if (packageName == BuildConfig.APPLICATION_ID) {
            return
        }
        owner.handleOpNoted(packageName)
    }
}