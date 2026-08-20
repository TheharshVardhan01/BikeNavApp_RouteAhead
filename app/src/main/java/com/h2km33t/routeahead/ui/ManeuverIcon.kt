package com.h2km33t.routeahead.ui

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import com.h2km33t.routeahead.routing.Maneuver

/**
 * Turn arrows drawn as vectors rather than bitmaps.
 *
 * These deliberately mirror the shapes the ESP32 renders, so the phone and the handlebar
 * device show the rider the same symbol - a mismatch between the two is worse than a
 * plain arrow on both.
 */
@Composable
fun ManeuverIcon(
    maneuver: Maneuver,
    modifier: Modifier = Modifier,
    color: Color = Color.White
) {
    Canvas(modifier = modifier) {
        val s = size.minDimension
        val stroke = Stroke(
            width = s * 0.11f,
            cap = StrokeCap.Round,
            join = StrokeJoin.Round
        )
        drawManeuver(maneuver, s, color, stroke)
    }
}

private fun DrawScope.drawManeuver(
    maneuver: Maneuver,
    s: Float,
    color: Color,
    stroke: Stroke
) {
    val cx = size.width / 2f
    val cy = size.height / 2f

    // Everything is drawn in a normalised box centred on the canvas so the same helpers
    // work at notification size and at full-screen navigation size.
    fun p(x: Float, y: Float) = Offset(cx + x * s, cy + y * s)

    /** Filled triangular arrowhead of [len], pointing along [angleDeg] (0 = up). */
    fun head(at: Offset, angleDeg: Float, len: Float = 0.17f) {
        rotate(angleDeg, at) {
            val h = len * s
            val w = h * 0.85f
            val path = Path().apply {
                moveTo(at.x, at.y - h)
                lineTo(at.x - w / 2f, at.y + h * 0.35f)
                lineTo(at.x + w / 2f, at.y + h * 0.35f)
                close()
            }
            drawPath(path, color)
        }
    }

    // Two overloads rather than a vararg: Offset is a value class, and Kotlin prohibits
    // those as vararg parameter types. Every call site here needs two or three points.
    fun line(a: Offset, b: Offset) {
        drawLine(color, a, b, strokeWidth = stroke.width, cap = stroke.cap)
    }

    fun line(a: Offset, b: Offset, c: Offset) {
        // A path rather than two drawLine calls, so the bend gets a proper joint
        // instead of a notch on the outside of the corner.
        val path = Path().apply {
            moveTo(a.x, a.y)
            lineTo(b.x, b.y)
            lineTo(c.x, c.y)
        }
        drawPath(path, color, style = stroke)
    }

    /** Stem that rises, then bends left or right. [sign] is -1 for left, +1 for right. */
    fun turn(sign: Float, sharpness: Float) {
        // sharpness 0 = slight, 1 = normal, 2 = sharp. Controls how far up the stem the
        // bend happens and how far across the arm reaches.
        val bendY = when {
            sharpness < 1f -> -0.02f
            sharpness < 2f -> -0.10f
            else -> -0.18f
        }
        val armX = 0.30f * sign
        val armY = when {
            sharpness < 1f -> -0.26f
            sharpness < 2f -> -0.20f
            else -> -0.06f
        }
        line(p(0f, 0.34f), p(0f, bendY), p(armX, armY))
        val angle = Math.toDegrees(
            kotlin.math.atan2((armX - 0f).toDouble(), (bendY - armY).toDouble())
        ).toFloat()
        head(p(armX, armY), angle)
    }

    when (maneuver) {
        Maneuver.STRAIGHT, Maneuver.DEPART, Maneuver.MERGE -> {
            line(p(0f, 0.34f), p(0f, -0.16f))
            head(p(0f, -0.20f), 0f)
        }

        Maneuver.LEFT -> turn(-1f, 1f)
        Maneuver.RIGHT -> turn(1f, 1f)
        Maneuver.SLIGHT_LEFT -> turn(-1f, 0f)
        Maneuver.SLIGHT_RIGHT -> turn(1f, 0f)
        Maneuver.SHARP_LEFT -> turn(-1f, 2f)
        Maneuver.SHARP_RIGHT -> turn(1f, 2f)

        Maneuver.UTURN -> {
            // Up the left side, over the top, back down the right.
            val path = Path().apply {
                moveTo(p(-0.14f, 0.34f).x, p(-0.14f, 0.34f).y)
                lineTo(p(-0.14f, -0.06f).x, p(-0.14f, -0.06f).y)
                quadraticTo(
                    p(-0.14f, -0.30f).x, p(-0.14f, -0.30f).y,
                    p(0.14f, -0.20f).x, p(0.14f, -0.20f).y
                )
                lineTo(p(0.14f, 0.02f).x, p(0.14f, 0.02f).y)
            }
            drawPath(path, color, style = stroke)
            head(p(0.14f, 0.10f), 180f)
        }

        Maneuver.ROUNDABOUT -> {
            val r = s * 0.19f
            drawCircle(color, r, Offset(cx, cy - s * 0.04f), style = stroke)
            // Entry from the bottom, exit to the upper right - the common case, and the
            // exit number is shown as text next to the icon anyway.
            line(p(0f, 0.38f), p(0f, 0.14f))
            line(p(0.17f, -0.18f), p(0.30f, -0.30f))
            head(p(0.32f, -0.32f), 45f)
        }

        Maneuver.FORK_LEFT, Maneuver.FORK_RIGHT, Maneuver.ON_RAMP, Maneuver.OFF_RAMP -> {
            val sign = if (maneuver == Maneuver.FORK_LEFT) -1f else 1f
            line(p(0f, 0.34f), p(0f, 0.06f))
            line(p(0f, 0.06f), p(0.22f * -sign, -0.16f))     // the road you don't take
            line(p(0f, 0.06f), p(0.22f * sign, -0.20f))      // the one you do
            head(p(0.24f * sign, -0.24f), 40f * sign)
        }

        Maneuver.ARRIVE -> {
            // Map pin: circle over a point.
            val r = s * 0.15f
            drawCircle(color, r, Offset(cx, cy - s * 0.10f), style = stroke)
            line(p(0f, 0.06f), p(0f, 0.30f))
            drawCircle(color, s * 0.045f, Offset(cx, cy + s * 0.32f))
        }
    }
}
