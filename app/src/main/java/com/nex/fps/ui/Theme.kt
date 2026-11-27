package com.nex.fps.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// Tema gelap bergaya GameHub: latar hampir hitam, kartu abu gelap berpinggir tipis, aksen biru terang.
val GhBg = Color(0xFF08080A)
val GhBar = Color(0xFF0E0E11)
val GhCard = Color(0xFF151518)
val GhCard2 = Color(0xFF1E1E23)
val GhLine = Color(0xFF2A2A31)
val GhText = Color(0xFFF2F2F6)
val GhMuted = Color(0xFF8E8E99)
val GhBlue = Color(0xFF1E88FF)
val GhBlueDeep = Color(0xFF12304F)
val GhCyan = Color(0xFF38D5FF)
val GhGreen = Color(0xFF2EE6A6)
val GhAmber = Color(0xFFFFB84D)
val GhRed = Color(0xFFFF5A5F)

private val Scheme = darkColorScheme(
    primary = GhBlue,
    onPrimary = Color.White,
    primaryContainer = GhBlueDeep,
    onPrimaryContainer = Color.White,
    secondary = GhCyan,
    background = GhBg,
    onBackground = GhText,
    surface = GhCard,
    onSurface = GhText,
    surfaceVariant = GhCard2,
    onSurfaceVariant = GhMuted,
    outline = GhLine,
    outlineVariant = GhLine,
    error = GhRed
)

@Composable
fun NexTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = Scheme, typography = Typography(), content = content)
}
