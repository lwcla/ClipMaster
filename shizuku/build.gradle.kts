plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.refine)
    alias(libs.plugins.google.devtools.ksp)                 // 应用KSP插件，用于代码生成
    alias(libs.plugins.google.dagger.hilt.android)                // 应用Hilt插件，用于依赖注入
}

android {
    namespace = "com.cla.clip.shizuku"
    resourcePrefix("shizuku_")
    compileSdk {
        version = release(36)
    }

    defaultConfig {
        minSdk = 24

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        consumerProguardFiles("consumer-rules.pro")
    }

    buildTypes {
        //这段代码的作用是在构建过程中动态生成一个包含 APPLICATION_ID 常量的 BuildConfig 字段。
        //具体来说：
        //buildConfigField(...): 这是 Android Gradle 插件提供的方法，用于向生成的 BuildConfig Java 类中添加自定义字段。
        //"String", "APPLICATION_ID": 它定义了一个名为 APPLICATION_ID 的 String 类型常量。
        //"\"${rootProject.project(":app").androidApp.defaultConfig.applicationId}\"": 这是该字段的值。
        //它首先访问根项目（rootProject）。
        //然后查找名为 :app 的子项目（project(":app")）。
        //通过扩展属性 androidApp（在文件底部定义）获取该 App 模块的 Android 配置。
        //最终读取 :app 模块 defaultConfig 中的 applicationId。
        //总结： 这段代码让当前的 Library 模块在其生成的 BuildConfig 类中能够直接引用 :app 主模块的 Application ID（包名），即使 Library 本身通常没有 Application ID。这通常用于跨进程通信或需要验证调用方身份的场景。
        configureEach {
            buildConfigField(
                "String",
                "APPLICATION_ID",
                "\"${rootProject.project(":app").androidApp.defaultConfig.applicationId}\""
            )

            buildConfigField(
                "int",
                "VERSION_CODE",
                "${rootProject.project(":app").androidApp.defaultConfig.versionCode}"
            )
        }
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
        buildConfig = true
        aidl = true
        compose = true
    }

    testOptions {
        unitTests {
            // 纯 JVM 单元测试不运行真实 Android Framework；允许日志和轻量系统常量走默认值，避免协议解析测试被 Android stub 阻断。
            isReturnDefaultValues = true
        }
    }
}

dependencies {
    implementation(project(":base:general"))
    implementation(project(":base:hidden-api"))

    val composeBom = platform(libs.androidx.compose.bom)
    implementation(composeBom)
    implementation(libs.bundles.compose)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)

    implementation(libs.refine.runtime)
    implementation(libs.hideen.api.bypass)

    // Hilt依赖注入，用于解耦代码，方便管理对象实例
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)

    api(libs.bundles.shizuku)
}

val org.gradle.api.Project.androidApp: com.android.build.gradle.AppExtension
    get() = (this as org.gradle.api.plugins.ExtensionAware).extensions.getByName("android") as com.android.build.gradle.AppExtension
