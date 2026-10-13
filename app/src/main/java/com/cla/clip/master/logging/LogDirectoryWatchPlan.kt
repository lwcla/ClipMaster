package com.cla.clip.master.logging

import java.io.File

/** 有界三层观察计划保留父锚点，目标创建/移出/重建后可重新注册，不全盘监测。 */
internal object LogDirectoryWatchPlan {
    /** 返回当前存在的 logs、ClipMaster、Download；每次文件身份事件重新计算。 */
    fun paths(directory: File): List<File> = generateSequence(directory.canonicalFile) { it.parentFile }
        .take(3).filter { it.isDirectory }.toList()
}
