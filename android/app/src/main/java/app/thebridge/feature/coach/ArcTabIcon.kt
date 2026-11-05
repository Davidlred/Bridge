package app.thebridge.feature.coach

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.semantics.invisibleToUser
import androidx.compose.ui.semantics.semantics
import app.thebridge.ui.theme.LocalReduceMotion
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Arc, the coach, as the Coach tab icon: the mascot's body and arc antenna in one tint, drawn in a 24-unit box.
 * It bobs, blinks, and bubbles rise from the antenna. Still, with no bubbles, when motion is reduced.
 * [time] pins one frame (for snapshots). The iOS tab bar uses the same frame maths (ArcTabGlyph.swift).
 */
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
fun ArcTabIcon(tint: Color, modifier: Modifier = Modifier, time: Float? = null, reduceMotion: Boolean = LocalReduceMotion.current) {
    val clock = remember { mutableFloatStateOf(0f) }
    LaunchedEffect(reduceMotion, time) {
        if (reduceMotion || time != null) return@LaunchedEffect
        val start = withFrameNanos { it }
        while (true) clock.floatValue = (withFrameNanos { it } - start) / 1_000_000_000f
    }
    Canvas(modifier.semantics { invisibleToUser() }) {
        // Read the clock only here, so each frame redraws the icon without recomposing the tab bar.
        val f = ArcFrame.at(time ?: clock.floatValue, still = reduceMotion)
        scale(size.width / 24f, pivot = Offset.Zero) {
            for (b in f.bubbles) drawCircle(tint.copy(alpha = tint.alpha * b.alpha), b.r, Offset(b.x, b.y))
            withTransform({
                translate(0f, f.lift)
                scale(1f + f.squash, 1f - f.squash, Offset(12f, 21.5f))
            }) {
                drawPath(ANTENNA, tint, style = Stroke(1.8f, cap = StrokeCap.Round))
                drawPath(body(f.blink), tint)
            }
        }
    }
}


private val ANTENNA = Path().apply { moveTo(9f, 9.5f); quadraticTo(12f, 3f, 15f, 9.5f) }

/** Body with the eyes and smile cut out (even-odd fill), so the icon stays one tint on any background. */
private fun body(blink: Float) = Path().apply {
    fillType = PathFillType.EvenOdd
    addRoundRect(RoundRect(3f, 9f, 21f, 21.5f, CornerRadius(6f)))
    val h = 2.8f * blink
    addOval(Rect(7.9f, 14f - h / 2, 10.1f, 14f + h / 2))
    addOval(Rect(13.9f, 14f - h / 2, 16.1f, 14f + h / 2))
    moveTo(10.4f, 17f); quadraticTo(12f, 19f, 13.6f, 17f); quadraticTo(12f, 17.6f, 10.4f, 17f); close()
}

/** One frame of the tab icon. Everything loops every [LOOP] seconds, so iOS can pre-draw the frames. */
internal data class ArcFrame(val lift: Float, val squash: Float, val blink: Float, val bubbles: List<Bubble>) {
    data class Bubble(val x: Float, val y: Float, val r: Float, val alpha: Float)

    companion object {
        const val LOOP = 3.6f
        private const val TAU = (2 * PI).toFloat()

        fun at(t: Float, still: Boolean = false): ArcFrame {
            if (still) return ArcFrame(0f, 0f, 1f, emptyList())
            val s = t % LOOP
            val b = 0.5f - 0.5f * cos(TAU * s / 1.8f)
            val blink = if ((s / LOOP) in 0.92f..0.97f) 0.15f else 1f
            // Two bubbles, half a cycle apart: each rises from the antenna, drifts, grows and fades.
            val bubbles = (0..1).map { i ->
                val p = (s / 1.8f + i * 0.5f) % 1f
                val alpha = minOf(1f, p / 0.15f) * minOf(1f, (1f - p) / 0.4f)
                Bubble(12f + (if (i == 0) -0.9f else 0.9f) + 0.7f * sin(TAU * p + i), 5f - 4.4f * p - b, 0.6f + 0.6f * p, alpha)
            }
            return ArcFrame(lift = -1f * b, squash = 0.03f * b, blink = blink, bubbles = bubbles)
        }
    }
}
