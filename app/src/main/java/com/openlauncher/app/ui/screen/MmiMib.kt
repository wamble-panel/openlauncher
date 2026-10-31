package com.openlauncher.app.ui.screen

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// Audi MIB3 look for the inner pages: a quiet dark page, thin light lines, one warm
// accent for "where you are" (rail entry, breadcrumb, selection, progress).

internal val MibAccent = Color.White
private val MibSection = Color(0xFFA6A6A6)
internal val MibLine = Color(0x55FFFFFF)
private val MibRailBg = Color(0xFF14171B)

/** Left rail of the inner pages: Home, Radio, Media, Telephone, Navigation. */
internal val MIB_RAIL = listOf("home" to MmiLine.HOME, "radio" to MmiLine.ANTENNA, "music" to MmiLine.NOTE, "phone" to MmiLine.CALL, "nav" to MmiLine.NAV_ARROW)

@Composable
internal fun MibRail(active: String?, onOpen: (String) -> Unit, modifier: Modifier = Modifier) {
    Column(modifier.width(76.dp).fillMaxHeight().padding(top = 6.dp, bottom = 10.dp)) {
        MIB_RAIL.forEach { (key, icon) ->
            val on = key == active
            Box(
                Modifier.fillMaxWidth().weight(1f)
                    .background(
                        if (on) Brush.horizontalGradient(listOf(MibAccent.copy(alpha = 0.10f), MibAccent.copy(alpha = 0.32f)))
                        else Brush.horizontalGradient(listOf(MibRailBg, Color(0xFF1C2025)))
                    )
                    .drawBehind {
                        // Hairline between entries, and the accent edge on the active one.
                        drawLine(MibLine.copy(alpha = 0.18f), Offset(0f, size.height), Offset(size.width, size.height), 1.dp.toPx())
                        drawLine(if (on) MibAccent else MibLine.copy(alpha = 0.25f), Offset(size.width, 0f), Offset(size.width, size.height), (if (on) 2.dp else 1.dp).toPx())
                    }
                    .pressable { onOpen(key) },
                contentAlignment = Alignment.Center,
            ) { MmiLineIcon(icon, if (on) MibAccent else Color.White, Modifier.size(30.dp)) }
        }
    }
}

/**
 * Page header: optional back box, "Section · Page" breadcrumb (section in the accent),
 * optional extra boxes on the left and an action on the right, over a thin line.
 */
@Composable
internal fun MibHeader(
    section: String?,
    title: String,
    onBack: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
    leading: @Composable RowScope.() -> Unit = {},
    trailing: @Composable RowScope.() -> Unit = {},
) {
    Row(
        modifier.fillMaxWidth().height(58.dp).drawBehind {
            drawLine(MibLine, Offset(0f, size.height - 1), Offset(size.width, size.height - 1), 1.dp.toPx())
        },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (onBack != null) MibBox(onBack) { MmiLineIcon(MmiLine.RETURN, Color.White, Modifier.size(30.dp)) }
        leading()
        Row(Modifier.weight(1f).padding(horizontal = 18.dp), verticalAlignment = Alignment.CenterVertically) {
            if (section != null) {
                Text(section, color = MibSection, fontFamily = MmiFont, fontSize = 24.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                Text("  ·  ", color = Color.White, fontFamily = MmiFont, fontSize = 24.sp)
            }
            Text(title, color = Color.White, fontFamily = MmiFont, fontSize = 24.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        trailing()
    }
}

/** Square-ish header box with a hairline on its right, like the MIB back / source boxes. */
@Composable
internal fun MibBox(onClick: () -> Unit, width: Dp = 88.dp, content: @Composable RowScope.() -> Unit) {
    Row(
        Modifier.width(width).fillMaxHeight().pressable(onClick).drawBehind {
            drawLine(MibLine, Offset(size.width, size.height * 0.15f), Offset(size.width, size.height), 1.dp.toPx())
        },
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
        content = content,
    )
}

/** Outlined tile: icon over a label; the selected one glows in the accent from below. */
@Composable
internal fun MibTile(
    label: String,
    icon: MmiLine,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    enabled: Boolean = true,
    badge: MmiLine? = null,
    onClick: () -> Unit,
) {
    val ink = when {
        !enabled -> Color.White.copy(alpha = 0.28f)
        selected -> MibAccent
        else -> Color.White
    }
    Column(
        modifier
            .background(
                if (selected) Brush.verticalGradient(listOf(Color.Transparent, MibAccent.copy(alpha = 0.38f)))
                else Brush.verticalGradient(listOf(Color.Transparent, Color(0x14FFFFFF)))
            )
            .border(1.dp, Brush.verticalGradient(listOf(Color(0x10FFFFFF), if (selected) MibAccent else Color(0x88FFFFFF))), RectangleShape)
            .then(if (enabled) Modifier.pressable(onClick) else Modifier)
            .padding(10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box {
            MmiLineIcon(icon, ink, Modifier.size(46.dp))
            if (badge != null) MmiLineIcon(badge, ink, Modifier.size(22.dp).align(Alignment.BottomEnd).padding(start = 2.dp))
        }
        Spacer(Modifier.height(10.dp))
        Text(label, color = ink, fontFamily = MmiFont, fontSize = 20.sp, textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

/** Thin MIB slider: white line, accent fill, round grey knob. [value] is 0…1. */
@Composable
internal fun MibSlider(value: Float, onChange: (Float) -> Unit, modifier: Modifier) {
    val current by rememberUpdatedState(onChange)
    Canvas(
        modifier
            .pointerInput(Unit) { detectTapGestures { current((it.x / size.width).coerceIn(0f, 1f)) } }
            .pointerInput(Unit) { detectDragGestures { change, _ -> current((change.position.x / size.width).coerceIn(0f, 1f)) } },
    ) {
        val y = size.height / 2
        val x = size.width * value.coerceIn(0f, 1f)
        drawLine(Color(0x55FFFFFF), Offset(0f, y), Offset(size.width, y), 2.dp.toPx())
        drawLine(MibAccent, Offset(0f, y), Offset(x, y), 2.dp.toPx())
        drawCircle(Color(0xFF1A1D21), 12.dp.toPx(), Offset(x, y))
        drawCircle(Color(0xFFBFC3C8), 10.dp.toPx(), Offset(x, y))
    }
}

/** "Treble" row: label, then − slider + with Min / Max under the ends; steps by [step]. */
@Composable
internal fun MibSliderRow(label: String, value: Float, onChange: (Float) -> Unit, step: Float = 0.1f, minLabel: String = "Min", maxLabel: String = "Max") {
    Column(
        Modifier.fillMaxWidth().padding(vertical = 6.dp).drawBehind {
            drawLine(MibLine.copy(alpha = 0.25f), Offset(0f, size.height), Offset(size.width, size.height), 1.dp.toPx())
        },
    ) {
        Text(label, color = Color.White, fontFamily = MmiFont, fontSize = 22.sp, modifier = Modifier.padding(start = 8.dp, top = 4.dp))
        Row(Modifier.fillMaxWidth().height(52.dp), verticalAlignment = Alignment.CenterVertically) {
            MibStepButton(MmiLine.MINUS) { onChange((value - step).coerceIn(0f, 1f)) }
            MibSlider(value, onChange, Modifier.weight(1f).fillMaxHeight().padding(horizontal = 16.dp))
            MibStepButton(MmiLine.PLUS) { onChange((value + step).coerceIn(0f, 1f)) }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 60.dp, vertical = 2.dp)) {
            Text(minLabel, color = Color.White.copy(alpha = 0.8f), fontFamily = MmiFont, fontSize = 15.sp)
            Spacer(Modifier.weight(1f))
            Text(maxLabel, color = Color.White.copy(alpha = 0.8f), fontFamily = MmiFont, fontSize = 15.sp)
        }
    }
}

@Composable
private fun MibStepButton(icon: MmiLine, onClick: () -> Unit) {
    Box(
        Modifier.size(48.dp).border(1.dp, Color(0x33FFFFFF), RectangleShape).pressable(onClick),
        contentAlignment = Alignment.Center,
    ) { MmiLineIcon(icon, Color.White.copy(alpha = 0.7f), Modifier.size(20.dp)) }
}

/** Plain centred message for empty states. */
@Composable
internal fun MibMessage(text: String, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(text, color = Color.White, fontFamily = MmiFont, fontSize = 24.sp, textAlign = TextAlign.Center)
    }
}
