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
/** 正式配置由统一校验脚本读取。 */
val releaseUnits = extra["admobReleaseUnits"] as Map<String, String>
/** 正式 App ID 在 Project 作用域读取，避免构建类型 extra 遮蔽。 */
val releaseAppId = extra["admobReleaseAppId"] as String
android {
    namespace = "com.cla.clip.feature.ad.admob"
    resourcePrefix("ad_admob_")
    compileSdk { version = release(36) }
    defaultConfig {
        minSdk = 24
        consumerProguardFiles("consumer-rules.pro")
    }
    sourceSets.getByName("main") {
        if (includeSdk) {
            java.srcDir("src/sdk/java")
            manifest.srcFile("src/sdk/AndroidManifest.xml")
        }
    }
    buildTypes {
        debug {
            buildConfigField("String", "ADMOB_APP_ID", "ca-app-pub-3940256099942544~3347511713".asConfigLiteral())
            manifestPlaceholders["admobAppId"] = "ca-app-pub-3940256099942544~3347511713"
            placementNames.forEach { placement ->
                buildConfigField("String", placement, "ca-app-pub-3940256099942544/2247696110".asConfigLiteral())
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
}
