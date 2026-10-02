package com.cla.clip.master.ui.widget.sourceapp

/** 按缓存与系统顺序解析来源图标；读取函数以 null 表达不可用，取消及异常不在此静默吞掉。 */
internal class SourceAppIconResolver<Icon>(
    /** 仅非空路径调用的缓存读取，hash 用于缓存版本身份。 */
    private val readCached: suspend (String, String?) -> Icon?,
    /** 仅非空包名调用的单应用图标读取，失败返回 null。 */
    private val readSystem: suspend (String) -> Icon?,
) {
    /** 先读取缓存，最终返回可展示图标或 null；平台读取由调用方安排到 IO 调度器。 */
    suspend fun load(
        /** 稳定来源身份，空包名代表未知来源。 */
        packageName: String,
        /** 来源缓存路径，空值表示此前未保存图标。 */
        iconPath: String?,
        /** 同一路径覆盖后的版本标识。 */
        iconHash: String?,
    ): Icon? {
        if (!iconPath.isNullOrBlank()) {
            /** 已成功解码的缓存图标；返回 null 也覆盖文件存在但内容损坏的情况。 */
            val cachedIcon = readCached(iconPath, iconHash)
            if (cachedIcon != null) return cachedIcon
        }
        // 未知来源没有有效系统身份，不能用空包名读取其它应用图标。
        return if (packageName.isBlank()) null else readSystem(packageName)
    }
}
