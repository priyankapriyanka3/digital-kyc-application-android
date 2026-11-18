package com.sapphirebank.kyc.presentation.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

private val SapphireLight = lightColorScheme(
    primary = KycColors.Sapphire,
    onPrimary = KycColors.Surface,
    primaryContainer = KycColors.SapphireContainer,
    onPrimaryContainer = KycColors.NavyDark,
    secondary = KycColors.Navy,
    onSecondary = KycColors.Surface,
    secondaryContainer = KycColors.SapphireContainer,
    onSecondaryContainer = KycColors.NavyDark,
    tertiary = KycColors.Purple,
    background = KycColors.Background,
    onBackground = KycColors.TextPrimary,
    surface = KycColors.Surface,
    onSurface = KycColors.TextPrimary,
    surfaceVariant = KycColors.SurfaceMuted,
    onSurfaceVariant = KycColors.TextSecondary,
    outline = KycColors.TextSecondary,
    outlineVariant = KycColors.Outline,
    error = KycColors.Danger,
    onError = KycColors.Surface,
    errorContainer = KycColors.DangerBg,
    onErrorContainer = KycColors.Danger,
)

private val KycTypography = Typography().let { t ->
    t.copy(
        headlineSmall = t.headlineSmall.copy(fontWeight = FontWeight.SemiBold),
        titleLarge = t.titleLarge.copy(fontWeight = FontWeight.SemiBold),
        titleMedium = t.titleMedium.copy(fontWeight = FontWeight.SemiBold),
        titleSmall = t.titleSmall.copy(fontWeight = FontWeight.SemiBold),
        labelLarge = t.labelLarge.copy(fontWeight = FontWeight.SemiBold),
        // 12sp floor: nothing in the app is set smaller than this.
        labelSmall = TextStyle(fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.Medium),
    )
}

private val KycShapes = Shapes(
    small = RoundedCornerShape(Radius.field),
    medium = RoundedCornerShape(Radius.card),
    large = RoundedCornerShape(Radius.sheet),
)

@Composable
fun SapphireKycTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = SapphireLight, typography = KycTypography, shapes = KycShapes, content = content)
}
