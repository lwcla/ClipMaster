plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.google.devtools.ksp)
    alias(libs.plugins.google.dagger.hilt.android)
}

android {
    namespace = "com.cla.clip.feature.torrent.search"
    resourcePrefix("torrent_search_")
    compileSdk {
        version = release(36)
    }

    defaultConfig {
        minSdk = 24
        consumerProguardFiles("consumer-rules.pro")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11)
        }
    }
    buildFeatures {
        compose = true
    }
}

dependencies {
    // 实现模块只消费 API 契约；与现有 adapter 模块一致使用 implementation，确保 Hilt KSP 解析本模块绑定返回类型。
    implementation(project(":feature:torrent-search-api"))

    // Compose BOM 统一约束本模块 UI 依赖版本，避免各组件自行漂移。
    val composeBom = platform(libs.androidx.compose.bom)
    implementation(composeBom)
    implementation(libs.activity.compose)
    implementation(libs.compose.ui)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.material.icons.extended)

    implementation(libs.androidx.core.ktx)
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    // Provider 协调器依赖协程 Flow、超时和取消传播；网络对象不会在构造时执行请求。
    implementation(libs.kotlinx.coroutines.android)
    // 统一使用直接 OkHttp 客户端，Provider 不自行创建或持有第二套 HTTP 栈。
    implementation(libs.okhttp.client)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.serialization.json)
    // JVM 单测用虚拟调度器与本地 MockWebServer 固定并发、取消和解压边界，不访问互联网。
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)
    // HTTPS 合成服务使用 OkHttp 官方证书工具，避免为测试向生产网络边界增加 HTTP 逃生开关。
    testImplementation(libs.okhttp.tls)
}

/** Kotlin 显式声明行识别规则，只匹配类型、方法、构造和 val/var，不匹配普通调用表达式。 */
val torrentSearchDeclarationPattern = Regex(
    """^(?:(?:public|private|internal|protected|override|open|abstract|final|suspend|inline|tailrec|operator|infix|external|const|lateinit|data|sealed|enum|annotation|value|fun|companion)\s+)*(?:class|interface|object(?!\s*:)|fun|constructor|typealias|val|var)\b""",
)
/** 普通多行参数识别规则，以名称和类型冒号为锚点，并排除使用等号的函数调用 named argument。 */
val torrentSearchParameterPattern = Regex(
    """^(?:(?:crossinline|noinline|vararg)\s+)?(?:(?:val|var)\s+)?[A-Za-z_][A-Za-z0-9_]*\s*:\s*[^=].*(?:,|\))$""",
)
/** for 循环头会声明单个或解构变量，必须像其他局部变量一样说明迭代语义。 */
val torrentSearchForVariablePattern = Regex(
    """^for\s*\(\s*(?:\([^)]*\)|[A-Za-z_][A-Za-z0-9_]*)\s+in\b""",
)
/** catch 参数同样属于局部变量声明，必须有贴近的简体中文边界说明。 */
val torrentSearchCatchPattern = Regex("""(?:^|}\s*)catch\s*\(""")
/** 命名或解构 lambda 都会声明回调变量，必须有贴近的简体中文用途说明。 */
val torrentSearchLambdaParameterPattern = Regex(
    """\{\s*(?:\([^)]*\)|[A-Za-z_][A-Za-z0-9_]*(?:\s*,\s*[A-Za-z_][A-Za-z0-9_]*)*)\s*->""",
)
/** 简体中文字符识别规则，用于确认注释不是仅有英文占位。 */
val torrentSearchChineseCharacterPattern = Regex("[\\u4e00-\\u9fff]")

/** 扫描一组 Kotlin 文本行，返回缺少贴近中文注释的声明、参数、循环、catch 与回调。 */
fun collectTorrentSearchCommentViolations(sourceLines: List<String>, sourceLabel: String): List<String> {
    /** 当前文本中的全部违规位置。 */
    val violations = mutableListOf<String>()
    /** 逐行检查可以保留精确行号；lineIndex 是零基下标，sourceLine 是未裁剪原文。 */
    sourceLines.forEachIndexed { lineIndex, sourceLine ->
        /** 去除缩进后的候选行，避免格式差异影响匹配。 */
        val candidateLine = sourceLine.trim()
        // 构建脚本中的合成 fixture 以字符串字面量保存；其内容只由下方独立 fixture 调用验证，不能当作脚本声明重复扫描。
        if (candidateLine.startsWith("\"")) return@forEachIndexed
        /** 类型/方法/变量、普通参数、循环变量、catch 变量和 lambda 参数都属于强制覆盖。 */
        val requiresComment = torrentSearchDeclarationPattern.containsMatchIn(candidateLine) ||
            torrentSearchParameterPattern.containsMatchIn(candidateLine) ||
            torrentSearchForVariablePattern.containsMatchIn(candidateLine) ||
            torrentSearchCatchPattern.containsMatchIn(candidateLine) ||
            torrentSearchLambdaParameterPattern.containsMatchIn(candidateLine)
        if (!requiresComment) return@forEachIndexed

        /** 从候选上一行开始，允许跨过空行和注解查找紧邻注释。 */
        var previousLineIndex = lineIndex - 1
        while (
            previousLineIndex >= 0 &&
            (sourceLines[previousLineIndex].isBlank() || sourceLines[previousLineIndex].trimStart().startsWith("@"))
        ) {
            previousLineIndex -= 1
        }
        /** 收集紧邻 KDoc/行注释，普通代码会留下空字符串并触发违规。 */
        val nearbyComment = buildString {
            while (previousLineIndex >= 0) {
                /** 当前向上检查的候选注释行。 */
                val commentLine = sourceLines[previousLineIndex].trim()
                if (!(commentLine.startsWith("//") || commentLine.startsWith("/*") || commentLine.startsWith("*") || commentLine.endsWith("*/"))) break
                append(commentLine)
                previousLineIndex -= 1
                if (commentLine.startsWith("/**") || commentLine.startsWith("/*")) break
            }
        }
        if (!torrentSearchChineseCharacterPattern.containsMatchIn(nearbyComment)) {
            violations += "$sourceLabel:${lineIndex + 1}: $candidateLine"
        }
    }
    return violations
}

/** 注册模块级中文注释扫描，供每个实现批次和 CI 重复执行。 */
tasks.register("checkTorrentSearchChineseComments") {
    /** 将任务归入 verification，便于与单测和编译门禁一起发现。 */
    group = "verification"
    /** 任务说明明确只检查 Kotlin 声明的贴近中文注释。 */
    description = "检查 TorrentSearch Kotlin 类型、方法和变量声明是否有贴近的简体中文注释"

    /** 扫描主代码与测试代码；生成目录不属于人工注释契约。 */
    val torrentSearchKotlinSources = fileTree("src") { include("**/*.kt") }
    /** 将模块构建脚本纳入同一门禁，避免扫描器自身新增声明或局部变量却绕过规则。 */
    val torrentSearchCommentSources = files(
        torrentSearchKotlinSources,
        layout.projectDirectory.file("build.gradle.kts"),
    )
    inputs.files(torrentSearchCommentSources)

    /** 执行确定性文本扫描；失败会列出文件和行号供直接修正。 */
    doLast {
        /** 汇总全部缺少贴近中文注释的声明位置。 */
        val violations = mutableListOf<String>()
        /** sourceFile 代表当前被扫描的 Kotlin 源码或模块构建脚本，排序保证诊断稳定。 */
        torrentSearchCommentSources.files.sortedBy { sourceFile -> sourceFile.invariantSeparatorsPath }.forEach { sourceFile ->
            /** 当前 Kotlin 文件的全部文本行，用于声明、回调和局部变量扫描。 */
            val sourceLines = sourceFile.readLines()
            /** 相对路径作为可行动诊断标签，不暴露工作机绝对路径。 */
            val sourceLabel = sourceFile.relativeTo(projectDir).invariantSeparatorsPath
            violations += collectTorrentSearchCommentViolations(sourceLines, sourceLabel)
        }
        /** 无注释合成样本逐类锁定类型、方法、变量、普通参数、循环变量、异常和 lambda 声明。 */
        val invalidScannerFixture = listOf(
            "class FixtureType",
            "fun fixtureMethod() = Unit",
            "val immutableValue = 1",
            "var mutableValue = 2",
            "    query: String,",
            "for (index in indices) {",
            "} catch (failure: Throwable) {",
            "items.map { item -> item }",
            "items.zip(values) { name, value -> name to value }",
            "items.forEach { (name, value) -> Unit }",
        )
        /** 十类遗漏都必须被扫描器独立识别，避免某一种 Kotlin 写法绕过门禁。 */
        val invalidFixtureViolations = collectTorrentSearchCommentViolations(invalidScannerFixture, "invalid-fixture")
        check(invalidFixtureViolations.size == 10) { "中文注释扫描器未覆盖全部合成遗漏：$invalidFixtureViolations" }
        /** 带简体中文注释的声明，以及调用参数、when 标签和继承语法，都不得被误报。 */
        val validScannerFixture = listOf(
            "/** 类型用途。 */", "class FixtureType",
            "/** 方法用途。 */", "fun fixtureMethod() = Unit",
            "/** 不可变值用途。 */", "val immutableValue = 1",
            "/** 可变值用途。 */", "var mutableValue = 2",
            "/** 查询参数用途。 */", "    query: String,",
            "// 循环下标用途。", "for (index in indices) {",
            "// 异常边界。", "} catch (failure: Throwable) {",
            "// 单参数回调用途。", "items.map { item -> item }",
            "// name 与 value 回调用途。", "items.zip(values) { name, value -> name to value }",
            "// name 与 value 的用途。", "items.forEach { (name, value) -> Unit }",
            "submit(query = \"synthetic\")",
            "when (state) {",
            "    READY -> Unit",
            "}",
            ") : SearchProvider",
            "object : SearchProvider {}",
        )
        /** 合法合成样本不得产生误报。 */
        val validFixtureViolations = collectTorrentSearchCommentViolations(validScannerFixture, "valid-fixture")
        check(validFixtureViolations.isEmpty()) { "中文注释扫描器误报合法合成样本：$validFixtureViolations" }
        check(violations.isEmpty()) {
            "TorrentSearch 声明缺少贴近的简体中文注释：\n${violations.joinToString("\n")}"
        }
    }
}
