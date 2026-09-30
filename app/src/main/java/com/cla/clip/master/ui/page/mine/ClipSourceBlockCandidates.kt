package com.cla.clip.master.ui.page.mine

import android.graphics.drawable.Drawable
import com.cla.clip.master.installedapps.InstalledAppInfo

/**
 * 从当前扫描结果构建过滤候选，已保存屏蔽项优先，同组保留读取器的名称/包名排序。
 *
 * 扫描结果已由读取器规范化、去重；未扫描到的保存规则不生成行，完整名单仍由页面草稿保存。
 * 此函数不读取数据库、文件或图标，也不修改输入。
 */
internal fun buildBlockedSourceAppCandidates(
    blockedPackages: Set<String>,
    installedApps: List<InstalledAppInfo>,
) = installedApps.map { app ->
    BlockedSourceAppCandidate(
        packageName = app.packageName,
        appName = app.appName,
        systemApp = app.isSystemApp,
        savedBlocked = app.packageName in blockedPackages,
        icon = app.icon,
    )
    // candidate 以已保存状态分组，稳定排序保留各组原有扫描顺序，避免重复名称排序。
}.sortedByDescending { candidate ->
    candidate.savedBlocked
}

/** 过滤页的当前扫描应用模型；只服务内存展示，不参与持久化、反射或外部协议。 */
data class BlockedSourceAppCandidate(
    /** 扫描得到的包名，是保存匹配和列表稳定 key 的唯一身份。 */
    val packageName: String,
    /** 扫描得到的应用名称；缺失时保留空串，不用历史名称或包名替代。 */
    val appName: String,
    /** 扫描得到的系统应用标记；默认隐藏，仅由系统应用开关放行。 */
    val systemApp: Boolean,
    /** 已提交名单中的屏蔽状态，仅用于候选排序，当前勾选以页面完整草稿为准。 */
    val savedBlocked: Boolean,
    /** 扫描时读取的 Application 图标；仅页面内存持有，null 显示默认图标。 */
    val icon: Drawable? = null,
)
