package com.cla.clip.base.general.config

/**
 * 剪贴来源 App 过滤规则。
 *
 * 该对象只处理包名级纯规则，不读取系统安装列表、不访问数据库，也不理解 App 名称，确保保存链路、设置页、详情页和备份恢复使用同一套边界。
 */
object ClipSourceBlockRules {

    /** 单个来源包名最大长度；超长输入大概率不是正常 Android 包名，拒绝保存避免配置膨胀。 */
    const val MAX_PACKAGE_NAME_LENGTH = 200

    /** 过滤名单最多保存的包名数量；避免设置、备份和 UI 多选长期无界增长。 */
    const val MAX_PACKAGE_COUNT = 500

    /** 明显不适合出现在包名里的危险字符；不过度限制点号、横线、下划线和厂商特殊命名。 */
    private val unsafePackageNameChars = setOf('/', '\\', ':', ';', ',', '|', '<', '>', '"', '\'', '`')

    /**
     * 规范化一组已保存来源包名。
     *
     * 只做 trim、去空、危险字符过滤、长度过滤、精确去重、排序和数量裁剪；不做大小写归一、前缀匹配或 App 名称匹配。
     */
    fun normalizePackageSet(packages: Iterable<String?>): Set<String> {
        /** 规范化后的候选包名；LinkedHashSet 保留排序后的稳定顺序，方便 UI 和备份输出一致。 */
        val normalizedPackages = packages
            .mapNotNull { packageName -> normalizeSinglePackage(packageName) }
            .distinct()
            .sorted()
            .take(MAX_PACKAGE_COUNT)
            .toCollection(LinkedHashSet())
        return normalizedPackages
    }

    /**
     * 判断某次剪贴来源是否命中过滤名单。
     *
     * 空来源或不可信来源永远不匹配；命中只基于完整包名精确比较，不把 App 名、前缀或包含关系纳入规则。
     */
    fun isSourceBlocked(sourcePackageName: String?, blockedPackages: Set<String>): Boolean {
        /** 规范化后的来源包名；为空表示本次系统或 Shizuku 没有给出稳定来源。 */
        val normalizedSourcePackage = normalizeSinglePackage(sourcePackageName) ?: return false
        return normalizedSourcePackage in blockedPackages
    }

    /**
     * 校验用户手动输入的包名。
     *
     * @param packageName 用户输入的包名文本。
     * @param selfPackageName 当前应用自身包名；拒绝加入自身，避免把本应用内部复制误屏蔽。
     * @param currentPackageCount 当前已保存数量；达到上限时拒绝继续添加。
     */
    fun validateManualPackage(
        packageName: String,
        selfPackageName: String,
        currentPackageCount: Int,
    ): ManualPackageValidationResult {
        /** 去掉首尾空白后的用户输入；中间空白仍视为危险字符。 */
        val trimmedPackageName = packageName.trim()
        if (trimmedPackageName.isEmpty()) {
            return ManualPackageValidationResult.Blank
        }
        if (trimmedPackageName.length > MAX_PACKAGE_NAME_LENGTH) {
            return ManualPackageValidationResult.TooLong
        }
        if (!hasSafeCharacters(trimmedPackageName)) {
            return ManualPackageValidationResult.UnsafeCharacters
        }
        if (trimmedPackageName == selfPackageName.trim()) {
            return ManualPackageValidationResult.SelfPackage
        }
        if (currentPackageCount >= MAX_PACKAGE_COUNT) {
            return ManualPackageValidationResult.TooManyPackages
        }
        return ManualPackageValidationResult.Valid(trimmedPackageName)
    }

    /**
     * 规范化单个包名。
     *
     * 返回 null 表示该包名不应参与保存、匹配或备份；大小写保持原样，避免把不同字面值误合并。
     */
    fun normalizeSinglePackage(packageName: String?): String? {
        /** 去掉首尾空白后的包名；空白来源不参与过滤匹配。 */
        val trimmedPackageName = packageName?.trim().orEmpty()
        if (trimmedPackageName.isEmpty()) {
            return null
        }
        if (trimmedPackageName.length > MAX_PACKAGE_NAME_LENGTH) {
            return null
        }
        if (!hasSafeCharacters(trimmedPackageName)) {
            return null
        }
        return trimmedPackageName
    }

    /** 判断包名字符是否明显安全；控制字符、空白和少数分隔/注入字符都会被拒绝。 */
    private fun hasSafeCharacters(packageName: String): Boolean {
        return packageName.none { char ->
            /** 当前字符是否为空白、控制字符或明显分隔符。 */
            val unsafe = char.isWhitespace() || char.isISOControl() || char in unsafePackageNameChars
            unsafe
        }
    }
}

/**
 * 手动包名添加校验结果。
 *
 * UI 根据不同结果展示短提示；成功结果携带 trim 后的包名，调用方不需要再次处理空白。
 */
sealed interface ManualPackageValidationResult {
    /** 输入合法，可以加入过滤名单。 */
    data class Valid(
        /** 已 trim 的包名，保持用户输入大小写。 */
        val packageName: String,
    ) : ManualPackageValidationResult

    /** 输入为空或只有空白。 */
    data object Blank : ManualPackageValidationResult

    /** 输入等于当前应用自身包名。 */
    data object SelfPackage : ManualPackageValidationResult

    /** 输入超过单个包名长度上限。 */
    data object TooLong : ManualPackageValidationResult

    /** 输入包含空白、控制字符或明显危险分隔符。 */
    data object UnsafeCharacters : ManualPackageValidationResult

    /** 当前过滤名单已经达到数量上限。 */
    data object TooManyPackages : ManualPackageValidationResult
}
