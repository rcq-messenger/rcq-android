package app.rcq.android.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

/**
 * ICQ-2002 palette, ported one-to-one from the iOS client's
 * `Theme.swift`. Two variants: light (classic white) and dark (night
 * mode). The accent is the iconic ICQ "flower" green — primary actions,
 * the active-status accent, and selected pills all use it.
 *
 * Anywhere in the UI we read `RcqTheme.colors.bgPrimary` etc.; the
 * active variant is chosen once at the root by [RcqTheme]. Wrapping in a
 * real MaterialTheme colorScheme is what fixes Material3 components
 * (OutlinedTextField, dialogs) rendering black-on-black text in the
 * dark theme — they fall back to the scheme's onSurface otherwise.
 */
@Immutable
data class RcqColors(
    val bgPrimary: Color,
    val bgSecondary: Color,
    val bgRowHover: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val textMono: Color,
    val accent: Color,
    val accentPressed: Color,
    val bubbleSelf: Color,
    val bubbleOther: Color,
    val divider: Color,
    // Status dots are spec-locked across both themes.
    val statusOnline: Color = Color(0xFF4CAF50),
    val statusAway: Color = Color(0xFFFFC107),
    val statusBusy: Color = Color(0xFFF44336),
    val statusInvisible: Color = Color(0xFF9C27B0),
    val statusOffline: Color = Color(0xFF9E9E9E),
    val isDark: Boolean,
)

private val LightColors = RcqColors(
    bgPrimary = Color(0xFFFFFFFF),
    bgSecondary = Color(0xFFF2F2F2),
    bgRowHover = Color(0xFFE6EFFA),
    textPrimary = Color(0xFF000000),
    textSecondary = Color(0xFF555555),
    textMono = Color(0xFF222222),
    accent = Color(0xFF6BB12C),
    accentPressed = Color(0xFF4F8E1C),
    bubbleSelf = Color(0xFFDCEEFC),
    bubbleOther = Color(0xFFF2F2F2),
    divider = Color(0xFFCFCFCF),
    isDark = false,
)

private val DarkColors = RcqColors(
    bgPrimary = Color(0xFF1A1A1A),
    bgSecondary = Color(0xFF222222),
    bgRowHover = Color(0xFF2A2A2A),
    textPrimary = Color(0xFFEDEDED),
    textSecondary = Color(0xFF9A9A9A),
    textMono = Color(0xFFB8B8B8),
    accent = Color(0xFF84C32C),
    accentPressed = Color(0xFF6BB12C),
    bubbleSelf = Color(0xFF2E2E2E),
    bubbleOther = Color(0xFF222222),
    divider = Color(0xFF303030),
    isDark = true,
)

val LocalRcqColors = staticCompositionLocalOf { DarkColors }

object RcqTheme {
    val colors: RcqColors
        @Composable @ReadOnlyComposable get() = LocalRcqColors.current
}

/** User-selectable appearance. SYSTEM follows the OS dark-mode flag. */
enum class ThemeMode { SYSTEM, LIGHT, DARK }

@Composable
fun RcqTheme(mode: ThemeMode, content: @Composable () -> Unit) {
    val dark = when (mode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    val colors = if (dark) DarkColors else LightColors

    // Match the system status- + nav-bar ICON tint to the theme: on dark
    // mode the bars are dark, so their icons (wifi/battery/clock) must be
    // LIGHT — otherwise they render black-on-dark and vanish. Light theme
    // wants dark icons. (Previously unmanaged → black icons in dark mode.)
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            var ctx = view.context
            while (ctx is android.content.ContextWrapper) {
                if (ctx is android.app.Activity) {
                    val controller = WindowCompat.getInsetsController(ctx.window, view)
                    controller.isAppearanceLightStatusBars = !dark
                    controller.isAppearanceLightNavigationBars = !dark
                    break
                }
                ctx = ctx.baseContext
            }
        }
    }

    // Mirror the RCQ palette into a Material3 colorScheme so built-in
    // components (text fields, AlertDialog, ripples) inherit the right
    // on-colors. This is the actual fix for black text in dark mode.
    val scheme = if (dark) {
        darkColorScheme(
            primary = colors.accent,
            onPrimary = Color.White,
            background = colors.bgPrimary,
            onBackground = colors.textPrimary,
            surface = colors.bgSecondary,
            onSurface = colors.textPrimary,
            surfaceVariant = colors.bgSecondary,
            onSurfaceVariant = colors.textSecondary,
            outline = colors.divider,
        )
    } else {
        lightColorScheme(
            primary = colors.accent,
            onPrimary = Color.White,
            background = colors.bgPrimary,
            onBackground = colors.textPrimary,
            surface = colors.bgPrimary,
            onSurface = colors.textPrimary,
            surfaceVariant = colors.bgSecondary,
            onSurfaceVariant = colors.textSecondary,
            outline = colors.divider,
        )
    }

    CompositionLocalProvider(LocalRcqColors provides colors) {
        MaterialTheme(colorScheme = scheme, typography = Typography(), content = content)
    }
}
