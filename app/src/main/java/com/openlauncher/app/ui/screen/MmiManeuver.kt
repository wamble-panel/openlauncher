package com.openlauncher.app.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.material3.Text
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openlauncher.app.map.ManeuverType
import com.openlauncher.app.map.NavState
import com.openlauncher.app.map.formatDistance
import java.util.Calendar
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Turn arrow in the MMI style: a thick white road arrow, roundabouts drawn as a
 * ring with the exit taken (traffic drives counter-clockwise, as in Egypt).
 */
@Composable
internal fun ManeuverGlyph(type: ManeuverType, exit: Int, color: Color, modifier: Modifier) {
    if (type == ManeuverType.ARRIVE) {
        MmiLineIcon(MmiLine.FLAG, color, modifier)
        return
    }
    Spacer(
        modifier.drawWithCache {
            val s = size.minDimension
            val ox = (size.width - s) / 2f
            val oy = (size.height - s) / 2f
            val width = s * 0.12f
            val stroke = Stroke(width, cap = StrokeCap.Butt, join = StrokeJoin.Round)
            val ring = Stroke(s * 0.05f)
            val shapes = maneuverShapes(type, exit, s)
            onDrawBehind {
                translate(ox, oy) {
                    shapes.ring?.let { drawPath(it, color.copy(alpha = 0.35f), style = ring) }
                    drawPath(shapes.line, color, style = stroke)
                    drawPath(shapes.head, color)
                }
            }
        }
    )
}

private class ManeuverShapes(val line: Path, val head: Path, val ring: Path?)

/** Point at compass angle [deg] (0 = up, 90 = right) from (cx, cy). */
private fun polar(cx: Float, cy: Float, r: Float, deg: Float): Offset {
    val a = Math.toRadians(deg.toDouble())
    return Offset(cx + r * sin(a).toFloat(), cy - r * cos(a).toFloat())
}

private fun arrowHead(tip: Offset, deg: Float, s: Float): Path {
    val base = polar(tip.x, tip.y, s * 0.15f, deg + 180f)
    val l = polar(base.x, base.y, s * 0.13f, deg - 90f)
    val r = polar(base.x, base.y, s * 0.13f, deg + 90f)
    return Path().apply { moveTo(tip.x, tip.y); lineTo(l.x, l.y); lineTo(r.x, r.y); close() }
}

private fun maneuverShapes(type: ManeuverType, exit: Int, s: Float): ManeuverShapes {
    val cx = s * 0.5f
    when (type) {
        ManeuverType.UTURN -> {
            val line = Path().apply {
                moveTo(s * 0.64f, s * 0.94f)
                lineTo(s * 0.64f, s * 0.42f)
                arcTo(Rect(s * 0.32f, s * 0.26f, s * 0.64f, s * 0.58f), 0f, -180f, false)
                lineTo(s * 0.32f, s * 0.56f)
            }
            return ManeuverShapes(line, arrowHead(Offset(s * 0.32f, s * 0.72f), 180f, s), null)
        }
        ManeuverType.ROUNDABOUT -> {
            val cy = s * 0.44f
            val r = s * 0.20f
            val exitDeg = when (exit) {
                1 -> 75f
                2 -> 0f
                3 -> -75f
                4 -> -140f
                else -> -165f
            }
            val ringPath = Path().apply { addOval(Rect(cx - r, cy - r, cx + r, cy + r)) }
            val out = polar(cx, cy, r, exitDeg)
            val tip = polar(cx, cy, s * 0.46f, exitDeg)
            val end = polar(cx, cy, s * 0.46f - s * 0.13f, exitDeg)
            // Screen arc angles: 0 = right, clockwise; compass θ maps to θ - 90.
            val startScreen = 90f
            val sweep = (exitDeg - 90f) - startScreen
            val line = Path().apply {
                moveTo(cx, s * 0.96f)
                lineTo(cx, cy + r)
                arcTo(Rect(cx - r, cy - r, cx + r, cy + r), startScreen, sweep, false)
                lineTo(out.x, out.y)
                lineTo(end.x, end.y)
            }
            return ManeuverShapes(line, arrowHead(tip, exitDeg, s), ringPath)
        }
        else -> {
            val deg = when (type) {
                ManeuverType.SLIGHT_LEFT -> -40f
                ManeuverType.SLIGHT_RIGHT -> 40f
                ManeuverType.KEEP_LEFT -> -28f
                ManeuverType.KEEP_RIGHT -> 28f
                ManeuverType.LEFT -> -90f
                ManeuverType.RIGHT -> 90f
                ManeuverType.SHARP_LEFT -> -135f
                ManeuverType.SHARP_RIGHT -> 135f
                else -> 0f
            }
            val joint = Offset(cx, s * 0.56f)
            val reach = if (kotlin.math.abs(deg) > 100f) s * 0.30f else s * 0.38f
            val tip = polar(joint.x, joint.y, reach, deg)
            val end = polar(joint.x, joint.y, reach - s * 0.13f, deg)
            val bend = polar(joint.x, joint.y, s * 0.08f, deg)
            val line = Path().apply {
                moveTo(cx, s * 0.96f)
                lineTo(cx, joint.y + s * 0.08f)
                quadraticTo(joint.x, joint.y, bend.x, bend.y)
                lineTo(end.x, end.y)
            }
            val ring = if (type == ManeuverType.KEEP_LEFT || type == ManeuverType.KEEP_RIGHT) {
                // The branch not taken, faint.
                val other = polar(joint.x, joint.y, s * 0.34f, -deg)
                Path().apply { moveTo(joint.x, joint.y); lineTo(other.x, other.y) }
            } else null
            return ManeuverShapes(line, arrowHead(tip, deg, s), ring)
        }
    }
}

/** Round speed-limit sign: white disc, red ring, black number. */
@Composable
internal fun SpeedLimitSign(limit: Int, modifier: Modifier) {
    Box(
        modifier.clip(CircleShape).background(Color.White).border(7.dp, Color(0xFFD32F2F), CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            "$limit", color = Color.Black, fontFamily = MmiFont, fontWeight = FontWeight.Bold,
            fontSize = if (limit >= 100) 20.sp else 24.sp,
        )
    }
}

internal fun formatDuration(seconds: Double): String {
    val min = (seconds / 60.0).roundToInt().coerceAtLeast(1)
    return if (min < 60) "$min min" else "${min / 60} h ${"%02d".format(min % 60)}"
}

internal fun formatEta(seconds: Double): String {
    val c = Calendar.getInstance()
    c.add(Calendar.SECOND, seconds.roundToInt())
    return "%02d:%02d".format(c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE))
}

/** Home-screen card for our own guidance (replaces the weather card while navigating). */
@Composable
internal fun MmiRouteCard(nav: NavState, metric: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Column(modifier.pressable(onClick), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Column(
            Modifier.fillMaxWidth().weight(1f).mmiPanel().padding(14.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceEvenly,
        ) {
            Text("NAVIGATION", color = MmiCyan, fontFamily = MmiFont, fontSize = 12.sp, letterSpacing = 3.sp)
            val next = nav.next
            when {
                nav.arrived -> {
                    ManeuverGlyph(ManeuverType.ARRIVE, 0, MmiInk, Modifier.fillMaxWidth(0.4f).aspectRatio(1f))
                    CardText("Arrived", nav.destination?.title)
                }
                next != null -> {
                    ManeuverGlyph(next.type, next.exit, MmiInk, Modifier.fillMaxWidth(0.45f).aspectRatio(1f))
                    CardText(formatDistance(nav.distanceToNextM, metric), next.road.display.ifEmpty { next.text })
                }
                else -> {
                    ManeuverGlyph(ManeuverType.STRAIGHT, 0, MmiInk, Modifier.fillMaxWidth(0.4f).aspectRatio(1f))
                    CardText(if (nav.calculating) "Calculating…" else "Guidance", nav.destination?.title)
                }
            }
        }
        Row(
            Modifier.fillMaxWidth().height(48.dp).mmiPanel().padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val text = if (nav.route != null && !nav.arrived) {
                "${formatDistance(nav.remainingM, metric)} · ${formatDuration(nav.remainingS)} · ${formatEta(nav.remainingS)}"
            } else nav.destination?.title ?: ""
            Text(text, color = MmiInk, fontFamily = MmiFont, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun CardText(big: String, small: String?) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(big, color = MmiInk, fontFamily = MmiFont, fontSize = 30.sp, maxLines = 1)
        if (!small.isNullOrEmpty()) {
            Text(
                small, color = MmiDim, fontFamily = MmiFont, fontSize = 16.sp, maxLines = 2,
                textAlign = TextAlign.Center, overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
