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
}

/** Kotlin 声明行识别规则，仅扫描类型、方法和变量声明，不匹配调用链或普通表达式。 */
val torrentSearchDeclarationPattern = Regex(
    """^(?:(?:public|private|internal|protected|override|open|abstract|final|suspend|inline|tailrec|operator|infix|external|const|lateinit|data|sealed|enum|annotation|value|fun|companion)\s+)*(?:class|interface|object|fun|val|var)\b""",
)
/** 简体中文字符识别规则，用于确认注释不是仅有英文占位。 */
val torrentSearchChineseCharacterPattern = Regex("[\\u4e00-\\u9fff]")

/** 注册模块级中文注释扫描，供每个实现批次和 CI 重复执行。 */
tasks.register("checkTorrentSearchChineseComments") {
    /** 将任务归入 verification，便于与单测和编译门禁一起发现。 */
    group = "verification"
    /** 任务说明明确只检查 Kotlin 声明的贴近中文注释。 */
    description = "检查 TorrentSearch Kotlin 类型、方法和变量声明是否有贴近的简体中文注释"

    /** 扫描主代码与测试代码；生成目录不属于人工注释契约。 */
    val torrentSearchKotlinSources = fileTree("src") { include("**/*.kt") }
    inputs.files(torrentSearchKotlinSources)

    /** 执行确定性文本扫描；失败会列出文件和行号供直接修正。 */
    doLast {
        /** 汇总全部缺少贴近中文注释的声明位置。 */
        val violations = mutableListOf<String>()
        torrentSearchKotlinSources.files.sortedBy { sourceFile -> sourceFile.invariantSeparatorsPath }.forEach { sourceFile ->
            /** 当前 Kotlin 文件的全部文本行，用于向上查找 KDoc 或行注释。 */
            val sourceLines = sourceFile.readLines()
            sourceLines.forEachIndexed { lineIndex, sourceLine ->
                /** 去除缩进后的声明候选，避免格式差异影响匹配。 */
                val declarationLine = sourceLine.trim()
                if (!torrentSearchDeclarationPattern.containsMatchIn(declarationLine)) return@forEachIndexed

                /** 从声明上一行开始，允许跨过空行和注解后查找紧邻注释块。 */
                var previousLineIndex = lineIndex - 1
                while (
                    previousLineIndex >= 0 &&
                    (sourceLines[previousLineIndex].isBlank() || sourceLines[previousLineIndex].trimStart().startsWith("@"))
                ) {
                    previousLineIndex -= 1
                }
                /** 收集紧邻 KDoc/行注释文本；普通代码会留下空字符串并触发失败。 */
                val nearbyComment = buildString {
                    while (previousLineIndex >= 0) {
                        /** 当前向上检查的候选注释行。 */
                        val candidateLine = sourceLines[previousLineIndex].trim()
                        if (!(candidateLine.startsWith("//") || candidateLine.startsWith("/*") || candidateLine.startsWith("*") || candidateLine.endsWith("*/"))) break
                        append(candidateLine)
                        previousLineIndex -= 1
                        if (candidateLine.startsWith("/**") || candidateLine.startsWith("/*")) break
                    }
                }
                if (!torrentSearchChineseCharacterPattern.containsMatchIn(nearbyComment)) {
                    violations += "${sourceFile.relativeTo(projectDir).invariantSeparatorsPath}:${lineIndex + 1}: $declarationLine"
                }
            }
        }
        check(violations.isEmpty()) {
            "TorrentSearch 声明缺少贴近的简体中文注释：\n${violations.joinToString("\n")}"
        }
    }
}
