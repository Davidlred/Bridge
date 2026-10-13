package app.thebridge.feature.coach

import android.provider.Settings
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateOffsetAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.invisibleToUser
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.thebridge.ui.theme.LocalBridgeColors
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

enum class MascotMood { Idle, Listening, Thinking, Talking, Happy }

@Composable
private fun rememberReduceMotion(): Boolean {
    val ctx = LocalContext.current
    return remember { Settings.Global.getFloat(ctx.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f }
}

/** The coach character, drawn in a 120×120 unit space. One frame clock drives every motion. */
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
fun CoachMascot(mood: MascotMood, modifier: Modifier = Modifier, size: Dp = 112.dp, reduceMotion: Boolean = rememberReduceMotion()) {
    val c = LocalBridgeColors.current
    var t by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(reduceMotion) {
        if (reduceMotion) { t = 0f; return@LaunchedEffect }
        val start = withFrameNanos { it }
        while (true) withFrameNanos { t = (it - start) / 1_000_000_000f }
    }
    val moodStart = remember(mood) { t }
    val look by animateOffsetAsState(
        when (mood) { MascotMood.Listening -> Offset(0f, -4f); MascotMood.Thinking -> Offset(7f, -6f); else -> Offset.Zero },
        spring(dampingRatio = 0.8f, stiffness = Spring.StiffnessMediumLow), label = "look",
    )
    val tau = (2 * PI).toFloat()

    Canvas(modifier.size(size).semantics { invisibleToUser() }) {
        scale(this.size.width / 120f, pivot = Offset.Zero) {
            drawOval(c.track, topLeft = Offset(30f, 109f), size = Size(60f, 8f))

            val period = if (mood == MascotMood.Talking) 1.1f else 3.2f
            val b = if (mood == MascotMood.Thinking || mood == MascotMood.Happy) 0f else 0.5f - 0.5f * cos(tau * t / period)
            val since = if (reduceMotion) 10f else t - moodStart
            val hop = if (mood == MascotMood.Happy && since < 1.1f) -14f * sin(PI.toFloat() * ((since / 0.55f) % 1f)) else 0f
            val tilt = if (mood == MascotMood.Thinking) 5f * sin(tau * t / 1.6f) else 0f
            val pivot = Offset(60f, 110f)

            withTransform({
                translate(0f, -3f * b + hop)
                rotate(tilt, pivot)
                scale(1f + 0.02f * b, 1f - 0.02f * b, pivot)
            }) {
                val antenna = Path().apply { moveTo(45f, 40f); quadraticTo(60f, 10f, 75f, 40f) }
                drawPath(antenna, c.mascotInk, style = Stroke(width = 5f, cap = StrokeCap.Round))
                drawRoundRect(c.mascotBody, topLeft = Offset(18f, 36f), size = Size(84f, 74f), cornerRadius = CornerRadius(34f))
                drawCircle(c.mascotCheek.copy(alpha = .35f), radius = 6f, center = Offset(35f, 77f))
                drawCircle(c.mascotCheek.copy(alpha = .35f), radius = 6f, center = Offset(85f, 77f))

                translate(look.x, look.y) {
                    val round = Stroke(width = 3f, cap = StrokeCap.Round)
                    val phase = (t / 4.2f) % 1f
                    val blink = if (phase in 0.92f..0.98f) 0.1f else 1f
                    for (cx in listOf(46f, 74f)) {
                        if (mood == MascotMood.Happy) {
                            drawPath(Path().apply { moveTo(cx - 6, 64f); quadraticTo(cx, 55f, cx + 6, 64f) }, c.mascotInk, style = Stroke(3.5f, cap = StrokeCap.Round))
                        } else {
                            scale(1f, blink, pivot = Offset(cx, 62f)) { drawOval(c.mascotInk, topLeft = Offset(cx - 5f, 55.5f), size = Size(10f, 13f)) }
                        }
                    }
                    when (mood) {
                        MascotMood.Talking -> {
                            val m = 0.3f + 0.7f * (0.5f - 0.5f * cos(tau * t / 0.24f))
                            scale(1f, m, pivot = Offset(60f, 80f)) { drawOval(c.mascotInk, topLeft = Offset(53.5f, 74.5f), size = Size(13f, 11f)) }
                        }
                        MascotMood.Thinking -> drawLine(c.mascotInk, Offset(54f, 81f), Offset(66f, 79f), strokeWidth = 3f, cap = StrokeCap.Round)
                        else -> {
                            val (a, ctl, z) = when (mood) {
                                MascotMood.Happy -> Triple(Offset(49f, 76f), Offset(60f, 89f), Offset(71f, 76f))
                                MascotMood.Listening -> Triple(Offset(55f, 79f), Offset(60f, 82f), Offset(65f, 79f))
                                else -> Triple(Offset(53f, 78f), Offset(60f, 84f), Offset(67f, 78f))
                            }
                            drawPath(Path().apply { moveTo(a.x, a.y); quadraticTo(ctl.x, ctl.y, z.x, z.y) }, c.mascotInk, style = round)
                        }
                    }
                }
            }
        }
    }
}
