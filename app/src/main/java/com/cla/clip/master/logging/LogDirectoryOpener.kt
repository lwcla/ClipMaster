package com.cla.clip.master.logging

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract

/** 文本日志目录的外部 UI 契约，不复用只打开相册的图片 Helper。 */
internal object LogDirectoryOpener {
    /** 公共主存储的固定目录文档 URI，不传入自由路径或 file://。 */
    private fun directoryUri(): Uri = DocumentsContract.buildDocumentUri(
        "com.android.externalstorage.documents", "primary:Download/ClipMaster/logs",
    )

    /** 系统支持目录 VIEW 时直接浏览；失败交给调用方选择器降级，绝不切换日志开关。 */
    fun tryOpen(context: Context): Boolean = runCatching {
        context.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(directoryUri(), DocumentsContract.Document.MIME_TYPE_DIR))
    }.isSuccess

    /** 兼容系统文件选择器，不请求重新选择保存位置或长期目录授权。 */
    fun pickerIntent(): Intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
        addCategory(Intent.CATEGORY_OPENABLE)
        type = "*/*"
        if (Build.VERSION.SDK_INT >= 26) putExtra(DocumentsContract.EXTRA_INITIAL_URI, directoryUri())
    }

    /** 只打开用户在系统选择器授权的文本 URI，不读正文或保存新配置。 */
    fun tryOpenFile(context: Context, uri: Uri): Boolean = runCatching {
        context.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "text/plain")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
    }.isSuccess
}
