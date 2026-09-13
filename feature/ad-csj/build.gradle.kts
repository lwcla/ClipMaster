plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.google.devtools.ksp)
    alias(libs.plugins.google.dagger.hilt.android)
}

apply(from = rootProject.file("gradle/feature-flags.gradle.kts"))
apply(from = rootProject.file("gradle/csj-ad-config.gradle.kts"))

/** 把原始字符串转成可安全写入 `buildConfigField` 的 Kotlin 字面量。 */
fun String.asBuildConfigString(): String = "\"${replace("\\", "\\\\").replace("\"", "\\\"")}\""

/** 当前构建是否把穿山甲官方 SDK 依赖编译进 adapter；由任一 buildType 显式开关统一决定。 */
val csjSdkDependencyEnabled: Boolean = extra["csjAdFeatureEnabled"] as Boolean

/** debug/internal 穿山甲 AppId；开关关闭时允许为空，开启时由配置脚本保证有效。 */
val csjDebugAppId: String = extra["csjDebugAppId"] as String

/** release 穿山甲 AppId；开关关闭时允许为空，开启时由配置脚本保证有效。 */
val csjReleaseAppId: String = extra["csjReleaseAppId"] as String

/** debug/internal 详情页信息流/原生广告位 ID；开关关闭时允许为空，开启时由配置脚本保证有效。 */
val csjDebugDetailNativeAdSlotId: String = extra["csjDebugDetailNativeAdSlotId"] as String

/** release 详情页信息流/原生广告位 ID；开关关闭时允许为空，开启时由配置脚本保证有效。 */
val csjReleaseDetailNativeAdSlotId: String = extra["csjReleaseDetailNativeAdSlotId"] as String

/** debug/internal 构建是否使用测试广告位；默认按测试位处理。 */
val csjDebugUseTestAdSlot: Boolean = extra["csjDebugUseTestAdSlot"] as Boolean

/** release 构建是否使用测试广告位；默认 false，正式包应使用正式广告位。 */
val csjReleaseUseTestAdSlot: Boolean = extra["csjReleaseUseTestAdSlot"] as Boolean

/** 用户已同意的广告隐私政策版本必须匹配该值；空值表示暂未启用版本绑定。 */
val csjRequiredPrivacyPolicyVersion: String = extra["csjRequiredPrivacyPolicyVersion"] as String

android {
    namespace = "com.cla.clip.feature.ad.csj"
    resourcePrefix("ad_csj_")
    compileSdk {
        version = release(36)
    }

    defaultConfig {
        minSdk = 24
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        consumerProguardFiles("consumer-rules.pro")

        buildConfigField("String", "CSJ_REQUIRED_PRIVACY_POLICY_VERSION", csjRequiredPrivacyPolicyVersion.asBuildConfigString())
        buildConfigField("boolean", "CSJ_SDK_DEPENDENCY_ENABLED", csjSdkDependencyEnabled.toString())
        buildConfigField("String", "CSJ_SDK_VERSION", libs.versions.csjAds.get().asBuildConfigString())
    }

    buildTypes {
        debug {
            buildConfigField("String", "CSJ_APP_ID", csjDebugAppId.asBuildConfigString())
            buildConfigField("String", "CSJ_DETAIL_NATIVE_AD_SLOT_ID", csjDebugDetailNativeAdSlotId.asBuildConfigString())
            buildConfigField("boolean", "CSJ_USE_TEST_AD_SLOT", csjDebugUseTestAdSlot.toString())
        }

        release {
            isMinifyEnabled = false
            buildConfigField("String", "CSJ_APP_ID", csjReleaseAppId.asBuildConfigString())
            buildConfigField("String", "CSJ_DETAIL_NATIVE_AD_SLOT_ID", csjReleaseDetailNativeAdSlotId.asBuildConfigString())
            buildConfigField("boolean", "CSJ_USE_TEST_AD_SLOT", csjReleaseUseTestAdSlot.toString())
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
        buildConfig = true
        compose = true
    }

    testOptions {
        unitTests {
            // 纯 JVM 单元测试不运行真实 Android Framework；策略类只依赖纯 Kotlin 输入输出。
            isReturnDefaultValues = true
        }
    }
}

dependencies {
    implementation(project(":feature:ad-api"))
    implementation(project(":base:general"))

    /** Compose BOM 保证穿山甲广告容器和宿主 Compose 版本一致。 */
    val composeBom = platform(libs.androidx.compose.bom)
    implementation(composeBom)
    implementation(libs.bundles.compose)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)

    implementation(libs.csj.ads.sdk.pro)

    testImplementation(libs.junit)
}
