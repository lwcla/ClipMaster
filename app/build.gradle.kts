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

/** 把原始字符串转成可安全写入 `buildConfigField` 的 Kotlin 字面量。 */
fun String.asBuildConfigString(): String = "\"${replace("\\", "\\\\").replace("\"", "\\\"")}\""

/** 规范化 APK 文件名片段，只保留文件系统安全字符。 */
fun String.asApkFileNamePart(): String = replace(Regex("[^A-Za-z0-9._-]"), "_")

android {
    namespace = "com.cla.clip.master"
    resourcePrefix("host_")
    compileSdk {
        version = release(36)
    }

    signingConfigs {
        create("release") {
            val keystoreFile = project.rootProject.file("keystore.properties")
            if (keystoreFile.exists()) {
                val properties = Properties()
                properties.load(FileInputStream(keystoreFile))

                storeFile = rootProject.file(properties.getProperty("storeFile"))
                storePassword = properties.getProperty("storePassword")
                keyAlias = properties.getProperty("keyAlias")
                keyPassword = properties.getProperty("keyPassword")
            }
        }
    }

    defaultConfig {
        applicationId = "com.cla.clip.master"
        minSdk = 24
        targetSdk = 36
        versionCode = 24
        versionName = "0.4.3"

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
    }

    buildTypes {
        release {
            isMinifyEnabled = true          // 开启 R8（代码压缩/优化/混淆）
            isShrinkResources = true        // 可选：开启资源压缩（依赖 minify）
            signingConfig = signingConfigs.getByName("release")
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }

        debug {
            isMinifyEnabled = false         // 调试包不混淆，方便调试
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
}

dependencies {
    implementation(project(":base:general"))
    implementation(project(":feature:magnet-api"))
    implementation(project(":shizuku"))

    /** 是否按 Gradle 属性编译进磁力模块；默认关闭，避免宿主强依赖可选特性。 */
    val enableMagnetFeature = providers.gradleProperty("enableMagnetFeature")
        .map(String::toBoolean)
        .getOrElse(false)
    if (enableMagnetFeature) {
        implementation(project(":feature:magnet"))
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
