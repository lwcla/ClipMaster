package com.cla.clip.master.ui.page.search

/**
 * 搜索页即时来源勾选状态，仅在当前 ViewModel 内保存，不参与数据库、备份或反射协议。
 * All 与显式空集合查询等价，但菜单勾选不同；禁止从查询空集合反推全选。
 */
sealed interface SourceAppSelection {
    /** 全部来源行勾选；新出现的候选也自动属于全选。 */
    data object All : SourceAppSelection

    /** 显式勾选集合允许为空；空字符串是未知来源的合法包名。 */
    data class Explicit(
        /** 真实勾选的来源身份，不过滤空字符串，也不因候选缺失而丢弃。 */
        val packageNames: Set<String>,
    ) : SourceAppSelection
}

/** 派生有效查询包名；全选和无勾选均不限制来源，未知来源单独保留。 */
internal val SourceAppSelection.queryPackageNames: Set<String>
    get() = when (this) {
        SourceAppSelection.All -> emptySet()
        is SourceAppSelection.Explicit -> packageNames
    }

/** 菜单全选复选框只反映真实勾选模式，不把无勾选误显示成全选。 */
internal val SourceAppSelection.isAllSelected: Boolean
    get() = this == SourceAppSelection.All

/** 判断具体来源行勾选态；调用方只对候选行调用，All 自动涵盖新候选。 */
internal fun SourceAppSelection.contains(packageName: String): Boolean = when (this) {
    SourceAppSelection.All -> true
    is SourceAppSelection.Explicit -> packageName in packageNames
}
