package com.cla.clip.base.general.search

import androidx.sqlite.db.SimpleSQLiteQuery
import androidx.sqlite.db.SupportSQLiteQuery
import com.cla.clip.base.general.entity.ClipVisibilityScope

/** 保守限制 IN 绑定数量，为时间、范围等参数保留余量；更大的集合改用单个编码参数。 */
private const val MAX_INLINE_SOURCE_BINDINGS = 400

/** SQLite hex(TEXT) 使用 UTF-8 大写十六进制；竖线不会出现在编码中，可作为无碰撞身份边界。 */
private const val SOURCE_PACKAGE_HEX_DIGITS = "0123456789ABCDEF"

/** 构建定向查询；列名仅来自封闭枚举，用户值一律绑定，来源空匹配明确生成零结果条件。 */
internal fun buildClipFieldSearchQuery(
    /** 非综合搜索目标；综合模式继续使用既有 FTS 查询。 */ target: ClipSearchTarget,
    /** 已去首尾空白的非空关键词。 */ keyword: String,
    /** 包含开始时刻，null 不限制。 */ startTime: Long?,
    /** 不包含结束时刻，null 不限制。 */ endTime: Long?,
    /** 用户选择来源，空集合不限制。 */ sourceAppPackages: Set<String>,
    /** 来源名命中身份，null 表示未解析，空集合表示确实无命中。 */ matchedSourcePackages: Set<String>?,
    /** 决定普通/折叠过滤与时间轴。 */ visibilityScope: ClipVisibilityScope,
): SupportSQLiteQuery {
    require(target != ClipSearchTarget.All && keyword.isNotBlank())
    /** 折叠搜索的窗口和排序统一使用折叠动作时间。 */
    val folded = visibilityScope == ClipVisibilityScope.FoldedOnly
    /** SQL 标识符只从编译期分支选取，禁止由用户输入拼接。 */
    val timeColumn = if (folded) "c.folded_at" else "c.timestamp"
    /** 标题表主键为 link，左连接不会把同一剪贴复制成多行。 */
    val field = if (target == ClipSearchTarget.LinkTitle) "p.title" else "c.content"
    /** 参数顺序严格跟随占位符追加顺序；不把值直接插入 SQL。 */
    val arguments = mutableListOf<Any>()
    /** 查询只观察剪贴与预览，来源图标/主色表不加入分页依赖。 */
    val sql = StringBuilder("SELECT c.* FROM clips c")
    if (target == ClipSearchTarget.LinkTitle) sql.append(" LEFT JOIN link_previews p ON c.link = p.link")
    sql.append(" WHERE c.deleted_at = 0 AND c.is_folded = ?")
    arguments += if (folded) 1 else 0
    if (startTime != null) { sql.append(" AND $timeColumn >= ?"); arguments += startTime }
    if (endTime != null) { sql.append(" AND $timeColumn < ?"); arguments += endTime }
    /** 定向名称必须明确收到解析结果，避免缺参时误把查询放宽到所有来源。 */
    val effectivePackages = if (target == ClipSearchTarget.SourceName) {
        requireNotNull(matchedSourcePackages)
        if (sourceAppPackages.isEmpty()) matchedSourcePackages else matchedSourcePackages.intersect(sourceAppPackages)
    } else sourceAppPackages
    if (target == ClipSearchTarget.SourceName && effectivePackages.isEmpty()) {
        sql.append(" AND 0")
    } else if (effectivePackages.size > MAX_INLINE_SOURCE_BINDINGS) {
        // 不把一个 IN 拆成多个 IN（仍会超总参数上限）；单一编码集合保留全部身份且不拼入 SQL。
        // null 与空包名不同，必须先排除 null；空字符串身份仍可由相邻分隔符精确匹配。
        sql.append(" AND c.source_app_package IS NOT NULL AND instr(?, '|' || hex(c.source_app_package) || '|') > 0")
        arguments += encodeSearchSourcePackages(effectivePackages)
    } else if (effectivePackages.isNotEmpty()) {
        sql.append(" AND c.source_app_package IN (").append(effectivePackages.joinToString(",") { "?" }).append(")")
        arguments.addAll(effectivePackages.sorted())
    }
    if (target != ClipSearchTarget.SourceName) {
        sql.append(" AND $field LIKE ? ESCAPE '\\'")
        arguments += "%${escapeSearchLikeLiteral(keyword)}%"
    }
    sql.append(" ORDER BY CASE WHEN c.pinned_time > 0 THEN 1 ELSE 0 END DESC,")
    if (target != ClipSearchTarget.SourceName) {
        sql.append(" CASE WHEN $field = ? COLLATE NOCASE THEN 1 ELSE 0 END DESC,")
        arguments += keyword
    }
    if (!folded) sql.append(" c.pinned_time DESC,")
    sql.append(" $timeColumn DESC, c.timestamp DESC, c.id DESC")
    return SimpleSQLiteQuery(sql.toString(), arguments.toTypedArray())
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
