package app.thebridge.ui.theme

import android.provider.Settings
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import app.thebridge.R

/** Mirrors tokens/tokens.json. */
@Immutable
data class BridgeColors(
    val page: Color, val bg: Color, val s0: Color, val s1: Color, val s2: Color, val bubble: Color,
    val ink: Color, val ink2: Color, val mut: Color, val mut2: Color,
    val line: Color, val line2: Color, val track: Color,
    val acc: Color, val accInk: Color, val onAcc: Color, val accTint: Color, val accLine: Color,
    val prog: Color, val today: Color, val pill: Color,
    val ok: Color, val okFill: Color, val onOk: Color, val okTint: Color,
    val bad: Color, val badTint: Color, val warn: Color, val warnTint: Color,
    val pHi: Color, val pHiTint: Color, val pMd: Color, val pMdTint: Color, val pLo: Color, val pLoTint: Color,
    val goalCards: List<Color>, val goalInk: Color, val goalTrack: Color,
    val mascotBody: Color, val mascotInk: Color, val mascotCheek: Color,
    /** Avatar circles and the four feature tiles (Community preview). */
    val av: Color, val tiles: List<Color>,
    /** Bronze tier in alliance rankings (gold uses accInk, silver ink2). */
    val bronze: Color,
    val isDark: Boolean,
)

val LightColors = BridgeColors(
    page = Color(0xFFEFEDE7), bg = Color(0xFFFBFAF7), s0 = Color(0xFFF3F1EB), s1 = Color(0xFFFFFFFF), s2 = Color(0xFFF5F3EE), bubble = Color(0xFFEAF3E2),
    ink = Color(0xFF23262B), ink2 = Color(0xFF4A4E55), mut = Color(0xFF7A7D83), mut2 = Color(0xFFA7A9AD),
    line = Color(0xFFE3E0D8), line2 = Color(0xFFD6D2C8), track = Color(0xFFEEEBE4),
    acc = Color(0xFF2F3A33), accInk = Color(0xFF4F8A3A), onAcc = Color(0xFFFFFFFF), accTint = Color(0xFFE4F2D9), accLine = Color(0xFFA9D38F),
    prog = Color(0xFF9FD17F), today = Color(0xFFF2A97E), pill = Color(0xFFDDEFD0),
    ok = Color(0xFF4F8A3A), okFill = Color(0xFFBFE3A6), onOk = Color(0xFF23262B), okTint = Color(0xFFEEF7E8),
    bad = Color(0xFFD2553F), badTint = Color(0xFFFCEDE8), warn = Color(0xFFB8741C), warnTint = Color(0xFFFCF1E2),
    pHi = Color(0xFFB9452E), pHiTint = Color(0xFFFBE3DB), pMd = Color(0xFF8F6410), pMdTint = Color(0xFFF7EBCD), pLo = Color(0xFF3F7262), pLoTint = Color(0xFFDFEEE7),
    goalCards = listOf(Color(0xFFFCE7D9), Color(0xFFE4F2D9), Color(0xFFDFECF6)), goalInk = Color(0xFF23262B), goalTrack = Color(0xFF23262B).copy(alpha = .12f),
    mascotBody = Color(0xFFF5BE98), mascotInk = Color(0xFF23262B), mascotCheek = Color(0xFFE07A62),
    av = Color(0xFFFCE7D9), tiles = listOf(Color(0xFFFCE7D9), Color(0xFFE4F2D9), Color(0xFFDFECF6), Color(0xFFFBF0CF)), bronze = Color(0xFFC98B5A),
    isDark = false,
)

val DarkColors = BridgeColors(
    page = Color(0xFF050505), bg = Color(0xFF0C0C0C), s0 = Color(0xFF000000), s1 = Color(0xFF151515), s2 = Color(0xFF1F1F1F), bubble = Color(0xFF262626),
    ink = Color(0xFFF2F2F2), ink2 = Color(0xFFC8C8C8), mut = Color(0xFF8C8C8C), mut2 = Color(0xFF5E5E5E),
    line = Color.White.copy(alpha = .12f), line2 = Color.White.copy(alpha = .22f), track = Color(0xFF262626),
    acc = Color(0xFFE8643A), accInk = Color(0xFFF08A62), onAcc = Color(0xFF1A0C06), accTint = Color(0xFFE8643A).copy(alpha = .12f), accLine = Color(0xFFE8643A).copy(alpha = .5f),
    prog = Color(0xFFE8643A), today = Color(0xFF8FD17A), pill = Color(0xFF3A2016),
    ok = Color(0xFF8FD17A), okFill = Color(0xFF8FD17A), onOk = Color(0xFF0C0C0C), okTint = Color(0xFF8FD17A).copy(alpha = .1f),
    bad = Color(0xFFE5533D), badTint = Color(0xFFE5533D).copy(alpha = .1f), warn = Color(0xFFE9A23B), warnTint = Color(0xFFE9A23B).copy(alpha = .1f),
    pHi = Color(0xFFF2937A), pHiTint = Color(0xFFF2937A).copy(alpha = .16f), pMd = Color(0xFFE9BC5C), pMdTint = Color(0xFFE9BC5C).copy(alpha = .15f), pLo = Color(0xFF8DBFAE), pLoTint = Color(0xFF8DBFAE).copy(alpha = .15f),
    goalCards = listOf(Color(0xFF7E3A20), Color(0xFF24564F), Color(0xFF5A4A1C)), goalInk = Color.White, goalTrack = Color.White.copy(alpha = .25f),
    mascotBody = Color(0xFFE8875A), mascotInk = Color(0xFF1A0C06), mascotCheek = Color(0xFFB83A22),
    av = Color(0xFF1F1F1F), tiles = List(4) { Color(0xFF151515) }, bronze = Color(0xFFC98B5A),
    isDark = true,
)

// The bundled files are variable fonts (SIL OFL); each weight is an instance on the wght axis.
@OptIn(ExperimentalTextApi::class)
private fun variable(res: Int, vararg weights: FontWeight) =
    FontFamily(weights.map { Font(res, it, variationSettings = FontVariation.Settings(FontVariation.weight(it.weight))) })

val Bricolage = variable(R.font.bricolage_grotesque, FontWeight.SemiBold, FontWeight.Bold)
val Geist = variable(R.font.geist, FontWeight.Normal, FontWeight.Medium, FontWeight.SemiBold)
val GeistMono = variable(R.font.geist_mono, FontWeight.Medium)

object BType {
    /** Display sizes stop growing at 1.3× font scale so headings never crowd out the content. */
    @Composable
    fun display(size: Int, lineHeight: Float = 1.1f): TextStyle {
        val scale = LocalDensity.current.fontScale
        val capped = size * (minOf(scale, 1.3f) / scale)
        return TextStyle(fontFamily = Bricolage, fontWeight = FontWeight.SemiBold, fontSize = capped.sp, lineHeight = (capped * lineHeight).sp)
    }
    fun body(size: Int, weight: FontWeight = FontWeight.Normal, lineHeight: Float = 1.45f) =
        TextStyle(fontFamily = Geist, fontWeight = weight, fontSize = size.sp, lineHeight = (size * lineHeight).sp)
    /** Geist Mono label. Callers pass text already uppercased by the string resource or `.uppercase()`. */
    fun label(size: Float = 10f, tracking: Float = 0.13f) =
        TextStyle(fontFamily = GeistMono, fontWeight = FontWeight.Medium, fontSize = size.sp, letterSpacing = tracking.em)
}

object Radius { val check = 8.dp; val pill = 11.dp; val input = 14.dp; val button = 16.dp; val card = 20.dp; val goalCard = 24.dp; val hero = 30.dp }
object Space { val screen = 20.dp; val section = 18.dp; val card = 16.dp; val stack = 12.dp; val tight = 8.dp; val hit = 44.dp }
object Motion { const val fade = 200; const val push = 320; const val sheet = 300; const val toast = 220; const val toastHold = 2600L }

val LocalBridgeColors = staticCompositionLocalOf { LightColors }
/** True when the system has animations turned off; screens cross-fade instead of sliding and the mascot holds still. */
val LocalReduceMotion = staticCompositionLocalOf { false }

@Composable
fun BridgeTheme(override: String = "system", content: @Composable () -> Unit) {
    val dark = when (override) { "dark" -> true; "light" -> false; else -> isSystemInDarkTheme() }
    val c = if (dark) DarkColors else LightColors
    val ctx = LocalContext.current
    val reduce = remember { runCatching { Settings.Global.getFloat(ctx.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f }.getOrDefault(false) }
    // Material components read from this scheme; our own components read LocalBridgeColors.
    val scheme = (if (dark) darkColorScheme() else lightColorScheme()).copy(
        primary = c.acc, onPrimary = c.onAcc, background = c.bg, onBackground = c.ink,
        surface = c.s1, onSurface = c.ink, onSurfaceVariant = c.mut, outline = c.line, error = c.bad,
        surfaceContainerLow = c.s1, scrim = Color.Black.copy(alpha = .5f),
    )
    CompositionLocalProvider(LocalBridgeColors provides c, LocalReduceMotion provides reduce) {
        MaterialTheme(colorScheme = scheme, content = content)
    }
}
