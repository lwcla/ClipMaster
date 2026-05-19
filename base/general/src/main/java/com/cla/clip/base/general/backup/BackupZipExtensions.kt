package com.cla.clip.base.general.backup

import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * 备份 zip 包写入扩展。
 *
 * zip 操作和 JSON 协议模型拆开，避免 `BackupJson` 同时承担序列化、checksum、压缩包 IO 多类职责。
 */
internal fun ZipOutputStream.putUtf8Entry(path: String, text: String) {
    putNextEntry(ZipEntry(path))
    write(text.toByteArray(Charsets.UTF_8))
    closeEntry()
}
