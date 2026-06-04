plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.google.devtools.ksp)
    alias(libs.plugins.google.dagger.hilt.android)
}

apply(from = rootProject.file("gradle/csj-ad-config.gradle.kts"))
apply(from = rootProject.file("gradle/uniad-ad-config.gradle.kts"))

/** 把原始字符串转成可安全写入 `buildConfigField` 的 Kotlin 字面量。 */
fun String.asBuildConfigString(): String = "\"${replace("\\", "\\\\").replace("\"", "\\\"")}\""

/** 当前构建是否具备任一 buildType 的 uni-ad 参数；用于运行时诊断 SDK 能力是否启用。 */
val uniadSdkDependencyConfigured: Boolean = extra["uniadAdFeatureConfigured"] as Boolean

/** debug/internal DCloud AppId；缺失时 debug adapter 编译通过、运行隐藏广告。 */
val uniadDebugAppId: String = extra["uniadDebugAppId"] as String

/** debug/internal uni-ad 联盟 ID；缺失时 debug adapter 编译通过、运行隐藏广告。 */
val uniadDebugUnionId: String = extra["uniadDebugUnionId"] as String

/** debug/internal 详情页信息流 adpid；缺失时 debug 包隐藏广告。 */
val uniadDebugDetailNativeAdpid: String = extra["uniadDebugDetailNativeAdpid"] as String

/** release DCloud AppId；缺失时 release adapter 编译通过、运行隐藏广告。 */
val uniadReleaseAppId: String = extra["uniadReleaseAppId"] as String

/** release uni-ad 联盟 ID；缺失时 release adapter 编译通过、运行隐藏广告。 */
val uniadReleaseUnionId: String = extra["uniadReleaseUnionId"] as String

/** release 详情页信息流 adpid；缺失时 release 包隐藏广告。 */
val uniadReleaseDetailNativeAdpid: String = extra["uniadReleaseDetailNativeAdpid"] as String

/** debug/internal 构建是否使用测试 adpid；固定 true，避免内部测试污染正式收益。 */
val uniadDebugUseTestAdpid: Boolean = extra["uniadDebugUseTestAdpid"] as Boolean

/** release 构建是否使用测试 adpid；固定 false，正式包应使用正式广告位。 */
val uniadReleaseUseTestAdpid: Boolean = extra["uniadReleaseUseTestAdpid"] as Boolean

/** 用户已同意的广告隐私政策版本必须匹配该值；空值表示暂未启用版本绑定。 */
val uniadRequiredPrivacyPolicyVersion: String = extra["uniadRequiredPrivacyPolicyVersion"] as String

/** uni-ad SDK 固定版本；用于低敏诊断和构建报告。 */
val uniadSdkVersion: String = extra["uniadSdkVersion"] as String

/** uni-ad AAR 固定目录；依赖必须显式列出文件名，禁止 fileTree 泛扫。 */
val uniadArtifactDir: java.io.File = extra["uniadArtifactDir"] as java.io.File

android {
    namespace = "com.cla.clip.feature.ad.uniad"
    resourcePrefix("ad_uniad_")
    compileSdk {
        version = release(36)
    }

    defaultConfig {
        minSdk = 24
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        consumerProguardFiles("consumer-rules.pro")

        buildConfigField("boolean", "UNIAD_SDK_DEPENDENCY_ENABLED", uniadSdkDependencyConfigured.toString())
        buildConfigField("String", "UNIAD_SDK_VERSION", uniadSdkVersion.asBuildConfigString())
        buildConfigField("String", "UNIAD_REQUIRED_PRIVACY_POLICY_VERSION", uniadRequiredPrivacyPolicyVersion.asBuildConfigString())
    }

    buildTypes {
        debug {
            buildConfigField("String", "UNIAD_APP_ID", uniadDebugAppId.asBuildConfigString())
            buildConfigField("String", "UNIAD_UNION_ID", uniadDebugUnionId.asBuildConfigString())
            buildConfigField("String", "UNIAD_DETAIL_NATIVE_ADPID", uniadDebugDetailNativeAdpid.asBuildConfigString())
            buildConfigField("boolean", "UNIAD_USE_TEST_ADPID", uniadDebugUseTestAdpid.toString())
        }

        release {
            isMinifyEnabled = false
            buildConfigField("String", "UNIAD_APP_ID", uniadReleaseAppId.asBuildConfigString())
            buildConfigField("String", "UNIAD_UNION_ID", uniadReleaseUnionId.asBuildConfigString())
            buildConfigField("String", "UNIAD_DETAIL_NATIVE_ADPID", uniadReleaseDetailNativeAdpid.asBuildConfigString())
            buildConfigField("boolean", "UNIAD_USE_TEST_ADPID", uniadReleaseUseTestAdpid.toString())
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
            // 纯 JVM 单元测试只覆盖策略、配置和 facade 规则，不启动真实 Android Framework。
            isReturnDefaultValues = true
        }
    }
}

dependencies {
    implementation(project(":feature:ad-api"))
    implementation(project(":base:general"))

    /** Compose BOM 保证 uni-ad 广告容器和宿主 Compose 版本一致。 */
    val composeBom = platform(libs.androidx.compose.bom)
    implementation(composeBom)
    implementation(libs.bundles.compose)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.recyclerview)
    implementation(libs.glide)
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)

    /** uni-ad 基础广告 SDK；显式列出文件名，避免误带其它广告渠道 AAR。 */
    implementation(files(uniadArtifactDir.resolve("uniad-native-release.aar")))
    /** uni-ad 基础包要求的 GIF AAR；不通过 fileTree 引入。 */
    implementation(files(uniadArtifactDir.resolve("android-gif-drawable-1.2.29.aar")))
    /** 章鱼 uni-ad adapter；v1 只保留支持信息流的章鱼渠道。 */
    implementation(files(uniadArtifactDir.resolve("uniad-zy-release.aar")))
    /** 章鱼广告 SDK；v1 只用于详情页信息流。 */
    implementation(files(uniadArtifactDir.resolve("octopus_ad_sdk_2.5.10.5.aar")))
    /** 泛连广告 SDK；v1 只保留支持信息流的泛连渠道。 */
    implementation(files(uniadArtifactDir.resolve("Funlink_2.8.8_76006310_release.aar")))
    /** 泛连 uni-ad adapter；不引入华夏乐游、Sigmob 或其它不支持/高冲突渠道。 */
    implementation(files(uniadArtifactDir.resolve("Funlink_adapter_uniad_2.8.4_74659082_release.aar")))

    testImplementation(libs.junit)
}
