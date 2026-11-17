package com.im.openchat.common.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Density
import com.im.openchat.common.config.AppConfig

private val DarkColorScheme = darkColorScheme(
    primary = Green80,
    onPrimary = Color(0xFF003919),
    primaryContainer = GreenContainerDark,
    onPrimaryContainer = Color(0xFFB5F2C8),
    inversePrimary = Green40,
    secondary = Color(0xFFBACBBE),
    onSecondary = Color(0xFF26352B),
    secondaryContainer = Color(0xFF3C4B40),
    onSecondaryContainer = Color(0xFFD6E8DA),
    tertiary = Color(0xFFF0C26D),
    onTertiary = Color(0xFF452F00),
    tertiaryContainer = Color(0xFF624617),
    onTertiaryContainer = Color(0xFFFFE0A2),
    background = Color(0xFF141619),
    onBackground = Color(0xFFF1F4F2),
    surface = Color(0xFF202327),
    surfaceVariant = Color(0xFF2B3036),
    onSurface = Color(0xFFF1F4F2),
    onSurfaceVariant = Color(0xFFB5BDB7),
    surfaceTint = Green80,
    inverseSurface = Color(0xFFE2E6E3),
    inverseOnSurface = Color(0xFF2C302D),
    surfaceDim = Color(0xFF141619),
    surfaceBright = Color(0xFF363B38),
    surfaceContainerLowest = Color(0xFF101312),
    surfaceContainerLow = Color(0xFF1B1F1D),
    surfaceContainer = Color(0xFF202523),
    surfaceContainerHigh = Color(0xFF292E2B),
    surfaceContainerHighest = Color(0xFF343A36),
    outline = Color(0xFF89968D),
    outlineVariant = Color(0xFF414A44),
)

private val LightColorScheme = lightColorScheme(
    primary = Green40,
    onPrimary = Color.White,
    primaryContainer = GreenContainerLight,
    onPrimaryContainer = Color(0xFF0B5127),
    inversePrimary = Green80,
    secondary = Color(0xFF526457),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFDAE7DE),
    onSecondaryContainer = Color(0xFF26352B),
    tertiary = Color(0xFF89631A),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFFFE0A2),
    onTertiaryContainer = Color(0xFF452F00),
    background = Color(0xFFF7F9F8),
    onBackground = Color(0xFF1C2520),
    surface = Color.White,
    surfaceVariant = Color(0xFFF0F4F1),
    onSurface = Color(0xFF1C2520),
    onSurfaceVariant = Color(0xFF5C6B62),
    surfaceTint = Green40,
    inverseSurface = Color(0xFF2C332F),
    inverseOnSurface = Color(0xFFF0F4F1),
    surfaceDim = Color(0xFFD9DFDA),
    surfaceBright = Color.White,
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFF5F8F6),
    surfaceContainer = Color(0xFFEEF3EF),
    surfaceContainerHigh = Color(0xFFE7EDE8),
    surfaceContainerHighest = Color(0xFFE0E7E2),
    outline = Color(0xFF748379),
    outlineVariant = Color(0xFFDDE5DF),
)

/**
 * 为应用内容提供浅深色主题、共用排版和全局字体缩放，无返回值，仅影响当前界面树。
 *
 * @param darkTheme 是否启用深色主题。
 * @param dynamicColor 是否在 Android 12 及以上使用系统动态配色。
 * @param fontScale 应用字号相对于系统字号的缩放比例，同时覆盖主题和显式 sp 字号。
 * @param content 使用当前主题渲染的界面内容。
 */
@Composable
fun OpenChatTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
// Android 12 及以上支持动态取色。
    dynamicColor: Boolean = false,
    fontScale: Float = AppConfig.DEFAULT_FONT_SCALE,
    content: @Composable () -> Unit
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }

        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }

    val systemDensity = LocalDensity.current
    // 保留系统字体偏好并叠加应用字号；只改变 sp 换算，不缩放 dp 布局和图标。
    val scaledDensity = remember(systemDensity, fontScale) {
        val scale = if (fontScale.isFinite()) {
            fontScale.coerceIn(AppConfig.MIN_FONT_SCALE, AppConfig.MAX_FONT_SCALE)
        } else {
            AppConfig.DEFAULT_FONT_SCALE
        }
        if (scale == AppConfig.DEFAULT_FONT_SCALE) {
            systemDensity
        } else {
            Density(systemDensity.density, systemDensity.fontScale * scale)
        }
    }
    CompositionLocalProvider(LocalDensity provides scaledDensity) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = Typography,
            content = content
        )
    }
}
