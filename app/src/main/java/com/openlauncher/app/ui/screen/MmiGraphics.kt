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

private val ChromeStops = listOf(
    Color(0xFFF8FAFC), Color(0xFFC3C9D0), Color(0xFF79808A), Color(0xFFDDE2E7), Color(0xFF4E545C),
)
private val Depth = Color(0xFF15171A)

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

// ── Line icons (white UI glyphs) ─────────────────────────────────────────────

enum class MmiLine {
    CHEVRON_LEFT, ARROW, MENU, PREV, NEXT, PLAY, PAUSE,
    CALL, BACKSPACE, OPEN, BLUETOOTH, CONTACTS, NOTE,
    PLUS, MINUS, LOCATE, SEARCH, STAR, STAR_FILLED, HISTORY, PIN, FUEL, CLOSE, HOME, FLAG, CARD, GLOBE,
    SPEAKER, MUTE, CAMERA, MOUNTAIN, BATTERY, GRID, ANTENNA, NAV_ARROW, GEAR, BUMP,
    CAR, CLOUD, FILM, LINK,
    RETURN, DOTS, CHEVRON_UP, PHONE, SDCARD, USB, VOICEMAIL, DIALPAD,
}

/** Small line icon: the Material outline icon for [kind], tinted (one icon style everywhere). */
@Composable
fun MmiLineIcon(kind: MmiLine, color: Color, modifier: Modifier) {
    androidx.compose.material3.Icon(outlineIcon(kind), contentDescription = null, modifier = modifier, tint = color)
}

/** Thin outline icon for each kind (Material Symbols, weight 300). */
private fun outlineIcon(kind: MmiLine): androidx.compose.ui.graphics.vector.ImageVector = MibSymbols.get(
    when (kind) {
        MmiLine.CHEVRON_LEFT -> "chevron_left"
        MmiLine.ARROW -> "chevron_right"
        MmiLine.MENU -> "menu"
        MmiLine.PREV -> "skip_previous"
        MmiLine.NEXT -> "skip_next"
        MmiLine.PLAY -> "play_arrow"
        MmiLine.PAUSE -> "pause"
        MmiLine.CALL -> "call"
        MmiLine.BACKSPACE -> "backspace"
        MmiLine.OPEN -> "open_in_new"
        MmiLine.BLUETOOTH -> "bluetooth"
        MmiLine.CONTACTS -> "contacts"
        MmiLine.NOTE -> "music_note"
        MmiLine.PLUS -> "add"
        MmiLine.MINUS -> "remove"
        MmiLine.LOCATE -> "my_location"
        MmiLine.SEARCH -> "search"
        MmiLine.STAR -> "star"
        MmiLine.STAR_FILLED -> "star.fill"
        MmiLine.HISTORY -> "history"
        MmiLine.PIN -> "place"
        MmiLine.FUEL -> "local_gas_station"
        MmiLine.CLOSE -> "close"
        MmiLine.HOME -> "home"
        MmiLine.FLAG -> "flag"
        MmiLine.CARD -> "info"
        MmiLine.GLOBE -> "language"
        MmiLine.SPEAKER -> "volume_up"
        MmiLine.MUTE -> "volume_off"
        MmiLine.CAMERA -> "photo_camera"
        MmiLine.MOUNTAIN -> "landscape"
        MmiLine.BATTERY -> "battery_full"
        MmiLine.GRID -> "apps"
        MmiLine.ANTENNA -> "cell_tower"
        MmiLine.NAV_ARROW -> "near_me"
        MmiLine.GEAR -> "settings"
        MmiLine.BUMP -> "warning"
        MmiLine.CAR -> "directions_car"
        MmiLine.CLOUD -> "partly_cloudy_day"
        MmiLine.FILM -> "movie"
        MmiLine.LINK -> "mobile_share"
        MmiLine.RETURN -> "arrow_back"
        MmiLine.DOTS -> "more_horiz"
        MmiLine.CHEVRON_UP -> "expand_less"
        MmiLine.PHONE -> "mobile"
        MmiLine.SDCARD -> "sd_card"
        MmiLine.USB -> "usb"
        MmiLine.VOICEMAIL -> "voicemail"
        MmiLine.DIALPAD -> "dialpad"
    }
)

private fun path(s: Float, block: Path.(Float) -> Unit) = Path().apply { block(s) }

