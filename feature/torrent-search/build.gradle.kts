plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.google.devtools.ksp)
    alias(libs.plugins.google.dagger.hilt.android)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.cla.clip.feature.torrent.search"
    resourcePrefix("torrent_search_")
    compileSdk {
        version = release(36)
    }

    defaultConfig {
        minSdk = 24
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
        compose = true
    }
}

/** Room schema 固定输出到源码目录，供版本审查和后续迁移测试使用。 */
ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    // 实现模块只消费 API 契约；与现有 adapter 模块一致使用 implementation，确保 Hilt KSP 解析本模块绑定返回类型。
    implementation(project(":feature:torrent-search-api"))
    // schema v4 通过 base 提供的 BackupFeatureContributor/包 IO 扩展点接入，不反向依赖 app。
    implementation(project(":base:general"))

    // Compose BOM 统一约束本模块 UI 依赖版本，避免各组件自行漂移。
    val composeBom = platform(libs.androidx.compose.bom)
    implementation(composeBom)
    implementation(libs.activity.compose)
    implementation(libs.compose.ui)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.material.icons.extended)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.hilt.navigation.compose)
    // 详情页复用仓库既有 Coil 栈并限制请求解码尺寸；专用 OkHttp 客户端禁用重定向并由 Composition 取消图片请求。
    implementation(libs.io.coil.compose)
    implementation(libs.io.coil.network.okhttp)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    // Provider 协调器依赖协程 Flow、超时和取消传播；网络对象不会在构造时执行请求。
    implementation(libs.kotlinx.coroutines.android)
    // 统一使用直接 OkHttp 客户端，Provider 不自行创建或持有第二套 HTTP 栈。
    implementation(libs.okhttp.client)
    // 可选 Cloudflare DNS-over-HTTPS 复用 OkHttp 官方实现，默认仍使用系统 DNS。
    implementation(libs.okhttp.dnsoverhttps)
    // 内置 Provider 用 Jsoup 纯解析固定 HTML 结构，不执行页面脚本。
    implementation(libs.jsoup)
    // AniLibria Provider 使用 kotlinx JSON DOM 解析合成响应，不依赖反射或类名协议。
    implementation(libs.kotlinx.serialization.json)
    // 独立 Room v1 保存书签、搜索历史和已查看记录，不把可选功能表混入宿主主库。
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)
    // Preferences DataStore 保存 Provider 开关、安全模式与排序稳定 code。
    implementation(libs.androidx.datastore.preferences)

    testImplementation(libs.junit)
    // JVM 单测用虚拟调度器与本地 MockWebServer 固定并发、取消和解压边界，不访问互联网。
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)
    // HTTPS 合成服务使用 OkHttp 官方证书工具，避免为测试向生产网络边界增加 HTTP 逃生开关。
    testImplementation(libs.okhttp.tls)
}
