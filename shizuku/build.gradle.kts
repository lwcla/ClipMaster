plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.refine)
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
    kotlinOptions {
        jvmTarget = "11"
    }

    buildFeatures {
        buildConfig = true
        aidl = true
    }
}

dependencies {
    implementation(project(":base:general"))
    implementation(project(":base:hidden-api"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)

    implementation(libs.refine.runtime)
    implementation(libs.hideen.api.bypass)

    api(libs.shizuku.api)
    api(libs.shizuku.provider)
}

val org.gradle.api.Project.androidApp: com.android.build.gradle.AppExtension
    get() = (this as org.gradle.api.plugins.ExtensionAware).extensions.getByName("android") as com.android.build.gradle.AppExtension