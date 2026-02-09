plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
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
    }
}

// 在这里添加以下代码块
ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)

    // Room数据库，用于本地持久化存储剪贴板数据
    implementation(libs.room.runtime)
    implementation(libs.room.ktx) // 提供协程支持
    ksp(libs.room.compiler)       // 使用KSP生成代码

    // Hilt依赖注入，用于解耦代码，方便管理对象实例
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
}