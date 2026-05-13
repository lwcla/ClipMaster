package com.cla.clip.shizuku

import android.app.AppOpsManagerHidden
import androidx.annotation.Keep

@Keep
/**
 * Shizuku 进程中的 AppOps 剪贴板事件监听器。
 *
 * 使用具名类而不是 lambda，是因为 R8 可能破坏隐藏 API 回调签名；监听到其他应用写剪贴板后交给 ClipboardShizukuService 防抖处理。
 */
class ClipboardListener(
    /** 当前应用包名，收到自身剪贴板写入事件时需要忽略，避免循环读取。 */
    private val packageName: String,

    /** 监听事件拥有者，负责后续取来源应用信息并回调主进程。 */
    private val owner: ClipboardShizukuService
) : AppOpsManagerHidden.OnOpNotedListener {

    /** 字符串 op 回调版本，部分系统会走这个隐藏 API 签名。 */
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

    /** 数字 code 回调版本，兼容不同 Android 版本或 ROM 的隐藏 API 签名差异。 */
    override fun onOpNoted(code: Int, uid: Int, clipPackageName: String?, attributionTag: String?, flags: Int, result: Int) {
        if (clipPackageName == packageName) {
            return
        }
        owner.handleOpNoted(clipPackageName)
    }
}
