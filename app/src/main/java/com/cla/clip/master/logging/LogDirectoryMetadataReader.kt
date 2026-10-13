package com.cla.clip.master.logging

import java.io.File
import java.io.IOException

/** 一级日志文件元数据与归属校验，不依赖 Android，也不读取正文或执行删除。 */
internal object LogDirectoryMetadataReader {
    /** 缺失为 0，其他失败显式抛出；符号链接不能把目录外条目统计进来。 */
    fun count(directory: File): Int = entries(directory).count {
        it.isFile && LogCapturePolicy.isLogFile(it.name) && it.canonicalFile.parentFile == directory.canonicalFile
    }

    /** 原生受控文件族只允许普通本目录文件，未知安装不获得轮转淘汰授权。 */
    fun validateManagedFiles(directory: File, owned: Boolean) {
        /** 仅检查新文件族，不冒领或删除旧会话日志。 */
        val managed = entries(directory).filter { LogCapturePolicy.isManagedFile(it.name) }
        if (managed.any { !it.isFile || it.canonicalFile.parentFile != directory.canonicalFile }) {
            throw IOException("log_file_identity_invalid")
        }
        if (!owned && managed.isNotEmpty()) throw IOException("log_ownership_conflict")
    }

    /** 文件系统的 null 表示不可枚举，不能把它转换成空集合。 */
    private fun entries(directory: File): List<File> {
        if (!directory.exists()) return emptyList()
        if (!directory.isDirectory) throw IOException("log_directory_invalid")
        return (directory.listFiles() ?: throw IOException("log_directory_unreadable")).toList()
    }
}
