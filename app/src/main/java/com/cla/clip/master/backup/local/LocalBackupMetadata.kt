package com.cla.clip.master.backup.local

import com.cla.clip.base.general.backup.BackupKind
import com.cla.clip.base.general.backup.parseBackupKindFromFileName
import com.cla.clip.base.general.backup.parseBackupTimestampFromFileName

/**
 * 本地目录的纯文件元数据：列表排序和保留资格共用规则，不读取摘要或持有 Android URI。
 *
 * 自然顺序为最新优先；只在编译期使用，不参与备份序列化或反射，无需 keep。
 */
internal data class LocalBackupMetadata(
    /** SAF 原始显示名；只认既有应用前缀和正式 ZIP 后缀。 */
    val fileName: String,
    /** Provider 返回的字节大小；未知时为空，列表不打开 ZIP 补齐。 */
    val size: Long?,
    /** Provider 返回的修改时间，单位毫秒；未知时为空。 */
    val lastModified: Long?,
) : Comparable<LocalBackupMetadata> {
    /** 复用包协议解析器，非法或缺失的文件名时间返回空；每次列表读取只解析一次。 */
    private val filenameCreatedAt = parseBackupTimestampFromFileName(fileName)

    /** 仅判定文件名是否属于备份档案；目录由 SAF 层提前排除。 */
    val isBackupArchive: Boolean = fileName.startsWith("clip_master_backup_") && fileName.endsWith(".zip")

    /** 旧安全快照仍由既有文件名协议识别，不因缺少外部摘要而参与清理。 */
    val isSafetySnapshot: Boolean = parseBackupKindFromFileName(fileName) == BackupKind.Safety

    /** 普通备份必须有可解析文件名时间；修改时间不能给未知文件赋予删除资格。 */
    val isRegularBackup: Boolean = isBackupArchive && filenameCreatedAt != null && !isSafetySnapshot

    /** 与 WebDAV 一致：文件名时间优先，修改时间兜底，全部未知时置于列表尾部。 */
    val sortCreatedAt: Long = filenameCreatedAt ?: lastModified ?: 0L

    /** 倒序比较创建时间、修改时间与文件名，保证同秒条目不依赖 Provider 枚举顺序。 */
    override fun compareTo(other: LocalBackupMetadata): Int = NewestFirst.compare(this, other)

    companion object {
        /** 列表与保留删除候选共享自然顺序，避免两处 comparator 漂移。 */
        private val NewestFirst = compareByDescending<LocalBackupMetadata> { it.sortCreatedAt }
            .thenByDescending { it.lastModified ?: 0L }
            .thenByDescending { it.fileName }
    }
}
