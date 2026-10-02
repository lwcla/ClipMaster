package com.cla.clip.master.sourceapp

import com.cla.clip.base.general.dao.SourceAppData
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** 完整文件发布后的轻量结果，不持有 Drawable 或 Bitmap。 */
internal data class PublishedSourceAppIcon(
    /** 版本化正式文件的绝对路径，只用于私有存储与 UI。 */
    val path: String,
    /** 与既有 Provider 一致的稳定图标 hash。 */
    val hash: String,
    /** 同一图标提取的可空主色。 */
    val color: Int?,
)

/** 可替换读写边界的单项修复流程，测试使用字符串而非平台图像。 */
internal class SourceAppIconRepair<Icon>(
    /** 读取缓存；null 表示不可用，异常和取消按上层契约处理。 */
    private val readCached: suspend (SourceAppData) -> Icon?,
    /** 按非空身份读取系统图标。 */
    private val readSystem: suspend (String) -> Icon?,
    /** 完整发布文件，失败返回 null。 */
    private val publish: suspend (String, Icon) -> PublishedSourceAppIcon?,
    /** 单项条件提交，false 表示来源删除或并发新版本。 */
    private val commit: suspend (SourceAppData, PublishedSourceAppIcon) -> Boolean,
) {
    /** 缓存可用即结束；每个包独立发布和条件提交，取消不得转为默认成功。 */
    suspend fun repair(source: SourceAppData): Boolean {
        currentCoroutineContext().ensureActive()
        if (readCached(source) != null) return true
        if (source.packageName.isBlank()) return false
        /** 平台图标只存活于当前单项任务，不跨包累计。 */
        val icon = readSystem(source.packageName) ?: return false
        currentCoroutineContext().ensureActive()
        /** 完整发布后才允许把版本路径交给数据库。 */
        val published = publish(source.packageName, icon) ?: return false
        currentCoroutineContext().ensureActive()
        return commit(source, published)
    }
}
