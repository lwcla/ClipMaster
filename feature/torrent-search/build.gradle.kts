plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.google.devtools.ksp)
    alias(libs.plugins.google.dagger.hilt.android)
    alias(libs.plugins.kotlin.serialization)
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

/** Room schema 固定输出到源码目录，供版本审查和后续迁移测试使用。 */
ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    // 实现模块只消费 API 契约；与现有 adapter 模块一致使用 implementation，确保 Hilt KSP 解析本模块绑定返回类型。
    implementation(project(":feature:torrent-search-api"))
    // schema v4 通过 base 提供的 BackupFeatureContributor/包 IO 扩展点接入，不反向依赖 app。
    implementation(project(":base:general"))

    // Compose BOM 统一约束本模块 UI 依赖版本，避免各组件自行漂移。
    val composeBom = platform(libs.androidx.compose.bom)
    implementation(composeBom)
    implementation(libs.activity.compose)
    implementation(libs.compose.ui)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.material.icons.extended)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.hilt.navigation.compose)
    // 详情页复用仓库既有 Coil 栈并限制请求解码尺寸；专用 OkHttp 客户端禁用重定向并由 Composition 取消图片请求。
    implementation(libs.io.coil.compose)
    implementation(libs.io.coil.network.okhttp)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    // Provider 协调器依赖协程 Flow、超时和取消传播；网络对象不会在构造时执行请求。
    implementation(libs.kotlinx.coroutines.android)
    // 统一使用直接 OkHttp 客户端，Provider 不自行创建或持有第二套 HTTP 栈。
    implementation(libs.okhttp.client)
    // 可选 Cloudflare DNS-over-HTTPS 复用 OkHttp 官方实现，默认仍使用系统 DNS。
    implementation(libs.okhttp.dnsoverhttps)
    // 内置 Provider 用 Jsoup 纯解析固定 HTML 结构，不执行页面脚本。
    implementation(libs.jsoup)
    // AniLibria Provider 使用 kotlinx JSON DOM 解析合成响应，不依赖反射或类名协议。
    implementation(libs.kotlinx.serialization.json)
    // 独立 Room v1 保存书签、搜索历史和已查看记录，不把可选功能表混入宿主主库。
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)
    // Preferences DataStore 保存 Provider 开关、安全模式与排序稳定 code。
    implementation(libs.androidx.datastore.preferences)

    testImplementation(libs.junit)
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

/** 未注释 Kotlin 样本文件，独立于构建脚本以免 fixture 字符串形成扫描绕过。 */
val torrentSearchInvalidCommentFixture = layout.projectDirectory.file(
    "src/test/resources/comment-scan/invalid-kotlin.txt",
)
/** 已正确注释及合法非声明样本文件，用于锁定扫描器的误报边界。 */
val torrentSearchValidCommentFixture = layout.projectDirectory.file(
    "src/test/resources/comment-scan/valid-kotlin.txt",
)

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
    inputs.file(torrentSearchInvalidCommentFixture)
    inputs.file(torrentSearchValidCommentFixture)

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
        /** 无注释合成样本独立锁定类型、方法、构造、变量、参数、循环、异常和 lambda 声明。 */
        val invalidScannerFixture = torrentSearchInvalidCommentFixture.asFile.readLines()
        /** 十二类遗漏都必须被扫描器识别，包括字符串 receiver 的命名 lambda，避免字符串行绕过门禁。 */
        val invalidFixtureViolations = collectTorrentSearchCommentViolations(invalidScannerFixture, "invalid-fixture")
        check(invalidFixtureViolations.size == 12) { "中文注释扫描器未覆盖全部合成遗漏：$invalidFixtureViolations" }
        /** 带简体中文注释的声明和合法非声明样本由独立文件提供，避免扫描器测试污染自身源码。 */
        val validScannerFixture = torrentSearchValidCommentFixture.asFile.readLines()
        /** 合法合成样本不得产生误报。 */
        val validFixtureViolations = collectTorrentSearchCommentViolations(validScannerFixture, "valid-fixture")
        check(validFixtureViolations.isEmpty()) { "中文注释扫描器误报合法合成样本：$validFixtureViolations" }
        check(violations.isEmpty()) {
            "TorrentSearch 声明缺少贴近的简体中文注释：\n${violations.joinToString("\n")}"
        }
    }
}

/** 生产日志中的高风险原始字段插值；门禁只检查真实日志调用参数，不扫描普通网络构造代码。 */
val torrentSearchSensitiveLogInterpolationPattern = Regex(
    "\\$(?:\\{)?(?:query|url|uri|magnet|cookie|apiKey|password|username|host|header|body|response|fileName)\\b|\\.message\\b|stackTraceToString",
    RegexOption.IGNORE_CASE,
)
/** fixture 中不可提交的凭据或私钥模式；中性合成 URL、hash 和 magnet 可用于协议测试。 */
val torrentSearchSensitiveFixturePatterns = listOf(
    Regex("Authorization\\s*[:=]\\s*(?:Bearer|Basic)", RegexOption.IGNORE_CASE),
    Regex("-----BEGIN [A-Z ]*PRIVATE KEY-----"),
    Regex("AKIA[0-9A-Z]{16}"),
    Regex("(?:api[_-]?key|access[_-]?token|password|passwd|secret)\\s*[=:]\\s*[\\\"'][^\\\"']{8,}", RegexOption.IGNORE_CASE),
)
/** values XML 的资源声明名称，用于验证所有可合并资源都保持 feature 前缀。 */
val torrentSearchValueResourceNamePattern = Regex("""\bname\s*=\s*"([^"]+)"""")
/** 文件型资源和 values 声明共同使用的稳定前缀。 */
val torrentSearchResourcePrefix = "torrent_search_"

/** 注册发布静态门禁：日志、fixture、资源、Manifest、上游许可与依赖许可一次检查。 */
tasks.register("checkTorrentSearchReleaseSafety") {
    /** 发布门禁属于 verification 任务组。 */
    group = "verification"
    /** 描述明确该任务不联网、不构建 APK，只检查可重复的仓库静态边界。 */
    description = "检查 TorrentSearch 敏感日志、fixture、资源、Manifest 和许可证边界"
    dependsOn("checkTorrentSearchChineseComments")

    /** 生产 Kotlin、测试资源、Android 资源和发布说明都是门禁输入。 */
    inputs.files(
        fileTree("src/main/java") { include("**/*.kt") },
        fileTree("src/test/resources") { include("**/*") },
        fileTree("src/main/res") { include("**/*") },
        layout.projectDirectory.file("src/main/AndroidManifest.xml"),
        layout.projectDirectory.file("LICENSE"),
        layout.projectDirectory.file("NOTICE.md"),
        layout.projectDirectory.file("THIRD_PARTY_DEPENDENCIES.md"),
    )

    /** 执行静态扫描并一次性汇总全部可行动违规。 */
    doLast {
        /** violations 汇总所有发布阻断项，避免逐次修复才能看到下一项。 */
        val violations = mutableListOf<String>()
        /** mainSourceFiles 是可能产生运行时日志的生产 Kotlin 文件。 */
        val mainSourceFiles = fileTree("src/main/java") { include("**/*.kt") }.files.sortedBy { /** sourceFile 是待排序的生产源码。 */ sourceFile -> sourceFile.invariantSeparatorsPath }
        /** sourceFile 是当前接受敏感日志调用扫描的生产源码。 */
        mainSourceFiles.forEach { sourceFile ->
            /** sourceLines 保留行号，便于门禁输出准确位置。 */
            val sourceLines = sourceFile.readLines()
            /** lineIndex 与 sourceLine 分别是零基行号和原始代码行。 */
            sourceLines.forEachIndexed { lineIndex, sourceLine ->
                /** trimmedLine 去除缩进后识别 Android Log 或结构化 logger 调用入口。 */
                val trimmedLine = sourceLine.trim()
                if (!(trimmedLine.contains("Log.") || trimmedLine.contains("logger.log("))) return@forEachIndexed
                /** callLines 收集当前日志调用到右括号闭合的有限代码块。 */
                val callLines = mutableListOf<String>()
                /** cursor 是当前向下读取日志调用的源码行号。 */
                var cursor = lineIndex
                /** parenthesisDepth 用简单括号计数覆盖现有结构化多行日志调用。 */
                var parenthesisDepth = 0
                do {
                    /** callLine 是当前加入日志调用检查的代码行。 */
                    val callLine = sourceLines[cursor]
                    callLines += callLine
                    parenthesisDepth += callLine.count { /** character 是待统计的括号字符。 */ character -> character == '(' }
                    parenthesisDepth -= callLine.count { /** character 是待统计的括号字符。 */ character -> character == ')' }
                    cursor += 1
                } while (parenthesisDepth > 0 && cursor < sourceLines.size && callLines.size < 40)
                /** callText 是不含注释语义判断的日志调用源码块。 */
                val callText = callLines.joinToString("\n")
                if (torrentSearchSensitiveLogInterpolationPattern.containsMatchIn(callText)) {
                    /** sourceLabel 使用模块相对路径，避免输出工作机绝对路径。 */
                    val sourceLabel = sourceFile.relativeTo(projectDir).invariantSeparatorsPath
                    violations += "$sourceLabel:${lineIndex + 1}: 日志调用包含高风险原始字段或异常 message"
                }
            }
        }

        /** fixtureFiles 是需要检查凭据泄漏的文本测试资源；固定快照和中性协议样本都在范围内。 */
        val fixtureFiles = fileTree("src/test/resources") { include("**/*") }.files.sortedBy { /** fixtureFile 是待排序的测试资源。 */ fixtureFile -> fixtureFile.invariantSeparatorsPath }
        /** fixtureFile 是当前接受凭据模式检查的测试资源。 */
        fixtureFiles.forEach { fixtureFile ->
            /** fixtureText 使用 UTF-8 文本读取；本模块 fixture 只允许 JSON、HTML、XML 和 txt。 */
            val fixtureText = fixtureFile.readText(Charsets.UTF_8)
            if (torrentSearchSensitiveFixturePatterns.any { /** pattern 是当前凭据或私钥检测规则。 */ pattern -> pattern.containsMatchIn(fixtureText) }) {
                /** fixtureLabel 使用模块相对路径形成低敏诊断。 */
                val fixtureLabel = fixtureFile.relativeTo(projectDir).invariantSeparatorsPath
                violations += "$fixtureLabel: fixture 命中凭据、授权头或私钥模式"
            }
        }

        /** resourceFiles 是所有会参与 Android 资源合并的 feature 文件。 */
        val resourceFiles = fileTree("src/main/res") { include("**/*") }.files.sortedBy { /** resourceFile 是待排序的 Android 资源。 */ resourceFile -> resourceFile.invariantSeparatorsPath }
        /** resourceFile 是当前接受文件名或 values 声明前缀检查的资源。 */
        resourceFiles.forEach { resourceFile ->
            /** relativePath 用于区分 values 声明与文件型资源。 */
            val relativePath = resourceFile.relativeTo(file("src/main/res")).invariantSeparatorsPath
            /** resourceDirectory 是资源文件直接父目录，例如 values-zh-rCN 或 drawable。 */
            val resourceDirectory = resourceFile.parentFile.name
            if (resourceDirectory.startsWith("values")) {
                /** resourceText 是当前 values XML，用于抽取声明名称。 */
                val resourceText = resourceFile.readText(Charsets.UTF_8)
                /** resourceName 是 XML 中每一个 name 属性；android: 属性属于平台而非模块资源。 */
                torrentSearchValueResourceNamePattern.findAll(resourceText).map { /** match 是单个 name 属性匹配。 */ match -> match.groupValues[1] }.forEach { resourceName ->
                    if (!resourceName.contains(':') && !resourceName.startsWith(torrentSearchResourcePrefix)) {
                        violations += "$relativePath: 资源声明缺少 $torrentSearchResourcePrefix 前缀：$resourceName"
                    }
                }
            } else if (resourceFile.name != "resources.properties" && !resourceFile.nameWithoutExtension.startsWith(torrentSearchResourcePrefix)) {
                violations += "$relativePath: 文件型资源缺少 $torrentSearchResourcePrefix 前缀"
            }
        }

        /** manifestFile 是 feature 自有 Manifest，宿主合并结果另在双路径构建后检查。 */
        val manifestFile = layout.projectDirectory.file("src/main/AndroidManifest.xml").asFile
        /** manifestText 用确定性字符串规则阻止 feature 接管宿主组件和系统备份配置。 */
        val manifestText = manifestFile.readText(Charsets.UTF_8)
        /** forbiddenManifestFragments 是 feature Manifest 不应出现的越界声明。 */
        val forbiddenManifestFragments = listOf(
            "<uses-permission",
            "<provider",
            "<service",
            "<receiver",
            "android:allowBackup",
            "android:fullBackupContent",
            "android:dataExtractionRules",
            "android:sharedUserId",
            "tools:replace",
        )
        /** fragment 是当前检查的 Manifest 越界声明片段。 */
        forbiddenManifestFragments.forEach { fragment ->
            if (manifestText.contains(fragment)) violations += "src/main/AndroidManifest.xml: 禁止声明 $fragment"
        }

        /** requiredLicenseFiles 是上游许可证、署名和依赖清单三个发布必需文件。 */
        val requiredLicenseFiles = listOf("LICENSE", "NOTICE.md", "THIRD_PARTY_DEPENDENCIES.md")
        /** licenseFileName 是当前需要存在且非空的发布许可文件名。 */
        requiredLicenseFiles.forEach { licenseFileName ->
            /** licenseFile 是模块根目录下的当前许可文件。 */
            val licenseFile = layout.projectDirectory.file(licenseFileName).asFile
            if (!licenseFile.isFile || licenseFile.length() == 0L) violations += "$licenseFileName: 发布许可文件缺失或为空"
        }
        /** dependencyInventory 是人工维护的直接依赖与许可证汇总文本。 */
        val dependencyInventory = layout.projectDirectory.file("THIRD_PARTY_DEPENDENCIES.md").asFile.readText(Charsets.UTF_8)
        /** requiredDependencyNames 覆盖当前模块全部外部运行时与测试依赖族。 */
        val requiredDependencyNames = listOf(
            "AndroidX",
            "Hilt",
            "Room",
            "DataStore",
            "JUnit",
            "kotlinx-serialization",
            "kotlinx-coroutines",
            "OkHttp",
            "okhttp-dnsoverhttps",
            "Jsoup",
            "Coil",
            "MockWebServer",
            "okhttp-tls",
        )
        /** dependencyName 是必须在人工许可清单中出现的依赖族名称。 */
        requiredDependencyNames.forEach { dependencyName ->
            if (!dependencyInventory.contains(dependencyName, ignoreCase = true)) {
                violations += "THIRD_PARTY_DEPENDENCIES.md: 缺少依赖许可条目 $dependencyName"
            }
        }

        check(violations.isEmpty()) {
            "TorrentSearch 发布静态门禁失败：\n${violations.joinToString("\n")}"
        }
    }
}
