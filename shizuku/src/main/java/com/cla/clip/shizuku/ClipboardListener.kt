package com.cla.clip.shizuku

import android.app.AppOpsManagerHidden
import androidx.annotation.Keep

@Keep
class ClipboardListener(
    private val packageName: String,
    private val owner: ClipboardShizukuService
) : AppOpsManagerHidden.OnOpNotedListener {

    override fun onOpNoted(
        op: String,
        uid: Int,
        clipPackageName: String,
        attributionTag: String?,
        flags: Int,
        result: Int
    ) {
        if (op != "android:write_clipboard" || clipPackageName == packageName) {
            return
        }
        owner.handleOpNoted(clipPackageName)
    }

    override fun onOpNoted(code: Int, uid: Int, clipPackageName: String?, attributionTag: String?, flags: Int, result: Int) {
        if (clipPackageName == packageName) {
            return
        }
        owner.handleOpNoted(clipPackageName)
    }
}