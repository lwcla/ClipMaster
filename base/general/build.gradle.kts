plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.google.devtools.ksp)                 // 应用KSP插件，用于代码生成
    alias(libs.plugins.google.dagger.hilt.android)                // 应用Hilt插件，用于依赖注入
}

android {
    namespace = "com.cla.clip.base.general"
    resourcePrefix("base_general_")
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
        compose = true
    }
}

// 在这里添加以下代码块
ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    val composeBom = platform(libs.androidx.compose.bom)
    implementation(composeBom)
    implementation(libs.bundles.compose)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)

    // Room数据库，用于本地持久化存储剪贴板数据
    implementation(libs.bundles.room)
    ksp(libs.room.compiler)       // 使用KSP生成代码

    // Hilt依赖注入，用于解耦代码，方便管理对象实例
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)

    // Paging3库，用于高效加载和显示大量数据
    implementation(libs.bundles.paging)
    testImplementation(libs.paging.common)

    // AndroidX Palette KTX，用于从图片中提取主色调，实现动态主题功能
    implementation(libs.androidx.palette.ktx)

    // okhttp
    api(libs.okhttp.client)
}

val org.gradle.api.Project.androidApp: com.android.build.gradle.AppExtension
    get() = (this as org.gradle.api.plugins.ExtensionAware).extensions.getByName("android") as com.android.build.gradle.AppExtension