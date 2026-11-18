package com.sapphirebank.kyc.presentation.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val SapphireLight = lightColorScheme(
    primary = Color(0xFF0F3D91),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD9E4FF),
    onPrimaryContainer = Color(0xFF001B3F),
    secondary = Color(0xFF00629B),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFCDE5FF),
    background = Color(0xFFF5F7FA),
    surface = Color.White,
    surfaceVariant = Color(0xFFE3E8F0),
    outline = Color(0xFF73777F),
    outlineVariant = Color(0xFFC3C7CF),
    error = Color(0xFFDA1E28),
)

object KycColors {
    val Success = Color(0xFF198038)
    val Warning = Color(0xFFB28600)
    val Danger = Color(0xFFDA1E28)
    val Neutral = Color(0xFF6F6F6F)
    val Purple = Color(0xFF8A3FFC)
}

@Composable
fun SapphireKycTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = SapphireLight, content = content)
}
