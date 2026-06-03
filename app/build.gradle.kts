import java.io.FileInputStream
import java.util.Properties
import com.android.build.gradle.api.ApkVariantOutput

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.google.devtools.ksp)                 // 应用KSP插件，用于代码生成
    alias(libs.plugins.google.dagger.hilt.android)                // 应用Hilt插件，用于依赖注入
    alias(libs.plugins.kotlin.serialization) // 新增Kotlin序列化插件，用于数据类的序列化和反序列化
    id("kotlin-parcelize")
}

apply(from = rootProject.file("gradle/csj-ad-config.gradle.kts"))

/** 把原始字符串转成可安全写入 `buildConfigField` 的 Kotlin 字面量。 */
fun String.asBuildConfigString(): String = "\"${replace("\\", "\\\\").replace("\"", "\\\"")}\""

/** 规范化 APK 文件名片段，只保留文件系统安全字符。 */
fun String.asApkFileNamePart(): String = replace(Regex("[^A-Za-z0-9._-]"), "_")

/** 是否把穿山甲广告 adapter 编译进 debug/internal 包；debug 广告 ID 齐全时默认启用。 */
val csjDebugAdFeatureConfigured: Boolean = extra["csjDebugAdFeatureConfigured"] as Boolean

/** 是否把穿山甲广告 adapter 编译进 release 包；release 广告 ID 齐全时默认启用。 */
val csjReleaseAdFeatureConfigured: Boolean = extra["csjReleaseAdFeatureConfigured"] as Boolean

/** debug/internal 构建是否声明使用穿山甲测试广告位；默认 true，避免内部验证污染正式收益。 */
val csjDebugUseTestAdSlot: Boolean = extra["csjDebugUseTestAdSlot"] as Boolean

/** release 构建是否声明使用穿山甲测试广告位；默认 false，正式包应使用正式广告位。 */
val csjReleaseUseTestAdSlot: Boolean = extra["csjReleaseUseTestAdSlot"] as Boolean

android {
    namespace = "com.cla.clip.master"
    resourcePrefix("host_")
    compileSdk {
        version = release(36)
    }

    signingConfigs {
        create("internalDebug") {
            /** 固定 internal/debug keystore 文件；用于让不同电脑的 debug 包签名一致。 */
            val internalDebugKeystoreFile = rootProject.file("debug-internal.keystore")
            storeFile = internalDebugKeystoreFile
            /** 固定 internal/debug keystore 密码；仅用于开发调试包，不作为 release 机密。 */
            storePassword = "clipmaster_debug"
            /** 固定 internal/debug key alias；穿山甲 debug 应用后台绑定该签名指纹。 */
            keyAlias = "clipmaster_debug"
            /** 固定 internal/debug key 密码；仅用于开发调试包。 */
            keyPassword = "clipmaster_debug"
        }

        create("release") {
            /** 正式签名属性文件；release 包只从这里读取发布 keystore，不使用 debug/internal 签名。 */
            val keystoreFile = project.rootProject.file("keystore.properties")
            if (keystoreFile.exists()) {
                /** 正式签名属性集合；只在 Gradle 配置期读取，不打印到构建日志。 */
                val properties = Properties()
                properties.load(FileInputStream(keystoreFile))

                /** 正式发布 keystore 文件；穿山甲 release 应用后台绑定该签名指纹。 */
                storeFile = rootProject.file(properties.getProperty("storeFile"))
                /** 正式发布 keystore 密码；来自本机敏感配置文件。 */
                storePassword = properties.getProperty("storePassword")
                /** 正式发布 key alias；来自本机敏感配置文件。 */
                keyAlias = properties.getProperty("keyAlias")
                /** 正式发布 key 密码；来自本机敏感配置文件。 */
                keyPassword = properties.getProperty("keyPassword")
            }
        }
    }

    defaultConfig {
        applicationId = "com.cla.clip.master"
        minSdk = 24
        targetSdk = 36
        versionCode = 30
        versionName = "0.4.9"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        /** GitHub 发布仓库 owner/repo；默认指向正式 release 仓库。 */
        val appUpdateGithubRepo = providers.gradleProperty("appUpdateGithubRepo")
            .getOrElse("clip-master-2/ClipMaster-Releases")
        /** Gitee 发布仓库 owner/repo；默认指向国内镜像 release 仓库。 */
        val appUpdateGiteeRepo = providers.gradleProperty("appUpdateGiteeRepo")
            .getOrElse("clip-master-2/clip-master-releases")
        buildConfigField(
            "String",
            "APP_UPDATE_GITHUB_MANIFEST_URL",
            providers.gradleProperty("appUpdateGithubManifestUrl")
                .getOrElse("https://github.com/$appUpdateGithubRepo/releases/latest/download/update.json")
                .asBuildConfigString()
        )
        buildConfigField(
            "String",
            "APP_UPDATE_GITEE_RELEASE_API_URL",
            providers.gradleProperty("appUpdateGiteeReleaseApiUrl")
                .getOrElse("https://gitee.com/api/v5/repos/$appUpdateGiteeRepo/releases/latest")
                .asBuildConfigString()
        )
        buildConfigField(
            "String",
            "APP_UPDATE_GITHUB_RELEASE_PAGE_URL",
            providers.gradleProperty("appUpdateGithubReleasePageUrl")
                .getOrElse("https://github.com/$appUpdateGithubRepo/releases")
                .asBuildConfigString()
        )
        buildConfigField(
            "String",
            "APP_UPDATE_GITEE_RELEASE_PAGE_URL",
            providers.gradleProperty("appUpdateGiteeReleasePageUrl")
                .getOrElse("https://gitee.com/$appUpdateGiteeRepo/releases")
                .asBuildConfigString()
        )
        buildConfigField(
            "String",
            "APP_UPDATE_CHANNEL",
            providers.gradleProperty("appUpdateChannel").getOrElse("internal").asBuildConfigString()
        )
        buildConfigField("String", "ADS_CSJ_SOURCE_ID", "csj".asBuildConfigString())
    }

    buildTypes {
        release {
            isMinifyEnabled = true          // 开启 R8（代码压缩/优化/混淆）
            isShrinkResources = true        // 可选：开启资源压缩（依赖 minify）
            signingConfig = signingConfigs.getByName("release")
            buildConfigField("boolean", "ADS_CSJ_ENABLED", csjReleaseAdFeatureConfigured.toString())
            buildConfigField("boolean", "ADS_CSJ_USE_TEST_AD_SLOT", csjReleaseUseTestAdSlot.toString())
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }

        debug {
            isMinifyEnabled = false         // 调试包不混淆，方便调试
            signingConfig = signingConfigs.getByName("internalDebug")
            buildConfigField("boolean", "ADS_CSJ_ENABLED", csjDebugAdFeatureConfigured.toString())
            buildConfigField("boolean", "ADS_CSJ_USE_TEST_AD_SLOT", csjDebugUseTestAdSlot.toString())
//            isShrinkResources = false       // 调试包不压缩资源
        }
    }

    applicationVariants.all {
        outputs.all {
            /** 当前输出对象；需要转成 AGP 旧类型后才能覆写 APK 文件名。 */
            val apkOutput = this as ApkVariantOutput
            /** 参与文件名拼接的版本号片段。 */
            val normalizedVersionName = versionName.asApkFileNamePart()
            /** 非 release 变体追加后缀，避免多产物同名覆盖。 */
            val variantNamePart = if (buildType.name == "release") "" else "-${name.asApkFileNamePart()}"
            apkOutput.outputFileName = "ClipMaster-v$normalizedVersionName$variantNamePart.apk"
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
        buildConfig = true
        compose = true
    }

    testOptions {
        unitTests {
            // 纯 JVM 单元测试不运行真实 Android Framework；允许 Process/Bundle 等轻量平台调用返回默认值，避免协议测试被日志桩阻断。
            isReturnDefaultValues = true
        }
    }
}

dependencies {
    implementation(project(":base:general"))
    implementation(project(":feature:magnet-api"))
    implementation(project(":feature:ad-api"))
    implementation(project(":shizuku"))
    debugImplementation(project(":feature:ad-debug"))

    /** 是否按 Gradle 属性编译进磁力模块；默认关闭，避免宿主强依赖可选特性。 */
    val enableMagnetFeature = providers.gradleProperty("enableMagnetFeature")
        .map(String::toBoolean)
        .getOrElse(false)
    if (enableMagnetFeature) {
        implementation(project(":feature:magnet"))
    }

    /** 是否按 debug/internal 广告参数编译进穿山甲广告模块；默认关闭，避免无配置 debug 包误带国内广告 SDK。 */
    if (csjDebugAdFeatureConfigured) {
        debugImplementation(project(":feature:ad-csj"))
    }

    /** 是否按 release 广告参数编译进穿山甲广告模块；默认关闭，避免海外/默认 release 包误带国内广告 SDK。 */
    if (csjReleaseAdFeatureConfigured) {
        releaseImplementation(project(":feature:ad-csj"))
    }

    val composeBom = platform(libs.androidx.compose.bom)
    implementation(composeBom)
    debugImplementation(composeBom)
    androidTestImplementation(composeBom)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.bundles.compose)
    debugImplementation(libs.bundles.compose.debug)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.compose.ui.test.junit4)

    // Coil3依赖项
    implementation(libs.bundles.io.coil)

    // Hilt依赖注入，用于解耦代码，方便管理对象实例
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)

    // Google ML Kit，用于实现图片OCR功能
//    implementation(libs.mlkit.ocr)

    // Jsoup，用于解析HTML，实现链接预览功能
    implementation(libs.jsoup)

    // AndroidX Palette KTX，用于从图片中提取主色调，实现动态主题功能
    implementation(libs.androidx.palette.ktx)

    // Paging3库，用于高效加载和显示大量数据
    implementation(libs.bundles.paging)
    testImplementation(libs.paging.common)

    // codelocator，用于在调试时定位代码位置，提升调试效率
//    debugImplementation(libs.codelocator.core)

    // work
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.androidx.hilt.work)
    ksp(libs.androidx.hilt.compiler)
}
