package com.sapphirebank.kyc.presentation.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.sapphirebank.kyc.domain.model.Tone

/** 8dp spacing scale (4dp half-step for tight icon/label pairs). Use these instead of raw dp literals. */
object Spacing {
    val xs = 4.dp
    val s = 8.dp
    val m = 16.dp
    val l = 24.dp
    val xl = 32.dp
    val xxl = 48.dp
}

object Radius {
    val field = 8.dp
    val card = 12.dp
    val sheet = 24.dp
}

object Elevation {
    val none = 0.dp
    val card = 1.dp
    val raised = 3.dp
}

object Sizes {
    /** Minimum touch target (Material accessibility guideline). */
    val touch = 48.dp
    val iconTile = 40.dp
    val gauge = 88.dp

    /** Content is centred and capped on tablets / landscape so lines stay readable. */
    val maxContentWidth = 840.dp
    val railBreakpoint = 600
}

/**
 * Brand and status palette. Status colours are paired text/background pairs chosen for >= 4.5:1 contrast
 * on their tinted backgrounds; status is always also conveyed by a text label, never by colour alone.
 */
object KycColors {
    val Navy = Color(0xFF0B2A6F)
    val NavyDark = Color(0xFF071B4A)
    val Sapphire = Color(0xFF0F52BA)
    val SapphireContainer = Color(0xFFDCE6FA)

    val Background = Color(0xFFF4F6FA)
    val Surface = Color(0xFFFFFFFF)
    val SurfaceMuted = Color(0xFFEEF2F8)
    val Outline = Color(0xFFD5DBE6)
    val TextPrimary = Color(0xFF141A26)
    val TextSecondary = Color(0xFF475066)

    val Success = Color(0xFF1B7A3A)
    val SuccessBg = Color(0xFFE3F4E8)
    val Warning = Color(0xFF8A5A00)
    val WarningBg = Color(0xFFFFF1D6)
    val Danger = Color(0xFFB3261E)
    val DangerBg = Color(0xFFFBE4E2)
    val Info = Color(0xFF0F52BA)
    val InfoBg = Color(0xFFDCE6FA)
    val Neutral = Color(0xFF475066)
    val NeutralBg = Color(0xFFE8ECF3)
    val Purple = Color(0xFF6B3FA0)
}

fun Tone.color(): Color = when (this) {
    Tone.GOOD -> KycColors.Success
    Tone.WATCH -> KycColors.Warning
    Tone.BAD -> KycColors.Danger
    Tone.INFO -> KycColors.Info
}

fun Tone.background(): Color = when (this) {
    Tone.GOOD -> KycColors.SuccessBg
    Tone.WATCH -> KycColors.WarningBg
    Tone.BAD -> KycColors.DangerBg
    Tone.INFO -> KycColors.InfoBg
}
