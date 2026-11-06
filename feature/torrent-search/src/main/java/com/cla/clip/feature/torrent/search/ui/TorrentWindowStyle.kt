package com.cla.clip.feature.torrent.search.ui

import android.graphics.Color
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge

/** 功能当前使用固定浅色 MaterialTheme；系统图标必须匹配页面，而不能跟随设备夜间模式。 */
internal fun ComponentActivity.enableTorrentLightEdgeToEdge() {
    // legacyNavigationScrim 沿用 AndroidX 默认暗遮罩，供 API 24/25 无法显示深色导航图标时保证对比度。
    val legacyNavigationScrim = Color.argb(0x80, 0x1b, 0x1b, 0x1b)
    enableEdgeToEdge(
        statusBarStyle = SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT),
        navigationBarStyle = SystemBarStyle.light(Color.TRANSPARENT, legacyNavigationScrim),
    )
}
