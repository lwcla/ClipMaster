package com.cla.clip.master.backup.local

import android.net.Uri

/** 本地备份条目连接纯元数据与 SAF 句柄；不携带包外摘要，恢复时只复制 URI 指向的 ZIP。 */
data class LocalBackupFile(
    /** ZIP 显示名，保留文件名协议以供时间和旧 safety 识别。 */
    val fileName: String,
    /** 已授权目录中的 ZIP URI，只在本机使用，不参与序列化。 */
    val uri: Uri,
    /** 文件字节大小，未知时为空。 */
    val size: Long?,
    /** Provider 返回的修改时间毫秒值，未知时为空。 */
    val lastModified: Long?,
) {
    /** 文件名解析、排序及保留资格集中在纯模型，避免页面和 IO 层各自推断。 */
    internal val metadata = LocalBackupMetadata(fileName, size, lastModified)

    /** 展示时间与排序共用文件名时间/修改时间兜底规则。 */
    val sortCreatedAt: Long
        get() = metadata.sortCreatedAt

    /** 历史安全快照不作为普通恢复点展示。 */
    val isSafetySnapshot: Boolean
        get() = metadata.isSafetySnapshot

    /** 只有文件名可识别的普通 ZIP 参与列表展示与保留清理。 */
    val isRegularBackup: Boolean
        get() = metadata.isRegularBackup
}
