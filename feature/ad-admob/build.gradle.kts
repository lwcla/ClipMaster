plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.google.devtools.ksp)
    alias(libs.plugins.google.dagger.hilt.android)
}
apply(from = rootProject.file("gradle/admob-ad-config.gradle.kts"))

/** 把配置转为安全 Java 字符串常量，不输出真实广告 ID。 */
fun String.asConfigLiteral(): String = "\"${replace("\\", "\\\\").replace("\"", "\\\"")}\""
/** 显式模块任务可以验证真实适配器；默认全仓构建保持无 SDK 的空模块。 */
val includeSdk = extra["enableAdmobDebugAdFeature"] as Boolean ||
    extra["enableAdmobReleaseAdFeature"] as Boolean ||
    gradle.startParameter.taskNames.any { it.startsWith(":feature:ad-admob:") || it.startsWith("feature:ad-admob:") }
/** 广告位名称沿用统一构建校验表。 */
val placementNames = extra["admobPlacementNames"] as List<String>
/** debug/release 共用的真实单元由统一脚本校验；测试设备在 AdMob 后台登记。 */
val releaseUnits = extra["admobReleaseUnits"] as Map<String, String>
/** 两个变体共用的真实 App ID，在 Project 作用域读取以避免构建类型 extra 遮蔽。 */
val releaseAppId = extra["admobReleaseAppId"] as String
android {
    namespace = "com.cla.clip.feature.ad.admob"
    resourcePrefix("ad_admob_")
    compileSdk { version = release(36) }
    defaultConfig {
        minSdk = 24
        consumerProguardFiles("consumer-rules.pro")
        // 原生卡片几何与触摸边界在独立测试 APK 中验证，不启动真实广告请求。
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    sourceSets.getByName("main") {
        if (includeSdk) {
            java.srcDir("src/sdk/java")
            manifest.srcFile("src/sdk/AndroidManifest.xml")
        }
    }
    // SDK 专用设备测试与真实实现一起启用，避免无广告根任务编译到 SDK 类型。
    if (includeSdk) sourceSets.getByName("androidTest").java.srcDir("src/sdkAndroidTest/java")
    buildTypes {
        debug {
            // 调试包也使用自己的 App ID，确保 SDK 初始化与 UMP/Manifest 指向同一后台应用。
            buildConfigField("String", "ADMOB_APP_ID", releaseAppId.asConfigLiteral())
            manifestPlaceholders["admobAppId"] = releaseAppId
            // placement 是既有稳定位置名称；逐项映射真实 ID，不以一个示例单元覆盖所有位置。
            placementNames.forEach { placement ->
                buildConfigField("String", placement, releaseUnits.getValue(placement).asConfigLiteral())
            }
        }
        release {
            buildConfigField("String", "ADMOB_APP_ID", releaseAppId.asConfigLiteral())
            manifestPlaceholders["admobAppId"] = releaseAppId
            placementNames.forEach { placement ->
                buildConfigField("String", placement, releaseUnits.getValue(placement).asConfigLiteral())
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11) } }
    buildFeatures { buildConfig = true; compose = true }
    testOptions { unitTests.isReturnDefaultValues = true }
}
dependencies {
    implementation(project(":feature:ad-api"))
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.bundles.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    if (includeSdk) {
        implementation(libs.admob.nextgen)
        implementation(libs.admob.ump)
    }
    testImplementation(libs.junit)
    // 复用版本目录中现有设备测试依赖，不引入新的生产库。
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}
