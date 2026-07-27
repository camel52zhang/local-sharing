package com.localsharing.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.runtime.Composable

private val Primary = Color(0xFF3B6CFF)

private val Light = lightColorScheme(
    primary = Primary,
    onPrimary = Color.White,
    secondary = Color(0xFF5B7CFF),
    background = Color(0xFFF4F6FB),
    surface = Color.White,
    onBackground = Color(0xFF1C2333),
    onSurface = Color(0xFF1C2333),
)

private val Dark = darkColorScheme(
    primary = Color(0xFF7C9BFF),
    onPrimary = Color(0xFF0B1020),
    secondary = Color(0xFF5B7CFF),
    background = Color(0xFF0B1020),
    surface = Color(0xFF151B2E),
    onBackground = Color(0xFFE6E9F2),
    onSurface = Color(0xFFE6E9F2),
)

@Composable
fun LocalSharingTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) Dark else Light,
        content = content,
    )
}
