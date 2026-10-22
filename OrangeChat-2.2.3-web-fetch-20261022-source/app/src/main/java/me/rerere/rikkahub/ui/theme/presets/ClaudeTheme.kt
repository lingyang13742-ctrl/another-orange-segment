/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.ui.theme.presets

import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import me.rerere.rikkahub.R
import me.rerere.rikkahub.ui.theme.PresetTheme

val ClaudeThemePreset by lazy {
    PresetTheme(
        id = "claude",
        name = {
            Text(stringResource(R.string.theme_name_claude))
        },
        standardLight = lightScheme,
        standardDark = darkScheme,
    )
}

// ── Light ──────────────────────────────────────────────────────────────
private val primaryLight = Color(0xFFC96442)
private val onPrimaryLight = Color(0xFFFFFFFF)
private val primaryContainerLight = Color(0xFFFFD9C8)
private val onPrimaryContainerLight = Color(0xFF5A1600)
private val secondaryLight = Color(0xFF7A6654)
private val onSecondaryLight = Color(0xFFFFFFFF)
private val secondaryContainerLight = Color(0xFFE9E6DC)
private val onSecondaryContainerLight = Color(0xFF535146)
private val tertiaryLight = Color(0xFF4C6649)
private val onTertiaryLight = Color(0xFFFFFFFF)
private val tertiaryContainerLight = Color(0xFFCDEBC8)
private val onTertiaryContainerLight = Color(0xFF2B4E2B)
private val errorLight = Color(0xFFBA1A1A)
private val onErrorLight = Color(0xFFFFFFFF)
private val errorContainerLight = Color(0xFFFFDAD6)
private val onErrorContainerLight = Color(0xFF93000A)
private val backgroundLight = Color(0xFFFAF9F5)
private val onBackgroundLight = Color(0xFF3D3929)
private val surfaceLight = Color(0xFFFAF9F5)
private val onSurfaceLight = Color(0xFF3D3929)
private val surfaceVariantLight = Color(0xFFEDE9DE)
private val onSurfaceVariantLight = Color(0xFF4A4640)
private val outlineLight = Color(0xFF7B766D)
private val outlineVariantLight = Color(0xFFDAD9D4)
private val scrimLight = Color(0xFF000000)
private val inverseSurfaceLight = Color(0xFF352F26)
private val inverseOnSurfaceLight = Color(0xFFF5F0E7)
private val inversePrimaryLight = Color(0xFFE3A48C)
private val surfaceDimLight = Color(0xFFDDD9CE)
private val surfaceBrightLight = Color(0xFFFAF9F5)
private val surfaceContainerLowestLight = Color(0xFFFFFFFF)
private val surfaceContainerLowLight = Color(0xFFF5F2EB)
private val surfaceContainerLight = Color(0xFFEFECE4)
private val surfaceContainerHighLight = Color(0xFFEAE7DF)
private val surfaceContainerHighestLight = Color(0xFFE4E1DA)

// ── Dark ───────────────────────────────────────────────────────────────
private val primaryDark = Color(0xFFD97757)
private val onPrimaryDark = Color(0xFF521500)
private val primaryContainerDark = Color(0xFF6B2100)
private val onPrimaryContainerDark = Color(0xFFFFD9C8)
private val secondaryDark = Color(0xFFB9AE9A)
private val onSecondaryDark = Color(0xFF2D261E)
private val secondaryContainerDark = Color(0xFF3E3830)
private val onSecondaryContainerDark = Color(0xFFD5CAB8)
private val tertiaryDark = Color(0xFFADC99F)
private val onTertiaryDark = Color(0xFF1B3018)
private val tertiaryContainerDark = Color(0xFF2F4A2A)
private val onTertiaryContainerDark = Color(0xFFC8E5B8)
private val errorDark = Color(0xFFFFB4AB)
private val onErrorDark = Color(0xFF690005)
private val errorContainerDark = Color(0xFF93000A)
private val onErrorContainerDark = Color(0xFFFFDAD6)
private val backgroundDark = Color(0xFF262624)
private val onBackgroundDark = Color(0xFFC3C0B6)
private val surfaceDark = Color(0xFF262624)
private val onSurfaceDark = Color(0xFFC3C0B6)
private val surfaceVariantDark = Color(0xFF3E3C38)
private val onSurfaceVariantDark = Color(0xFFB7B5A9)
private val outlineDark = Color(0xFF87857D)
private val outlineVariantDark = Color(0xFF3E3E38)
private val scrimDark = Color(0xFF000000)
private val inverseSurfaceDark = Color(0xFFEAE7DF)
private val inverseOnSurfaceDark = Color(0xFF352F26)
private val inversePrimaryDark = Color(0xFFC96442)
private val surfaceDimDark = Color(0xFF1E1C1A)
private val surfaceBrightDark = Color(0xFF3A3835)
private val surfaceContainerLowestDark = Color(0xFF1A1816)
private val surfaceContainerLowDark = Color(0xFF252320)
private val surfaceContainerDark = Color(0xFF2A2724)
private val surfaceContainerHighDark = Color(0xFF2F2D29)
private val surfaceContainerHighestDark = Color(0xFF3A3835)

private val lightScheme = lightColorScheme(
    primary = primaryLight,
    onPrimary = onPrimaryLight,
    primaryContainer = primaryContainerLight,
    onPrimaryContainer = onPrimaryContainerLight,
    secondary = secondaryLight,
    onSecondary = onSecondaryLight,
    secondaryContainer = secondaryContainerLight,
    onSecondaryContainer = onSecondaryContainerLight,
    tertiary = tertiaryLight,
    onTertiary = onTertiaryLight,
    tertiaryContainer = tertiaryContainerLight,
    onTertiaryContainer = onTertiaryContainerLight,
    error = errorLight,
    onError = onErrorLight,
    errorContainer = errorContainerLight,
    onErrorContainer = onErrorContainerLight,
    background = backgroundLight,
    onBackground = onBackgroundLight,
    surface = surfaceLight,
    onSurface = onSurfaceLight,
    surfaceVariant = surfaceVariantLight,
    onSurfaceVariant = onSurfaceVariantLight,
    outline = outlineLight,
    outlineVariant = outlineVariantLight,
    scrim = scrimLight,
    inverseSurface = inverseSurfaceLight,
    inverseOnSurface = inverseOnSurfaceLight,
    inversePrimary = inversePrimaryLight,
    surfaceDim = surfaceDimLight,
    surfaceBright = surfaceBrightLight,
    surfaceContainerLowest = surfaceContainerLowestLight,
    surfaceContainerLow = surfaceContainerLowLight,
    surfaceContainer = surfaceContainerLight,
    surfaceContainerHigh = surfaceContainerHighLight,
    surfaceContainerHighest = surfaceContainerHighestLight,
)

private val darkScheme = darkColorScheme(
    primary = primaryDark,
    onPrimary = onPrimaryDark,
    primaryContainer = primaryContainerDark,
    onPrimaryContainer = onPrimaryContainerDark,
    secondary = secondaryDark,
    onSecondary = onSecondaryDark,
    secondaryContainer = secondaryContainerDark,
    onSecondaryContainer = onSecondaryContainerDark,
    tertiary = tertiaryDark,
    onTertiary = onTertiaryDark,
    tertiaryContainer = tertiaryContainerDark,
    onTertiaryContainer = onTertiaryContainerDark,
    error = errorDark,
    onError = onErrorDark,
    errorContainer = errorContainerDark,
    onErrorContainer = onErrorContainerDark,
    background = backgroundDark,
    onBackground = onBackgroundDark,
    surface = surfaceDark,
    onSurface = onSurfaceDark,
    surfaceVariant = surfaceVariantDark,
    onSurfaceVariant = onSurfaceVariantDark,
    outline = outlineDark,
    outlineVariant = outlineVariantDark,
    scrim = scrimDark,
    inverseSurface = inverseSurfaceDark,
    inverseOnSurface = inverseOnSurfaceDark,
    inversePrimary = inversePrimaryDark,
    surfaceDim = surfaceDimDark,
    surfaceBright = surfaceBrightDark,
    surfaceContainerLowest = surfaceContainerLowestDark,
    surfaceContainerLow = surfaceContainerLowDark,
    surfaceContainer = surfaceContainerDark,
    surfaceContainerHigh = surfaceContainerHighDark,
    surfaceContainerHighest = surfaceContainerHighestDark,
)