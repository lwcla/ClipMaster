package com.cla.clip.base.general.search

import androidx.sqlite.db.SimpleSQLiteQuery
import androidx.sqlite.db.SupportSQLiteQuery
import com.cla.clip.base.general.entity.ClipVisibilityScope

/** 保守限制 IN 绑定数量，为时间、范围等参数保留余量；更大的集合改用单个编码参数。 */
private const val MAX_INLINE_SOURCE_BINDINGS = 400

/** SQLite hex(TEXT) 使用 UTF-8 大写十六进制；竖线不会出现在编码中，可作为无碰撞身份边界。 */
private const val SOURCE_PACKAGE_HEX_DIGITS = "0123456789ABCDEF"

/** 构建七字段字面短语查询；字段内连续包含，字段间 OR，公共筛选条件在 OR 外层。 */
internal fun buildClipFieldSearchQuery(
    /** 本次生效字段；空集合必须返回零结果，不能作为全选。 */ fields: Set<ClipSearchField>,
    /** 去首尾空白后的完整短语；空词仅保留公共筛选。 */ keyword: String,
    /** 左闭时间边界。 */ startTime: Long?,
    /** 右开时间边界。 */ endTime: Long?,
    /** 用户来源限制，空集合表示不限制。 */ sourceAppPackages: Set<String>,
    /** 名称解析结果；仅非空词且选中名称时要求非 null。 */ matchedSourcePackages: Set<String>?,
    /** 普通/折叠隔离以及排序时间轴。 */ visibilityScope: ClipVisibilityScope,
): SupportSQLiteQuery {
    /** 稳定枚举顺序保证集合点击顺序不会改变 SQL 或绑定参数。 */
    val orderedFields = ClipSearchField.entries.filter { it in fields }
    /** 仅原始文本列参与字面匹配；来源名称经独立投影映射为包名身份。 */
    val columns = orderedFields.mapNotNull { it.searchColumn() }
    /** 空词不需要关联预览，避免浏览数据时增加无意义连接。 */
    val needsPreview = keyword.isNotBlank() && columns.any { it.startsWith("p.") }
    /** 折叠窗口基于折叠动作时间。 */
    val folded = visibilityScope == ClipVisibilityScope.FoldedOnly
    /** 列名只来自固定分支，不拼接外部输入。 */
    val timeColumn = if (folded) "c.folded_at" else "c.timestamp"
    /** 绑定值严格按 SQL 中占位符顺序追加。 */
    val arguments = mutableListOf<Any>()
    /** 查询只观察剪贴与预览，不引入来源图标表的分页失效。 */
    val sql = StringBuilder("SELECT c.* FROM clips c")
    if (needsPreview) sql.append(" LEFT JOIN link_previews p ON c.link = p.link")
    sql.append(" WHERE c.deleted_at = 0 AND c.is_folded = ?")
    arguments += if (folded) 1 else 0
    if (startTime != null) { sql.append(" AND $timeColumn >= ?"); arguments += startTime }
    if (endTime != null) { sql.append(" AND $timeColumn < ?"); arguments += endTime }
    if (sourceAppPackages.isNotEmpty()) sql.append(" AND ").append(sourcePackagePredicate(sourceAppPackages, arguments))
    if (fields.isEmpty()) {
        // 非法空字段在空关键词时同样关闭结果，不能退回默认全选。
        sql.append(" AND 0")
    } else if (keyword.isNotBlank()) {
        /** 每个已选字段贡献一条候选分支，空名称集合只贡献 false。 */
        val predicates = orderedFields.map { field ->
            if (field == ClipSearchField.SourceName) {
                sourcePackagePredicate(requireNotNull(matchedSourcePackages), arguments)
            } else {
                arguments += "%${escapeSearchLikeLiteral(keyword)}%"
                "${field.searchColumn()} LIKE ? ESCAPE '\\'"
            }
        }
        sql.append(" AND (").append(predicates.joinToString(" OR ")).append(")")
    }
    sql.append(" ORDER BY CASE WHEN c.pinned_time > 0 THEN 1 ELSE 0 END DESC,")
    if (keyword.isNotBlank() && columns.isNotEmpty()) {
        /** 任一已选文本列精确相等都只贡献一次排序优先级，不因多字段重复命中加分。 */
        val exactMatches = columns.map { column -> arguments += keyword; "$column = ? COLLATE NOCASE" }
        sql.append(" CASE WHEN (").append(exactMatches.joinToString(" OR ")).append(") THEN 1 ELSE 0 END DESC,")
    }
    if (!folded) sql.append(" c.pinned_time DESC,")
    sql.append(" $timeColumn DESC, c.timestamp DESC, c.id DESC")
    return SimpleSQLiteQuery(sql.toString(), arguments.toTypedArray())
}

/** 字段白名单映射；名称不是剪贴表列，交给名称投影解析，不得伪造 SQL 列。 */
private fun ClipSearchField.searchColumn(): String? = when (this) {
    ClipSearchField.Content -> "c.content"
    ClipSearchField.Link -> "c.link"
    ClipSearchField.LinkTitle -> "p.title"
    ClipSearchField.LinkDescription -> "p.description"
    ClipSearchField.SiteName -> "p.site_name"
    ClipSearchField.SourceName -> null
    ClipSearchField.SourcePackage -> "c.source_app_package"
}

/** 为手动来源与名称分支共用身份匹配；两组最多各 400 个绑定，合计仍低于常见 999 上限。 */
private fun sourcePackagePredicate(
    /** 本分支允许的稳定来源身份。 */ packages: Set<String>,
    /** 由上层统一维护的 SQL 绑定顺序。 */ arguments: MutableList<Any>,
): String = when {
    packages.isEmpty() -> "0"
    packages.size > MAX_INLINE_SOURCE_BINDINGS -> {
        arguments += encodeSearchSourcePackages(packages)
        // null 与空包名严格区分；编码边界保留空身份，禁止跨包名前缀误命中。
        "(c.source_app_package IS NOT NULL AND instr(?, '|' || hex(c.source_app_package) || '|') > 0)"
    }
    else -> {
        arguments.addAll(packages.sorted())
        "c.source_app_package IN (${packages.joinToString(",") { "?" }})"
    }
}

/** 大来源集合的精确身份编码；不保留可与分隔符冲突的原字符，允许合法的空身份。 */
internal fun encodeSearchSourcePackages(/** 已取交集的来源身份集合。 */ packages: Set<String>): String = buildString {
    append('|')
    packages.sorted().forEach { packageName ->
        packageName.toByteArray(Charsets.UTF_8).forEach { byte ->
            /** JVM Byte 有符号，位运算前转换为 0..255 的原始 UTF-8 字节值。 */
            val unsigned = byte.toInt() and 0xff
            append(SOURCE_PACKAGE_HEX_DIGITS[unsigned ushr 4])
            append(SOURCE_PACKAGE_HEX_DIGITS[unsigned and 0xf])
        }
        append('|')
    }
}
