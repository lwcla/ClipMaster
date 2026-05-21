package com.cla.clip.base.general.backup

/** 内部恢复计数器，用于组合各表恢复结果。 */
internal data class RestoreCounter(
    val inserted: Int = 0,
    val updated: Int = 0,
    val skipped: Int = 0,
) {
    /** 合并两个恢复计数器，便于 chunk 恢复逐段累积。 */
    operator fun plus(other: RestoreCounter): RestoreCounter {
        return RestoreCounter(
            inserted = inserted + other.inserted,
            updated = updated + other.updated,
            skipped = skipped + other.skipped
        )
    }
}
