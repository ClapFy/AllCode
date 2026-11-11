package com.allcode.connect.ui.theme

import android.provider.Settings
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.tween
import androidx.compose.foundation.LocalOverscrollFactory
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.allcode.connect.R
import com.allcode.connect.data.ThemeMode

/** The cozy palette: warm paper, soft ink, one clay accent. */
@Immutable
data class Palette(
    val paper: Color,
    val paperDeep: Color,
    val surface: Color,
    val inset: Color,
    val line: Color,
    val lineStrong: Color,
    val ink: Color,
    val inkSoft: Color,
    val inkFaint: Color,
    val clay: Color,
    val clayPressed: Color,
    val onClay: Color,
    val claySoft: Color,
    val userBubble: Color,
    val sage: Color,
    val sageSoft: Color,
    val amber: Color,
    val amberSoft: Color,
    val rust: Color,
    val rustSoft: Color,
    val code: Color,
    val addition: Color,
    val deletion: Color,
    val isDark: Boolean,
)

val LightPalette = Palette(
    paper = Color(0xFFFAF9F5),
    paperDeep = Color(0xFFF3F1EA),
    surface = Color(0xFFFFFFFF),
    inset = Color(0xFFF0EEE6),
    line = Color(0xFFE8E5DB),
    lineStrong = Color(0xFFD6D2C4),
    ink = Color(0xFF141413),
    inkSoft = Color(0xFF5E5D59),
    inkFaint = Color(0xFF8F8D86),
    clay = Color(0xFFD97757),
    clayPressed = Color(0xFFC6613F),
    onClay = Color(0xFFFFFFFF),
    claySoft = Color(0xFFF7E3DA),
    userBubble = Color(0xFFF0EEE6),
    sage = Color(0xFF5B7F5B),
    sageSoft = Color(0xFFE4EDE0),
    amber = Color(0xFFB0782A),
    amberSoft = Color(0xFFF6EBD6),
    rust = Color(0xFFB4483A),
    rustSoft = Color(0xFFF6E0DC),
    code = Color(0xFFF5F3EC),
    addition = Color(0xFFE3EFDD),
    deletion = Color(0xFFF7E1DC),
    isDark = false,
)

val DarkPalette = Palette(
    paper = Color(0xFF262624),
    paperDeep = Color(0xFF1F1E1D),
    surface = Color(0xFF30302E),
    inset = Color(0xFF3A3936),
    line = Color(0xFF3F3E3A),
    lineStrong = Color(0xFF52504B),
    ink = Color(0xFFF5F4EE),
    inkSoft = Color(0xFFB8B5AB),
    inkFaint = Color(0xFF8A877E),
    clay = Color(0xFFE08A6B),
    clayPressed = Color(0xFFD97757),
    onClay = Color(0xFF1F1E1D),
    claySoft = Color(0xFF4A342B),
    userBubble = Color(0xFF1F1E1D),
    sage = Color(0xFF9CC29A),
    sageSoft = Color(0xFF2F3B2E),
    amber = Color(0xFFE2B26A),
    amberSoft = Color(0xFF433625),
    rust = Color(0xFFEE8F80),
    rustSoft = Color(0xFF4A2D28),
    code = Color(0xFF1F1E1D),
    addition = Color(0xFF263826),
    deletion = Color(0xFF452A25),
    isDark = true,
)

val SansFamily = FontFamily(
    Font(R.font.dmsans_regular, FontWeight.Normal),
    Font(R.font.dmsans_medium, FontWeight.Medium),
    Font(R.font.dmsans_semibold, FontWeight.SemiBold),
    Font(R.font.dmsans_bold, FontWeight.Bold),
)

val MonoFamily = FontFamily(
    Font(R.font.jetbrainsmono, FontWeight.Normal, variationSettings = FontVariation.Settings(FontVariation.weight(400))),
    Font(R.font.jetbrainsmono, FontWeight.Medium, variationSettings = FontVariation.Settings(FontVariation.weight(500))),
    Font(R.font.jetbrainsmono, FontWeight.Bold, variationSettings = FontVariation.Settings(FontVariation.weight(700))),
)

/** Text roles. Prose and titles in sans; everything the machine says (status, paths, tools) in mono. */
@Immutable
data class TextRoles(
    val greeting: TextStyle,
    val title: TextStyle,
    val heading: TextStyle,
    val prose: TextStyle,
    val body: TextStyle,
    val bodyStrong: TextStyle,
    val label: TextStyle,
    val caption: TextStyle,
    val overline: TextStyle,
    val mono: TextStyle,
    val monoStrong: TextStyle,
    val monoSmall: TextStyle,
)

val Roles = TextRoles(
    greeting = TextStyle(fontFamily = SansFamily, fontWeight = FontWeight.SemiBold, fontSize = 26.sp, lineHeight = 31.sp, letterSpacing = (-0.6).sp),
    title = TextStyle(fontFamily = SansFamily, fontWeight = FontWeight.SemiBold, fontSize = 18.sp, lineHeight = 24.sp, letterSpacing = (-0.2).sp),
    heading = TextStyle(fontFamily = SansFamily, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, lineHeight = 22.sp, letterSpacing = (-0.1).sp),
    prose = TextStyle(fontFamily = SansFamily, fontWeight = FontWeight.Normal, fontSize = 15.5.sp, lineHeight = 24.sp),
    body = TextStyle(fontFamily = SansFamily, fontWeight = FontWeight.Normal, fontSize = 15.sp, lineHeight = 22.sp),
    bodyStrong = TextStyle(fontFamily = SansFamily, fontWeight = FontWeight.Medium, fontSize = 15.sp, lineHeight = 22.sp),
    label = TextStyle(fontFamily = SansFamily, fontWeight = FontWeight.Medium, fontSize = 13.5.sp, lineHeight = 19.sp),
    caption = TextStyle(fontFamily = SansFamily, fontWeight = FontWeight.Normal, fontSize = 12.5.sp, lineHeight = 17.sp),
    overline = TextStyle(fontFamily = MonoFamily, fontWeight = FontWeight.Medium, fontSize = 11.5.sp, lineHeight = 16.sp),
    mono = TextStyle(fontFamily = MonoFamily, fontSize = 13.sp, lineHeight = 20.sp),
    monoStrong = TextStyle(fontFamily = MonoFamily, fontWeight = FontWeight.Bold, fontSize = 13.sp, lineHeight = 20.sp),
    monoSmall = TextStyle(fontFamily = MonoFamily, fontSize = 11.5.sp, lineHeight = 16.sp),
)

/**
 * Motion is rare on purpose: content appears, it doesn't perform. What's left (screen changes, the
 * working spark) is short and ease-out, and goes away when the system asks for less motion.
 */
@Immutable
data class Motion(val reduced: Boolean) {
    fun <T> enter(durationMs: Int = 180): FiniteAnimationSpec<T> = tween(if (reduced) 0 else durationMs, easing = EaseOutStrong)
    fun <T> exit(durationMs: Int = 120): FiniteAnimationSpec<T> = tween(if (reduced) 0 else durationMs, easing = EaseOutStrong)

    companion object {
        val EaseOutStrong = CubicBezierEasing(0.23f, 1f, 0.32f, 1f)
    }
}

val LocalPalette = staticCompositionLocalOf { LightPalette }
val LocalMotion = staticCompositionLocalOf { Motion(reduced = false) }

object AllCode {
    val colors: Palette @Composable get() = LocalPalette.current
    val type: TextRoles get() = Roles
    val motion: Motion @Composable get() = LocalMotion.current
}

private fun Palette.toScheme(): ColorScheme = if (isDark) {
    darkColorScheme(
        primary = clay, onPrimary = onClay, primaryContainer = claySoft, onPrimaryContainer = ink,
        secondary = inkSoft, onSecondary = paper, background = paper, onBackground = ink,
        surface = paper, onSurface = ink, surfaceVariant = inset, onSurfaceVariant = inkSoft,
        surfaceContainerLowest = paperDeep, surfaceContainerLow = paper, surfaceContainer = surface,
        surfaceContainerHigh = surface, surfaceContainerHighest = inset,
        outline = lineStrong, outlineVariant = line, error = rust, onError = paperDeep, scrim = Color.Black,
    )
} else {
    lightColorScheme(
        primary = clay, onPrimary = onClay, primaryContainer = claySoft, onPrimaryContainer = ink,
        secondary = inkSoft, onSecondary = paper, background = paper, onBackground = ink,
        surface = paper, onSurface = ink, surfaceVariant = inset, onSurfaceVariant = inkSoft,
        surfaceContainerLowest = surface, surfaceContainerLow = paper, surfaceContainer = surface,
        surfaceContainerHigh = surface, surfaceContainerHighest = inset,
        outline = lineStrong, outlineVariant = line, error = rust, onError = onClay, scrim = Color.Black,
    )
}

private val MaterialTypography = Typography(
    displaySmall = Roles.greeting,
    headlineSmall = Roles.title,
    titleLarge = Roles.title,
    titleMedium = Roles.heading,
    titleSmall = Roles.bodyStrong,
    bodyLarge = Roles.body,
    bodyMedium = Roles.body,
    bodySmall = Roles.caption,
    labelLarge = Roles.label,
    labelMedium = Roles.label,
    labelSmall = Roles.caption,
)

private val MaterialShapes = Shapes(
    extraSmall = RoundedCornerShape(4.dp),
    small = RoundedCornerShape(6.dp),
    medium = RoundedCornerShape(8.dp),
    large = RoundedCornerShape(12.dp),
    extraLarge = RoundedCornerShape(16.dp),
)

@Composable
fun AllCodeTheme(mode: ThemeMode, content: @Composable () -> Unit) {
    val dark = when (mode) {
        ThemeMode.System -> isSystemInDarkTheme()
        ThemeMode.Light -> false
        ThemeMode.Dark -> true
    }
    val palette = if (dark) DarkPalette else LightPalette
    val context = LocalContext.current
    val motion = remember(context) {
        val scale = Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
        Motion(reduced = scale == 0f)
    }
    // No stretch overscroll: a panel whose content fits shouldn't move under your thumb.
    CompositionLocalProvider(LocalPalette provides palette, LocalMotion provides motion, LocalOverscrollFactory provides null) {
        MaterialTheme(colorScheme = palette.toScheme(), typography = MaterialTypography, shapes = MaterialShapes, content = content)
    }
}
