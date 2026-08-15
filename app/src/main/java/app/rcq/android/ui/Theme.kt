package app.rcq.android.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.LocalTextStyle
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
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.LineHeightStyle
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

/** The palette a surface of this darkness wants its text and glyphs drawn
 *  from. The two variants are the only two sets of foregrounds the app has, so
 *  chrome that lands on something other than the theme background (a wallpaper)
 *  borrows one of them rather than inventing a third. */
internal fun rcqColorsFor(dark: Boolean): RcqColors = if (dark) DarkColors else LightColors

/**
 * Does chrome drawn ON this colour need the DARK theme's light foregrounds?
 *
 * ⚠ The threshold is not the obvious "darker than mid-grey". Black text and
 * white text trade places at the luminance where their contrast ratios against
 * the surface are equal — (L + 0.05) / 0.05 == 1.05 / (L + 0.05) — which is
 * L ≈ 0.179, not 0.5. Half-way is wrong by a wide margin on saturated colours:
 * the "Sunset" wallpaper (#FF8008) has L ≈ 0.37, so a 0.5 threshold calls it
 * dark and paints white on it at a contrast ratio of 2.5, while the black it
 * rejects scores 8.3.
 */
internal fun Color.needsLightChrome(): Boolean = luminance() < 0.179f

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
        MaterialTheme(colorScheme = scheme, typography = tightTypography) {
            CompositionLocalProvider(LocalTextStyle provides tightTextStyle, content = content)
        }
    }
}

/**
 * Android puts padding above and below every line of text by default
 * (`includeFontPadding`, kept since 2009 for a font-metrics quirk). It is why
 * a two-line row here — a name over its subtitle — reads with a gap nobody
 * asked for, and it compounds with the system font size: on a phone with text
 * scaled up the list turns into a ladder of half-empty rows. A tester put it
 * plainly: "do a good deed, remove that huge line spacing everywhere".
 *
 * Turning it off is the single change that fixes it everywhere at once,
 * rather than one screen at a time. `Trim.FirstLineTop + LastLineBottom` does
 * the same for the line-height slack Material's type scale adds around a
 * paragraph, so multi-line text keeps its INTERNAL leading (which is what
 * makes prose readable) and loses only the padding at the block's edges.
 */
private val tightPlatformStyle = PlatformTextStyle(includeFontPadding = false)
private val tightLineHeight = LineHeightStyle(
    alignment = LineHeightStyle.Alignment.Center,
    trim = LineHeightStyle.Trim.Both,
)

private val tightTextStyle = TextStyle(
    platformStyle = tightPlatformStyle,
    lineHeightStyle = tightLineHeight,
)

private val tightTypography: Typography = Typography().let { base ->
    fun TextStyle.tight() = copy(
        platformStyle = tightPlatformStyle,
        lineHeightStyle = tightLineHeight,
    )
    base.copy(
        displayLarge = base.displayLarge.tight(),
        displayMedium = base.displayMedium.tight(),
        displaySmall = base.displaySmall.tight(),
        headlineLarge = base.headlineLarge.tight(),
        headlineMedium = base.headlineMedium.tight(),
        headlineSmall = base.headlineSmall.tight(),
        titleLarge = base.titleLarge.tight(),
        titleMedium = base.titleMedium.tight(),
        titleSmall = base.titleSmall.tight(),
        bodyLarge = base.bodyLarge.tight(),
        bodyMedium = base.bodyMedium.tight(),
        bodySmall = base.bodySmall.tight(),
        labelLarge = base.labelLarge.tight(),
        labelMedium = base.labelMedium.tight(),
        labelSmall = base.labelSmall.tight(),
    )
}
