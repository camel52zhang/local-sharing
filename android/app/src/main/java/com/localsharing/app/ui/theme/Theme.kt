package com.localsharing.app.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

// —— 品牌色（与桌面 hex 完全一致）——
private val Primary      = Color(0xFF3B6CFF)
private val PrimaryHover = Color(0xFF2A55D8)
private val OnPrimary    = Color.White
private val PrimaryC     = Color(0xFFEAF0FF)   // primaryContainer
private val OnPrimaryC   = Color(0xFF0B2A8A)
private val Secondary    = Color(0xFF5B7CFF)
private val Success      = Color(0xFF1FAA59)
private val Warning      = Color(0xFFE08A00)
private val Error        = Color(0xFFE5484D)
private val ErrorC       = Color(0xFFFDEAEA)
private val OnErrorC     = Color(0xFF7A1216)

// —— 中性灰（冷调，与桌面一致）——
private val Bg     = Color(0xFFF4F6FB)
private val Surf   = Color.White
private val Surf2  = Color(0xFFF0F2F7)
private val Line   = Color(0xFFE6E9F2)
private val Muted  = Color(0xFF6B7488)
private val Ink    = Color(0xFF1C2333)

private val Light = lightColorScheme(
    primary = Primary, onPrimary = OnPrimary, primaryContainer = PrimaryC, onPrimaryContainer = OnPrimaryC,
    secondary = Secondary, onSecondary = OnPrimary,
    tertiary = Success, onTertiary = Color.White,
    error = Error, onError = OnPrimary, errorContainer = ErrorC, onErrorContainer = OnErrorC,
    background = Bg, onBackground = Ink,
    surface = Surf, onSurface = Ink,
    surfaceVariant = Surf2, onSurfaceVariant = Muted,
    outline = Line, outlineVariant = Color(0xFFD8DDE8),
)

private val Dark = darkColorScheme(
    primary = Color(0xFF7C9BFF), onPrimary = Color(0xFF0B1020), primaryContainer = Color(0xFF1E3A8A), onPrimaryContainer = Color(0xFFD7E2FF),
    secondary = Color(0xFF8FA6FF), onSecondary = Color(0xFF0B1020),
    tertiary = Color(0xFF4CC07F), onTertiary = Color(0xFF06231A),
    error = Color(0xFFFF6B70), onError = Color(0xFF3A0608), errorContainer = Color(0xFF4A1417), onErrorContainer = Color(0xFFFFDAD8),
    background = Color(0xFF0B1020), onBackground = Color(0xFFE6E9F2),
    surface = Color(0xFF151B2E), onSurface = Color(0xFFE6E9F2),
    surfaceVariant = Color(0xFF1E2438), onSurfaceVariant = Color(0xFFA6AEC2),
    outline = Color(0xFF2C3650), outlineVariant = Color(0xFF3A4660),
)

// —— 形状：与桌面圆角语言对齐（lg16/md12/sm8/圆形）——
private val Shapes = Shapes(
    extraSmall = androidx.compose.foundation.shape.RoundedCornerShape(8.dp),
    small      = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
    medium     = androidx.compose.foundation.shape.RoundedCornerShape(16.dp),
    large      = androidx.compose.foundation.shape.RoundedCornerShape(20.dp),
    extraLarge = androidx.compose.foundation.shape.RoundedCornerShape(28.dp),
)

// —— 字体：中文系统字体栈（设备默认，无网络依赖）——
private val AppFont = androidx.compose.ui.text.font.FontFamily.Default

private fun LocalSharingTypography() = Typography(
    displaySmall = Typography().displaySmall.copy(fontFamily = AppFont),
    headlineSmall = Typography().headlineSmall.copy(fontFamily = AppFont),
    titleMedium = Typography().titleMedium.copy(fontFamily = AppFont),
    bodyMedium = Typography().bodyMedium.copy(fontFamily = AppFont),
    bodySmall = Typography().bodySmall.copy(fontFamily = AppFont),
    labelLarge = Typography().labelLarge.copy(fontFamily = AppFont),
)

@Composable
fun LocalSharingTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) Dark else Light,
        shapes = Shapes,
        typography = LocalSharingTypography(),
        content = content,
    )
}
