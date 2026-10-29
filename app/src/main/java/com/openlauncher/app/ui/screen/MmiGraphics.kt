package com.openlauncher.app.ui.screen

import androidx.compose.foundation.layout.Spacer
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathOperation
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import kotlin.math.cos
import kotlin.math.sin

// Hand-drawn chrome glyphs and weather art for the MMI home. Everything is vector
// paths built once per size (drawWithCache), with a dark extrusion offset behind
// the chrome face to give the chunky 3D look of the MMI menu icons.

enum class MmiGlyph { GAUGE, REEL, GEARS, NOTE, HANDSET, FLAG, APPS, RADIO }

private val ChromeStops = listOf(
    Color(0xFFF8FAFC), Color(0xFFC3C9D0), Color(0xFF79808A), Color(0xFFDDE2E7), Color(0xFF4E545C),
)
private val Depth = Color(0xFF15171A)

private class Layer(
    val path: Path,
    val brush: Brush? = null,   // null = chrome
    val stroke: Float = 0f,     // 0 = fill
    val extrude: Boolean = true,
)

private fun DrawScope.drawLayer(layer: Layer, brush: Brush) {
    if (layer.stroke > 0f) {
        drawPath(layer.path, brush, style = Stroke(layer.stroke, cap = StrokeCap.Round, join = StrokeJoin.Round))
    } else {
        drawPath(layer.path, brush)
    }
}

@Composable
fun MmiIcon(glyph: MmiGlyph, modifier: Modifier) {
    Spacer(
        modifier.drawWithCache {
            val s = size.minDimension
            val ox = (size.width - s) / 2f
            val oy = (size.height - s) / 2f
            val chrome = Brush.linearGradient(ChromeStops, start = Offset(ox, oy), end = Offset(ox + s, oy + s))
            val depthBrush = SolidColor(Depth)
            val layers = buildGlyph(glyph, s)
            val dx = s * 0.03f
            val dy = s * 0.05f
            onDrawBehind {
                translate(ox, oy) {
                    translate(dx, dy) {
                        layers.forEach { if (it.extrude) drawLayer(it, depthBrush) }
                    }
                    layers.forEach { drawLayer(it, it.brush ?: chrome) }
                }
            }
        }
    )
}

private fun circle(cx: Float, cy: Float, r: Float, s: Float) =
    Path().apply { addOval(Rect(Offset(cx * s, cy * s), r * s)) }

private fun rect(l: Float, t: Float, r: Float, b: Float, s: Float) =
    Path().apply { addRect(Rect(l * s, t * s, r * s, b * s)) }

private fun poly(s: Float, vararg pts: Pair<Float, Float>) = Path().apply {
    pts.forEachIndexed { i, (x, y) -> if (i == 0) moveTo(x * s, y * s) else lineTo(x * s, y * s) }
    close()
}

private fun gear(cx: Float, cy: Float, rOut: Float, rIn: Float, teeth: Int, s: Float) = Path().apply {
    val step = (2 * Math.PI / teeth).toFloat()
    fun pt(r: Float, a: Float) = Offset((cx + r * cos(a)) * s, (cy + r * sin(a)) * s)
    for (i in 0 until teeth) {
        val base = i * step
        val p0 = pt(rIn, base)
        if (i == 0) moveTo(p0.x, p0.y) else lineTo(p0.x, p0.y)
        pt(rOut, base + step * 0.14f).let { lineTo(it.x, it.y) }
        pt(rOut, base + step * 0.40f).let { lineTo(it.x, it.y) }
        pt(rIn, base + step * 0.54f).let { lineTo(it.x, it.y) }
    }
    close()
}

private fun darkHole(s: Float, cx: Float, cy: Float, r: Float) = Brush.radialGradient(
    listOf(Color(0xFF34383E), Color(0xFF08090A)),
    center = Offset(cx * s, (cy - r * 0.3f) * s),
    radius = r * s * 1.2f,
)

private fun buildGlyph(glyph: MmiGlyph, s: Float): List<Layer> = when (glyph) {
    MmiGlyph.GAUGE -> {
        val ticks = Path()
        var a = -225.0
        while (a <= 45.0) {
            val rad = Math.toRadians(a)
            ticks.moveTo((0.5f + 0.29f * cos(rad).toFloat()) * s, (0.5f + 0.29f * sin(rad).toFloat()) * s)
            ticks.lineTo((0.5f + 0.35f * cos(rad).toFloat()) * s, (0.5f + 0.35f * sin(rad).toFloat()) * s)
            a += 30.0
        }
        val n = Math.toRadians(-55.0)
        val dir = Offset(cos(n).toFloat(), sin(n).toFloat())
        val perp = Offset(-dir.y, dir.x)
        val needle = Path().apply {
            moveTo((0.5f + dir.x * 0.31f) * s, (0.5f + dir.y * 0.31f) * s)
            lineTo((0.5f + perp.x * 0.03f) * s, (0.5f + perp.y * 0.03f) * s)
            lineTo((0.5f - perp.x * 0.03f) * s, (0.5f - perp.y * 0.03f) * s)
            close()
        }
        listOf(
            Layer(circle(0.5f, 0.5f, 0.47f, s)),
            Layer(circle(0.5f, 0.5f, 0.39f, s), darkHole(s, 0.5f, 0.5f, 0.39f), extrude = false),
            Layer(ticks, SolidColor(Color(0xFFE9ECEF)), stroke = s * 0.028f, extrude = false),
            Layer(needle, SolidColor(Color(0xFFE53935)), extrude = false),
            Layer(circle(0.5f, 0.5f, 0.065f, s), extrude = false),
        )
    }
    MmiGlyph.REEL -> {
        val holes = Path()
        for (i in 0 until 5) {
            val a = Math.toRadians(-90.0 + i * 72.0)
            holes.addOval(Rect(Offset((0.5f + 0.24f * cos(a).toFloat()) * s, (0.5f + 0.24f * sin(a).toFloat()) * s), 0.105f * s))
        }
        listOf(
            Layer(circle(0.5f, 0.5f, 0.46f, s)),
            Layer(holes, darkHole(s, 0.5f, 0.5f, 0.45f), extrude = false),
            Layer(circle(0.5f, 0.5f, 0.07f, s), darkHole(s, 0.5f, 0.5f, 0.07f), extrude = false),
            Layer(circle(0.5f, 0.5f, 0.033f, s), extrude = false),
        )
    }
    MmiGlyph.GEARS -> listOf(
        Layer(gear(0.40f, 0.58f, 0.36f, 0.28f, 10, s)),
        Layer(circle(0.40f, 0.58f, 0.15f, s), darkHole(s, 0.40f, 0.58f, 0.15f), extrude = false),
        Layer(circle(0.40f, 0.58f, 0.075f, s), extrude = false),
        Layer(gear(0.79f, 0.21f, 0.20f, 0.15f, 8, s)),
        Layer(circle(0.79f, 0.21f, 0.075f, s), darkHole(s, 0.79f, 0.21f, 0.075f), extrude = false),
    )
    MmiGlyph.NOTE -> {
        val note = Path().apply {
            addOval(Rect(0.12f * s, 0.69f * s, 0.44f * s, 0.92f * s))
            addOval(Rect(0.58f * s, 0.59f * s, 0.90f * s, 0.82f * s))
            addRect(Rect(0.37f * s, 0.14f * s, 0.44f * s, 0.80f * s))
            addRect(Rect(0.83f * s, 0.05f * s, 0.90f * s, 0.70f * s))
        }
        listOf(
            Layer(note),
            Layer(poly(s, 0.37f to 0.12f, 0.90f to 0.03f, 0.90f to 0.18f, 0.37f to 0.27f)),
        )
    }
    MmiGlyph.HANDSET -> {
        val bar = Path().apply {
            moveTo(0.22f * s, 0.16f * s)
            cubicTo(0.02f * s, 0.48f * s, 0.40f * s, 0.94f * s, 0.86f * s, 0.84f * s)
        }
        listOf(
            Layer(bar, stroke = s * 0.17f),
            Layer(Path().apply {
                addOval(Rect(Offset(0.22f * s, 0.15f * s), 0.13f * s))
                addOval(Rect(Offset(0.85f * s, 0.84f * s), 0.13f * s))
            }),
        )
    }
    MmiGlyph.FLAG -> {
        val cols = 6
        val rows = 4
        val l = 0.18f; val t = 0.08f; val r = 0.94f; val b = 0.56f
        val cw = (r - l) / cols
        val ch = (b - t) / rows
        val white = Path()
        for (row in 0 until rows) for (col in 0 until cols) {
            if ((row + col) % 2 == 0) white.addRect(Rect((l + col * cw) * s, (t + row * ch) * s, (l + (col + 1) * cw) * s, (t + (row + 1) * ch) * s))
        }
        listOf(
            Layer(rect(0.10f, 0.06f, 0.17f, 0.98f, s)),
            Layer(circle(0.135f, 0.05f, 0.05f, s)),
            Layer(rect(l, t, r, b, s), SolidColor(Color(0xFF111214))),
            Layer(white, Brush.verticalGradient(listOf(Color(0xFFFFFFFF), Color(0xFFC8CDD3)), startY = t * s, endY = b * s), extrude = false),
        )
    }
    MmiGlyph.RADIO -> {
        val top = Offset(0.5f * s, 0.29f * s)
        val waves = Path().apply {
            for (r in listOf(0.18f, 0.31f)) {
                arcTo(Rect(top, r * s), 205f, 55f, true)
                arcTo(Rect(top, r * s), -80f, 55f, true)
            }
        }
        listOf(
            Layer(poly(s, 0.45f to 0.34f, 0.55f to 0.34f, 0.66f to 0.96f, 0.34f to 0.96f)),
            Layer(circle(0.5f, 0.29f, 0.075f, s)),
            Layer(waves, stroke = s * 0.06f),
        )
    }
    MmiGlyph.APPS -> {
        fun sq(x: Float, y: Float) = Path().apply {
            addRoundRect(RoundRect(Rect(x * s, y * s, (x + 0.38f) * s, (y + 0.38f) * s), CornerRadius(0.07f * s)))
        }
        listOf(
            Layer(sq(0.06f, 0.06f), stroke = s * 0.075f),
            Layer(sq(0.56f, 0.06f)),
            Layer(sq(0.06f, 0.56f), stroke = s * 0.075f),
            Layer(sq(0.56f, 0.56f), stroke = s * 0.075f),
        )
    }
}

// ── Weather art ──────────────────────────────────────────────────────────────

private enum class Sky { CLEAR, PARTLY, CLOUDY, FOG, RAIN, SNOW, STORM, NONE }

private fun skyFor(code: Int?): Sky = when (code) {
    null -> Sky.NONE
    0 -> Sky.CLEAR
    1, 2 -> Sky.PARTLY
    3 -> Sky.CLOUDY
    45, 48 -> Sky.FOG
    in 51..67, in 80..82 -> Sky.RAIN
    in 71..77, 85, 86 -> Sky.SNOW
    in 95..99 -> Sky.STORM
    else -> Sky.CLOUDY
}

@Composable
fun WeatherGlyph(code: Int?, isDay: Boolean, modifier: Modifier) {
    Spacer(
        modifier.drawWithCache {
            val s = size.minDimension
            val ox = (size.width - s) / 2f
            val oy = (size.height - s) / 2f
            val sky = skyFor(code)
            onDrawBehind {
                translate(ox, oy) {
                    when (sky) {
                        Sky.CLEAR -> if (isDay) sun(Offset(0.5f * s, 0.5f * s), 0.24f * s) else moon(Offset(0.5f * s, 0.5f * s), 0.26f * s)
                        Sky.PARTLY -> {
                            if (isDay) sun(Offset(0.64f * s, 0.34f * s), 0.17f * s) else moon(Offset(0.66f * s, 0.32f * s), 0.18f * s)
                            cloud(s, 1f)
                        }
                        Sky.CLOUDY -> cloud(s, 1f)
                        Sky.NONE -> cloud(s, 0.35f)
                        Sky.FOG -> {
                            cloud(s, 1f)
                            for (i in 0..1) {
                                val y = (0.88f + i * 0.08f) * s
                                drawLine(Color(0xFFB0BAC5), Offset(0.24f * s, y), Offset(0.80f * s, y), 0.04f * s, StrokeCap.Round)
                            }
                        }
                        Sky.RAIN -> {
                            cloud(s, 1f)
                            for (i in 0..2) {
                                val x = (0.38f + i * 0.16f) * s
                                drawLine(Color(0xFF4FC3F7), Offset(x, 0.86f * s), Offset(x - 0.04f * s, 0.98f * s), 0.045f * s, StrokeCap.Round)
                            }
                        }
                        Sky.SNOW -> {
                            cloud(s, 1f)
                            for (i in 0..2) drawCircle(Color.White, 0.04f * s, Offset((0.38f + i * 0.16f) * s, 0.92f * s))
                        }
                        Sky.STORM -> {
                            cloud(s, 1f)
                            drawPath(
                                poly(s, 0.56f to 0.72f, 0.44f to 0.90f, 0.53f to 0.90f, 0.46f to 1.02f, 0.64f to 0.84f, 0.55f to 0.84f, 0.61f to 0.72f),
                                Color(0xFFFFD54F),
                            )
                        }
                    }
                }
            }
        }
    )
}

private fun DrawScope.sun(c: Offset, r: Float) {
    for (i in 0 until 8) {
        val a = Math.toRadians(i * 45.0)
        val d = Offset(cos(a).toFloat(), sin(a).toFloat())
        drawLine(Color(0xFFFFC107), c + d * (r * 1.3f), c + d * (r * 1.65f), r * 0.2f, StrokeCap.Round)
    }
    drawCircle(Brush.radialGradient(listOf(Color(0xFFFFF3B0), Color(0xFFFFB300)), center = c, radius = r), r, c)
}

private fun DrawScope.moon(c: Offset, r: Float) {
    val crescent = Path().apply {
        op(
            Path().apply { addOval(Rect(c, r)) },
            Path().apply { addOval(Rect(c + Offset(r * 0.5f, -r * 0.3f), r * 0.85f)) },
            PathOperation.Difference,
        )
    }
    drawPath(crescent, Brush.radialGradient(listOf(Color(0xFFFFFDF0), Color(0xFFD9DEE6)), center = c, radius = r))
}

private fun DrawScope.cloud(s: Float, alpha: Float) {
    drawOval(
        Color.Black.copy(alpha = 0.3f * alpha),
        topLeft = Offset(0.22f * s, 0.80f * s), size = Size(0.62f * s, 0.07f * s),
    )
    val brush = Brush.verticalGradient(
        listOf(Color(0xFFFFFFFF), Color(0xFFC5D0DC)),
        startY = 0.30f * s, endY = 0.80f * s,
    )
    drawCircle(brush, 0.17f * s, Offset(0.33f * s, 0.60f * s), alpha = alpha)
    drawCircle(brush, 0.24f * s, Offset(0.54f * s, 0.52f * s), alpha = alpha)
    drawCircle(brush, 0.17f * s, Offset(0.75f * s, 0.60f * s), alpha = alpha)
    drawRoundRect(
        brush, topLeft = Offset(0.18f * s, 0.58f * s), size = Size(0.74f * s, 0.20f * s),
        cornerRadius = CornerRadius(0.10f * s), alpha = alpha,
    )
}

// ── Line icons (white UI glyphs) ─────────────────────────────────────────────

enum class MmiLine {
    WIND, THERMO, DROP, CHEVRON_LEFT, ARROW, MENU, PREV, NEXT, PLAY, PAUSE,
    CALL, BACKSPACE, OPEN, WIDGETS, BLUETOOTH, CONTACTS, NOTE,
    PLUS, MINUS, LOCATE, SEARCH, STAR, STAR_FILLED, HISTORY, PIN, FUEL, CLOSE, HOME, FLAG, CARD, GLOBE,
}

@Composable
fun MmiLineIcon(kind: MmiLine, color: Color, modifier: Modifier) {
    Spacer(
        modifier.drawWithCache {
            val s = size.minDimension
            val ox = (size.width - s) / 2f
            val oy = (size.height - s) / 2f
            val stroke = Stroke(s * 0.085f, cap = StrokeCap.Round, join = StrokeJoin.Round)
            val (strokes, fills) = linePaths(kind, s)
            onDrawBehind {
                translate(ox, oy) {
                    strokes.forEach { drawPath(it, color, style = stroke) }
                    fills.forEach { drawPath(it, color) }
                }
            }
        }
    )
}

private fun path(s: Float, block: Path.(Float) -> Unit) = Path().apply { block(s) }

private fun linePaths(kind: MmiLine, s: Float): Pair<List<Path>, List<Path>> = when (kind) {
    MmiLine.WIND -> listOf(
        path(s) { moveTo(0.10f * it, 0.36f * it); lineTo(0.62f * it, 0.36f * it); cubicTo(0.84f * it, 0.36f * it, 0.84f * it, 0.10f * it, 0.64f * it, 0.14f * it) },
        path(s) { moveTo(0.10f * it, 0.56f * it); lineTo(0.76f * it, 0.56f * it); cubicTo(0.98f * it, 0.56f * it, 0.98f * it, 0.84f * it, 0.78f * it, 0.82f * it) },
        path(s) { moveTo(0.10f * it, 0.76f * it); lineTo(0.44f * it, 0.76f * it) },
    ) to emptyList<Path>()
    MmiLine.THERMO -> listOf(
        path(s) { addRoundRect(RoundRect(Rect(0.40f * it, 0.06f * it, 0.60f * it, 0.66f * it), CornerRadius(0.10f * it))) },
    ) to listOf(circle(0.5f, 0.78f, 0.15f, s), rect(0.465f, 0.30f, 0.535f, 0.72f, s))
    MmiLine.DROP -> emptyList<Path>() to listOf(path(s) {
        moveTo(0.5f * it, 0.06f * it)
        cubicTo(0.62f * it, 0.24f * it, 0.82f * it, 0.46f * it, 0.82f * it, 0.64f * it)
        cubicTo(0.82f * it, 0.82f * it, 0.68f * it, 0.94f * it, 0.5f * it, 0.94f * it)
        cubicTo(0.32f * it, 0.94f * it, 0.18f * it, 0.82f * it, 0.18f * it, 0.64f * it)
        cubicTo(0.18f * it, 0.46f * it, 0.38f * it, 0.24f * it, 0.5f * it, 0.06f * it)
        close()
    })
    MmiLine.CHEVRON_LEFT -> listOf(path(s) { moveTo(0.64f * it, 0.14f * it); lineTo(0.30f * it, 0.5f * it); lineTo(0.64f * it, 0.86f * it) }) to emptyList<Path>()
    MmiLine.ARROW -> emptyList<Path>() to listOf(poly(s, 0.28f to 0.14f, 0.84f to 0.5f, 0.28f to 0.86f))
    MmiLine.MENU -> listOf(0.30f, 0.50f, 0.70f).map { y -> path(s) { moveTo(0.20f * it, y * it); lineTo(0.80f * it, y * it) } } to emptyList<Path>()
    MmiLine.PREV -> emptyList<Path>() to listOf(poly(s, 0.86f to 0.18f, 0.36f to 0.5f, 0.86f to 0.82f), rect(0.14f, 0.18f, 0.27f, 0.82f, s))
    MmiLine.NEXT -> emptyList<Path>() to listOf(poly(s, 0.14f to 0.18f, 0.64f to 0.5f, 0.14f to 0.82f), rect(0.73f, 0.18f, 0.86f, 0.82f, s))
    MmiLine.PLAY -> emptyList<Path>() to listOf(poly(s, 0.26f to 0.12f, 0.88f to 0.5f, 0.26f to 0.88f))
    MmiLine.PAUSE -> emptyList<Path>() to listOf(rect(0.22f, 0.14f, 0.42f, 0.86f, s), rect(0.58f, 0.14f, 0.78f, 0.86f, s))
    MmiLine.CALL -> listOf(path(s) { moveTo(0.26f * it, 0.20f * it); cubicTo(0.10f * it, 0.50f * it, 0.44f * it, 0.92f * it, 0.82f * it, 0.80f * it) }) to
        listOf(circle(0.26f, 0.20f, 0.13f, s), circle(0.80f, 0.80f, 0.13f, s))
    MmiLine.BACKSPACE -> listOf(
        path(s) { moveTo(0.36f * it, 0.22f * it); lineTo(0.90f * it, 0.22f * it); lineTo(0.90f * it, 0.78f * it); lineTo(0.36f * it, 0.78f * it); lineTo(0.10f * it, 0.5f * it); close() },
        path(s) { moveTo(0.50f * it, 0.38f * it); lineTo(0.72f * it, 0.62f * it); moveTo(0.72f * it, 0.38f * it); lineTo(0.50f * it, 0.62f * it) },
    ) to emptyList<Path>()
    MmiLine.OPEN -> listOf(
        path(s) { moveTo(0.52f * it, 0.14f * it); lineTo(0.14f * it, 0.14f * it); lineTo(0.14f * it, 0.86f * it); lineTo(0.86f * it, 0.86f * it); lineTo(0.86f * it, 0.48f * it) },
        path(s) { moveTo(0.46f * it, 0.54f * it); lineTo(0.88f * it, 0.12f * it); moveTo(0.62f * it, 0.12f * it); lineTo(0.88f * it, 0.12f * it); lineTo(0.88f * it, 0.38f * it) },
    ) to emptyList<Path>()
    MmiLine.WIDGETS -> listOf(0.10f to 0.10f, 0.56f to 0.10f, 0.10f to 0.56f, 0.56f to 0.56f).map { (x, y) ->
        path(s) { addRoundRect(RoundRect(Rect(x * it, y * it, (x + 0.34f) * it, (y + 0.34f) * it), CornerRadius(0.06f * it))) }
    } to emptyList<Path>()
    MmiLine.BLUETOOTH -> listOf(path(s) {
        moveTo(0.28f * it, 0.30f * it); lineTo(0.70f * it, 0.68f * it); lineTo(0.50f * it, 0.88f * it)
        lineTo(0.50f * it, 0.12f * it); lineTo(0.70f * it, 0.32f * it); lineTo(0.28f * it, 0.70f * it)
    }) to emptyList<Path>()
    MmiLine.CONTACTS -> emptyList<Path>() to listOf(
        circle(0.5f, 0.32f, 0.17f, s),
        path(s) { moveTo(0.16f * it, 0.92f * it); cubicTo(0.16f * it, 0.58f * it, 0.84f * it, 0.58f * it, 0.84f * it, 0.92f * it); close() },
    )
    MmiLine.PLUS -> listOf(path(s) { moveTo(0.5f * it, 0.18f * it); lineTo(0.5f * it, 0.82f * it); moveTo(0.18f * it, 0.5f * it); lineTo(0.82f * it, 0.5f * it) }) to emptyList<Path>()
    MmiLine.MINUS -> listOf(path(s) { moveTo(0.18f * it, 0.5f * it); lineTo(0.82f * it, 0.5f * it) }) to emptyList<Path>()
    MmiLine.CLOSE -> listOf(path(s) { moveTo(0.22f * it, 0.22f * it); lineTo(0.78f * it, 0.78f * it); moveTo(0.78f * it, 0.22f * it); lineTo(0.22f * it, 0.78f * it) }) to emptyList<Path>()
    MmiLine.LOCATE -> listOf(
        circle(0.5f, 0.5f, 0.26f, s),
        path(s) {
            moveTo(0.5f * it, 0.06f * it); lineTo(0.5f * it, 0.22f * it); moveTo(0.5f * it, 0.78f * it); lineTo(0.5f * it, 0.94f * it)
            moveTo(0.06f * it, 0.5f * it); lineTo(0.22f * it, 0.5f * it); moveTo(0.78f * it, 0.5f * it); lineTo(0.94f * it, 0.5f * it)
        },
    ) to listOf(circle(0.5f, 0.5f, 0.09f, s))
    MmiLine.SEARCH -> listOf(circle(0.42f, 0.42f, 0.26f, s), path(s) { moveTo(0.61f * it, 0.61f * it); lineTo(0.88f * it, 0.88f * it) }) to emptyList<Path>()
    MmiLine.STAR, MmiLine.STAR_FILLED -> {
        val star = Path().apply {
            for (i in 0 until 10) {
                val a = Math.toRadians(-90.0 + i * 36.0)
                val r = if (i % 2 == 0) 0.44f else 0.19f
                val x = (0.5f + r * cos(a).toFloat()) * s
                val y = (0.53f + r * sin(a).toFloat()) * s
                if (i == 0) moveTo(x, y) else lineTo(x, y)
            }
            close()
        }
        if (kind == MmiLine.STAR) listOf(star) to emptyList<Path>() else emptyList<Path>() to listOf(star)
    }
    MmiLine.HISTORY -> listOf(
        path(s) { arcTo(Rect(Offset(0.5f * it, 0.5f * it), 0.38f * it), 200f, 300f, true) },
        path(s) { moveTo(0.5f * it, 0.3f * it); lineTo(0.5f * it, 0.52f * it); lineTo(0.66f * it, 0.62f * it) },
        path(s) { moveTo(0.06f * it, 0.30f * it); lineTo(0.15f * it, 0.43f * it); lineTo(0.28f * it, 0.33f * it) },
    ) to emptyList<Path>()
    MmiLine.PIN -> listOf(path(s) {
        moveTo(0.5f * it, 0.94f * it)
        cubicTo(0.30f * it, 0.66f * it, 0.18f * it, 0.50f * it, 0.18f * it, 0.38f * it)
        cubicTo(0.18f * it, 0.18f * it, 0.33f * it, 0.06f * it, 0.5f * it, 0.06f * it)
        cubicTo(0.67f * it, 0.06f * it, 0.82f * it, 0.18f * it, 0.82f * it, 0.38f * it)
        cubicTo(0.82f * it, 0.50f * it, 0.70f * it, 0.66f * it, 0.5f * it, 0.94f * it)
        close()
    }) to listOf(circle(0.5f, 0.37f, 0.11f, s))
    MmiLine.FUEL -> listOf(
        path(s) { addRoundRect(RoundRect(Rect(0.16f * it, 0.10f * it, 0.60f * it, 0.90f * it), CornerRadius(0.06f * it))) },
        path(s) { moveTo(0.60f * it, 0.36f * it); lineTo(0.76f * it, 0.36f * it); lineTo(0.80f * it, 0.74f * it); lineTo(0.88f * it, 0.74f * it); lineTo(0.88f * it, 0.24f * it); lineTo(0.76f * it, 0.12f * it) },
    ) to listOf(rect(0.24f, 0.18f, 0.52f, 0.40f, s))
    MmiLine.HOME -> listOf(path(s) {
        moveTo(0.10f * it, 0.48f * it); lineTo(0.5f * it, 0.12f * it); lineTo(0.90f * it, 0.48f * it)
        moveTo(0.22f * it, 0.40f * it); lineTo(0.22f * it, 0.88f * it); lineTo(0.78f * it, 0.88f * it); lineTo(0.78f * it, 0.40f * it)
    }) to emptyList<Path>()
    MmiLine.FLAG -> listOf(path(s) { moveTo(0.20f * it, 0.92f * it); lineTo(0.20f * it, 0.10f * it) }) to
        listOf(poly(s, 0.20f to 0.10f, 0.84f to 0.10f, 0.70f to 0.30f, 0.84f to 0.50f, 0.20f to 0.50f))
    MmiLine.CARD -> listOf(
        path(s) { addRoundRect(RoundRect(Rect(0.08f * it, 0.20f * it, 0.92f * it, 0.80f * it), CornerRadius(0.06f * it))) },
        path(s) { moveTo(0.52f * it, 0.40f * it); lineTo(0.80f * it, 0.40f * it); moveTo(0.52f * it, 0.60f * it); lineTo(0.72f * it, 0.60f * it) },
    ) to listOf(rect(0.18f, 0.34f, 0.42f, 0.66f, s))
    MmiLine.GLOBE -> listOf(
        circle(0.5f, 0.5f, 0.40f, s),
        path(s) { addOval(Rect(0.32f * it, 0.10f * it, 0.68f * it, 0.90f * it)); moveTo(0.10f * it, 0.5f * it); lineTo(0.90f * it, 0.5f * it) },
    ) to emptyList<Path>()
    MmiLine.NOTE -> emptyList<Path>() to listOf(
        path(s) {
            addOval(Rect(0.14f * it, 0.66f * it, 0.46f * it, 0.92f * it))
            addRect(Rect(0.38f * it, 0.10f * it, 0.46f * it, 0.80f * it))
        },
        poly(s, 0.38f to 0.08f, 0.86f to 0.22f, 0.86f to 0.38f, 0.38f to 0.26f),
    )
}
