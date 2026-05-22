package com.cla.clip.base.general.magnet

/** 第一版磁力搜索唯一内置合法来源。 */
const val MAGNET_SOURCE_ACADEMIC_TORRENTS = "academic_torrents"

/**
 * 磁力来源白名单。
 *
 * 第一版只允许 Academic Torrents；恢复、下载记录和搜索结果都通过稳定 `sourceId` 判断来源，
 * 避免备份包或未来代码误把未知来源写入用户数据。
 */
object MagnetSourceProvider {
    /** 判断来源是否属于当前版本允许写入和恢复的白名单。 */
    fun isAllowed(sourceId: String): Boolean {
        return sourceId == MAGNET_SOURCE_ACADEMIC_TORRENTS
    }
}
