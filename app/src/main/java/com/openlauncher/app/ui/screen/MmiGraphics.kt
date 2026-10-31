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

// ── Line icons (white UI glyphs) ─────────────────────────────────────────────

enum class MmiLine {
    CHEVRON_LEFT, ARROW, MENU, PREV, NEXT, PLAY, PAUSE,
    CALL, BACKSPACE, OPEN, BLUETOOTH, CONTACTS, NOTE,
    PLUS, MINUS, LOCATE, SEARCH, STAR, STAR_FILLED, HISTORY, PIN, FUEL, CLOSE, HOME, FLAG, CARD, GLOBE,
    SPEAKER, MUTE, CAMERA, MOUNTAIN, BATTERY, GRID, ANTENNA, NAV_ARROW, GEAR, BUMP,
    CAR, CLOUD, FILM, LINK,
    RETURN, DOTS, CHEVRON_UP, PHONE, SDCARD, USB, VOICEMAIL, DIALPAD,
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
    MmiLine.SPEAKER -> listOf(
        path(s) { moveTo(0.64f * it, 0.36f * it); quadraticTo(0.74f * it, 0.5f * it, 0.64f * it, 0.64f * it) },
        path(s) { moveTo(0.74f * it, 0.24f * it); quadraticTo(0.94f * it, 0.5f * it, 0.74f * it, 0.76f * it) },
    ) to listOf(poly(s, 0.10f to 0.38f, 0.26f to 0.38f, 0.50f to 0.16f, 0.50f to 0.84f, 0.26f to 0.62f, 0.10f to 0.62f))
    MmiLine.MUTE -> listOf(
        path(s) { moveTo(0.64f * it, 0.36f * it); lineTo(0.90f * it, 0.64f * it); moveTo(0.90f * it, 0.36f * it); lineTo(0.64f * it, 0.64f * it) },
    ) to listOf(poly(s, 0.10f to 0.38f, 0.26f to 0.38f, 0.50f to 0.16f, 0.50f to 0.84f, 0.26f to 0.62f, 0.10f to 0.62f))
    MmiLine.BUMP -> listOf(
        path(s) { moveTo(0.04f * it, 0.74f * it); lineTo(0.22f * it, 0.74f * it); cubicTo(0.34f * it, 0.30f * it, 0.66f * it, 0.30f * it, 0.78f * it, 0.74f * it); lineTo(0.96f * it, 0.74f * it) },
        path(s) { moveTo(0.5f * it, 0.08f * it); lineTo(0.5f * it, 0.24f * it) },
    ) to emptyList<Path>()
    MmiLine.GRID -> listOf(
        path(s) { addRect(Rect(0.14f * it, 0.14f * it, 0.44f * it, 0.44f * it)) },
        path(s) { addRect(Rect(0.56f * it, 0.14f * it, 0.86f * it, 0.44f * it)) },
        path(s) { addRect(Rect(0.14f * it, 0.56f * it, 0.44f * it, 0.86f * it)) },
        path(s) { addRect(Rect(0.56f * it, 0.56f * it, 0.86f * it, 0.86f * it)) },
    ) to emptyList<Path>()
    MmiLine.ANTENNA -> listOf(
        path(s) { moveTo(0.5f * it, 0.42f * it); lineTo(0.5f * it, 0.92f * it) },
        path(s) { addArc(Rect(0.30f * it, 0.16f * it, 0.70f * it, 0.56f * it), 130f, 100f); addArc(Rect(0.30f * it, 0.16f * it, 0.70f * it, 0.56f * it), -50f, 100f) },
        path(s) { addArc(Rect(0.12f * it, 0.0f * it, 0.88f * it, 0.72f * it), 135f, 90f); addArc(Rect(0.12f * it, 0.0f * it, 0.88f * it, 0.72f * it), -45f, 90f) },
    ) to listOf(circle(0.5f, 0.36f, 0.06f, s))
    MmiLine.NAV_ARROW -> emptyList<Path>() to listOf(poly(s, 0.5f to 0.08f, 0.86f to 0.90f, 0.5f to 0.70f, 0.14f to 0.90f))
    MmiLine.GEAR -> listOf(
        path(s) {
            // Eight-tooth gear outline.
            val teeth = 8
            for (i in 0 until teeth * 4) {
                val a = Math.toRadians(i * 360.0 / (teeth * 4) - 90.0)
                val r = if ((i / 2) % 2 == 0) 0.44f else 0.33f
                val x = (0.5f + r * kotlin.math.cos(a).toFloat()) * it
                val y = (0.5f + r * kotlin.math.sin(a).toFloat()) * it
                if (i == 0) moveTo(x, y) else lineTo(x, y)
            }
            close()
        },
        circle(0.5f, 0.5f, 0.13f, s),
    ) to emptyList<Path>()
    MmiLine.BUMP -> listOf(
        path(s) { moveTo(0.04f * it, 0.74f * it); lineTo(0.22f * it, 0.74f * it); cubicTo(0.34f * it, 0.30f * it, 0.66f * it, 0.30f * it, 0.78f * it, 0.74f * it); lineTo(0.96f * it, 0.74f * it) },
        path(s) { moveTo(0.5f * it, 0.08f * it); lineTo(0.5f * it, 0.24f * it) },
    ) to emptyList<Path>()
    MmiLine.GRID -> listOf(
        path(s) { addRect(Rect(0.14f * it, 0.14f * it, 0.44f * it, 0.44f * it)) },
        path(s) { addRect(Rect(0.56f * it, 0.14f * it, 0.86f * it, 0.44f * it)) },
        path(s) { addRect(Rect(0.14f * it, 0.56f * it, 0.44f * it, 0.86f * it)) },
        path(s) { addRect(Rect(0.56f * it, 0.56f * it, 0.86f * it, 0.86f * it)) },
    ) to emptyList<Path>()
    MmiLine.ANTENNA -> listOf(
        path(s) { moveTo(0.5f * it, 0.42f * it); lineTo(0.5f * it, 0.92f * it) },
        path(s) { addArc(Rect(0.30f * it, 0.16f * it, 0.70f * it, 0.56f * it), 130f, 100f); addArc(Rect(0.30f * it, 0.16f * it, 0.70f * it, 0.56f * it), -50f, 100f) },
        path(s) { addArc(Rect(0.12f * it, 0.0f * it, 0.88f * it, 0.72f * it), 135f, 90f); addArc(Rect(0.12f * it, 0.0f * it, 0.88f * it, 0.72f * it), -45f, 90f) },
    ) to listOf(circle(0.5f, 0.36f, 0.06f, s))
    MmiLine.NAV_ARROW -> emptyList<Path>() to listOf(poly(s, 0.5f to 0.08f, 0.86f to 0.90f, 0.5f to 0.70f, 0.14f to 0.90f))
    MmiLine.GEAR -> listOf(
        circle(0.5f, 0.5f, 0.17f, s),
        path(s) {
            for (i in 0 until 8) {
                val a = Math.toRadians(i * 45.0)
                moveTo((0.5f + 0.27f * kotlin.math.cos(a).toFloat()) * it, (0.5f + 0.27f * kotlin.math.sin(a).toFloat()) * it)
                lineTo((0.5f + 0.40f * kotlin.math.cos(a).toFloat()) * it, (0.5f + 0.40f * kotlin.math.sin(a).toFloat()) * it)
            }
        },
    ) to emptyList<Path>()
    MmiLine.MOUNTAIN -> listOf(path(s) {
        moveTo(0.06f * it, 0.84f * it); lineTo(0.38f * it, 0.30f * it); lineTo(0.56f * it, 0.58f * it)
        lineTo(0.68f * it, 0.44f * it); lineTo(0.94f * it, 0.84f * it); close()
    }) to emptyList<Path>()
    MmiLine.RETURN -> listOf(
        path(s) { moveTo(0.86f * it, 0.28f * it); lineTo(0.86f * it, 0.62f * it); lineTo(0.16f * it, 0.62f * it) },
        path(s) { moveTo(0.32f * it, 0.46f * it); lineTo(0.16f * it, 0.62f * it); lineTo(0.32f * it, 0.78f * it) },
    ) to emptyList<Path>()
    MmiLine.DOTS -> emptyList<Path>() to listOf(circle(0.22f, 0.5f, 0.07f, s), circle(0.5f, 0.5f, 0.07f, s), circle(0.78f, 0.5f, 0.07f, s))
    MmiLine.CHEVRON_UP -> listOf(path(s) { moveTo(0.20f * it, 0.64f * it); lineTo(0.5f * it, 0.34f * it); lineTo(0.80f * it, 0.64f * it) }) to emptyList<Path>()
    MmiLine.PHONE -> listOf(
        path(s) { addRoundRect(RoundRect(Rect(0.30f * it, 0.08f * it, 0.70f * it, 0.92f * it), CornerRadius(0.07f * it))) },
        path(s) { moveTo(0.45f * it, 0.17f * it); lineTo(0.55f * it, 0.17f * it) },
    ) to emptyList<Path>()
    MmiLine.SDCARD -> listOf(
        path(s) { moveTo(0.26f * it, 0.10f * it); lineTo(0.62f * it, 0.10f * it); lineTo(0.76f * it, 0.24f * it); lineTo(0.76f * it, 0.90f * it); lineTo(0.26f * it, 0.90f * it); close() },
        path(s) { moveTo(0.38f * it, 0.20f * it); lineTo(0.38f * it, 0.32f * it); moveTo(0.50f * it, 0.20f * it); lineTo(0.50f * it, 0.32f * it) },
    ) to emptyList<Path>()
    MmiLine.USB -> listOf(
        path(s) { moveTo(0.5f * it, 0.16f * it); lineTo(0.5f * it, 0.82f * it) },
        path(s) { moveTo(0.5f * it, 0.64f * it); lineTo(0.30f * it, 0.50f * it); lineTo(0.30f * it, 0.38f * it) },
        path(s) { moveTo(0.5f * it, 0.54f * it); lineTo(0.70f * it, 0.42f * it); lineTo(0.70f * it, 0.32f * it) },
    ) to listOf(poly(s, 0.40f to 0.20f, 0.5f to 0.06f, 0.60f to 0.20f), circle(0.5f, 0.86f, 0.07f, s))
    MmiLine.VOICEMAIL -> listOf(
        circle(0.27f, 0.52f, 0.15f, s), circle(0.73f, 0.52f, 0.15f, s),
        path(s) { moveTo(0.27f * it, 0.67f * it); lineTo(0.73f * it, 0.67f * it) },
    ) to emptyList<Path>()
    MmiLine.DIALPAD -> emptyList<Path>() to (listOf(0.26f, 0.5f, 0.74f).flatMap { x -> listOf(0.14f, 0.34f, 0.54f).map { y -> rect(x - 0.07f, y - 0.06f, x + 0.07f, y + 0.06f, s) } } +
        rect(0.43f, 0.68f, 0.57f, 0.80f, s))
    MmiLine.CAR -> listOf(
        path(s) {
            moveTo(0.10f * it, 0.70f * it); lineTo(0.10f * it, 0.52f * it); lineTo(0.22f * it, 0.48f * it)
            lineTo(0.32f * it, 0.30f * it); lineTo(0.68f * it, 0.30f * it); lineTo(0.78f * it, 0.48f * it)
            lineTo(0.90f * it, 0.52f * it); lineTo(0.90f * it, 0.70f * it); close()
        },
        path(s) { moveTo(0.22f * it, 0.48f * it); lineTo(0.78f * it, 0.48f * it) },
    ) to listOf(circle(0.28f, 0.74f, 0.08f, s), circle(0.72f, 0.74f, 0.08f, s))
    MmiLine.CLOUD -> listOf(path(s) {
        moveTo(0.24f * it, 0.74f * it); lineTo(0.76f * it, 0.74f * it)
        cubicTo(0.96f * it, 0.74f * it, 0.96f * it, 0.48f * it, 0.76f * it, 0.48f * it)
        cubicTo(0.74f * it, 0.24f * it, 0.42f * it, 0.20f * it, 0.38f * it, 0.42f * it)
        cubicTo(0.12f * it, 0.38f * it, 0.06f * it, 0.74f * it, 0.24f * it, 0.74f * it)
    }) to emptyList<Path>()
    MmiLine.FILM -> listOf(
        path(s) { addRoundRect(RoundRect(Rect(0.16f * it, 0.12f * it, 0.84f * it, 0.88f * it), CornerRadius(0.06f * it))) },
        path(s) { moveTo(0.30f * it, 0.12f * it); lineTo(0.30f * it, 0.88f * it); moveTo(0.70f * it, 0.12f * it); lineTo(0.70f * it, 0.88f * it) },
    ) to listOf(poly(s, 0.42f to 0.38f, 0.60f to 0.5f, 0.42f to 0.62f))
    MmiLine.LINK -> listOf(
        path(s) { addRoundRect(RoundRect(Rect(0.08f * it, 0.20f * it, 0.62f * it, 0.62f * it), CornerRadius(0.05f * it))) },
        path(s) { addRoundRect(RoundRect(Rect(0.66f * it, 0.30f * it, 0.92f * it, 0.86f * it), CornerRadius(0.05f * it))) },
        path(s) { moveTo(0.28f * it, 0.76f * it); lineTo(0.52f * it, 0.76f * it); moveTo(0.44f * it, 0.69f * it); lineTo(0.52f * it, 0.76f * it); lineTo(0.44f * it, 0.83f * it) },
    ) to emptyList<Path>()
    MmiLine.BATTERY -> listOf(
        path(s) { addRoundRect(RoundRect(Rect(0.08f * it, 0.30f * it, 0.92f * it, 0.82f * it), CornerRadius(0.06f * it))) },
        path(s) { moveTo(0.36f * it, 0.56f * it); lineTo(0.64f * it, 0.56f * it); moveTo(0.50f * it, 0.42f * it); lineTo(0.50f * it, 0.70f * it) },
    ) to listOf(rect(0.20f, 0.18f, 0.34f, 0.30f, s), rect(0.66f, 0.18f, 0.80f, 0.30f, s))
    MmiLine.CAMERA -> listOf(
        path(s) { addRoundRect(RoundRect(Rect(0.08f * it, 0.28f * it, 0.92f * it, 0.82f * it), CornerRadius(0.08f * it))) },
        circle(0.5f, 0.55f, 0.16f, s),
    ) to listOf(rect(0.32f, 0.18f, 0.56f, 0.30f, s))
    MmiLine.NOTE -> emptyList<Path>() to listOf(
        path(s) {
            addOval(Rect(0.14f * it, 0.66f * it, 0.46f * it, 0.92f * it))
            addRect(Rect(0.38f * it, 0.10f * it, 0.46f * it, 0.80f * it))
        },
        poly(s, 0.38f to 0.08f, 0.86f to 0.22f, 0.86f to 0.38f, 0.38f to 0.26f),
    )
}
