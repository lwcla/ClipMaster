package com.cla.clip.master.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CardElevation
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** 浅色固定品牌色板，作为应用内 UI 的默认视觉来源。 */
private val LightColorScheme = lightColorScheme(
    primary = BrandPrimaryLight,
    onPrimary = Color.White,
    primaryContainer = BrandPrimaryContainerLight,
    onPrimaryContainer = Color(0xFF163A74),
    secondary = Color(0xFF56677F),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFDDE5F2),
    onSecondaryContainer = Color(0xFF1A2C43),
    tertiary = Color(0xFF486A89),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFD8E9FF),
    onTertiaryContainer = Color(0xFF0E2D48),
    error = BrandErrorLight,
    onError = Color.White,
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
    background = BrandBackgroundLight,
    onBackground = Color(0xFF18212E),
    surface = BrandSurfaceLight,
    onSurface = Color(0xFF18212E),
    surfaceVariant = BrandSurfaceVariantLight,
    onSurfaceVariant = Color(0xFF4A5668),
    outline = BrandOutlineLight,
    outlineVariant = Color(0xFFD8E0EE),
    scrim = Color(0xFF000000),
)

/** 深色固定品牌色板，保持工具页面在暗色模式下的低眩光和可读性。 */
private val DarkColorScheme = darkColorScheme(
    primary = BrandPrimaryDark,
    onPrimary = Color(0xFF0A2F64),
    primaryContainer = BrandPrimaryContainerDark,
    onPrimaryContainer = Color(0xFFD8E6FF),
    secondary = Color(0xFFBDC7D8),
    onSecondary = Color(0xFF273345),
    secondaryContainer = Color(0xFF39465A),
    onSecondaryContainer = Color(0xFFD9E3F4),
    tertiary = Color(0xFFB9D7FF),
    onTertiary = Color(0xFF183653),
    tertiaryContainer = Color(0xFF2C4C6B),
    onTertiaryContainer = Color(0xFFD8E9FF),
    error = BrandErrorDark,
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
    background = BrandBackgroundDark,
    onBackground = Color(0xFFE2E8F3),
    surface = BrandSurfaceDark,
    onSurface = Color(0xFFE2E8F3),
    surfaceVariant = BrandSurfaceVariantDark,
    onSurfaceVariant = Color(0xFFBFC8D8),
    outline = BrandOutlineDark,
    outlineVariant = Color(0xFF2D3748),
    scrim = Color(0xFF000000),
)

/** 应用统一圆角 token，按容器层级区分，避免页面各自手写 RoundedCornerShape。 */
@Immutable
data class ClipMasterShapes(
    /** 小控件圆角，用于 Chip、输入框内部容器和轻量状态块。 */
    val small: RoundedCornerShape = RoundedCornerShape(8.dp),

    /** 普通卡片圆角，用于列表 item 和入口卡片。 */
    val medium: RoundedCornerShape = RoundedCornerShape(10.dp),

    /** 弹窗、底部 Sheet 和强调卡片圆角。 */
    val large: RoundedCornerShape = RoundedCornerShape(18.dp),
)

/** 应用统一间距 token，覆盖页面边距、列表间距和内容内边距。 */
@Immutable
data class ClipMasterSpacing(
    /** 极小间距，用于图标和短标签之间。 */
    val tiny: Dp = 4.dp,

    /** 小间距，用于同组元素内部。 */
    val small: Dp = 8.dp,

    /** 中等间距，用于卡片内边距和列表 item 间距。 */
    val medium: Dp = 12.dp,

    /** 页面水平边距和区块外边距。 */
    val large: Dp = 16.dp,

    /** 大区块之间的视觉间隔。 */
    val extraLarge: Dp = 24.dp,
)

/** 应用统一动效 token，控制导航和状态反馈的克制节奏。 */
@Immutable
data class ClipMasterMotion(
    /** 短动效时长，适用于轻量状态切换。 */
    val shortMillis: Int = 140,

    /** 普通页面和控件动效时长。 */
    val mediumMillis: Int = 220,
)

/** 应用统一尺寸和视觉 token，通过 CompositionLocal 提供给共享组件。 */
@Immutable
data class ClipMasterDesignTokens(
    /** 共享圆角 token。 */
    val shapes: ClipMasterShapes = ClipMasterShapes(),

    /** 共享间距 token。 */
    val spacing: ClipMasterSpacing = ClipMasterSpacing(),

    /** 共享动效 token。 */
    val motion: ClipMasterMotion = ClipMasterMotion(),
)

/** 主题 token 的 CompositionLocal，默认值用于 Preview 或未包主题的临时渲染。 */
private val LocalClipMasterDesignTokens = staticCompositionLocalOf { ClipMasterDesignTokens() }

/** 当前主题下的 ClipMaster 设计 token 访问入口。 */
object ClipMasterThemeTokens {
    /** 当前主题圆角、间距和动效配置。 */
    val tokens: ClipMasterDesignTokens
        @Composable get() = LocalClipMasterDesignTokens.current
}

/** 兼容旧调用方的默认卡片圆角，后续新代码优先使用 `ClipMasterTheme.tokens.shapes.medium`。 */
val cardCornerShape @Composable get() = ClipMasterThemeTokens.tokens.shapes.medium

/** 卡片默认阴影，集中控制全局主要内容卡片的层次强度。 */
@Composable
fun clipMasterCardElevation(): CardElevation {
    return CardDefaults.cardElevation(defaultElevation = 3.dp)
}

/**
 * 应用主题入口。
 *
 * Android 12 及以上默认启用动态取色；低版本继续回退到应用定义的固定品牌色板。
 */
@Composable
fun ClipMasterTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit,
) {
    /** 当前主题上下文；动态取色需要用它读取系统提供的 tonal palette。 */
    val context = LocalContext.current
    /** 当前设备是否真正支持 Material You 动态取色。 */
    val supportsDynamicColor = dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    /** 当前深浅色模式下实际使用的色板；仅 Android 12+ 走动态取色，否则回退固定主题。 */
    val colorScheme = when {
        supportsDynamicColor && darkTheme -> dynamicDarkColorScheme(context)
        supportsDynamicColor && !darkTheme -> dynamicLightColorScheme(context)
        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }
    /** 当前主题提供给共享组件的设计 token。 */
    val designTokens = ClipMasterDesignTokens()

    androidx.compose.runtime.CompositionLocalProvider(
        LocalClipMasterDesignTokens provides designTokens
    ) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = Typography,
            content = content
        )
    }
}

/**
 * 旧主题函数名兼容入口。
 *
 * 现有 Preview 和页面会逐步迁移到 `ClipMasterTheme`；保留该函数避免一次性重命名带来无关风险。
 */
@Composable
fun ClipMaterTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit
) {
    ClipMasterTheme(
        darkTheme = darkTheme,
        dynamicColor = dynamicColor,
        content = content
    )
}
