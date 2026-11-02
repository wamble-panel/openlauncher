package com.openlauncher.app.ui.screen

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AcUnit
import androidx.compose.material.icons.outlined.Air
import androidx.compose.material.icons.outlined.Battery5Bar
import androidx.compose.material.icons.outlined.CallEnd
import androidx.compose.material.icons.outlined.CallMade
import androidx.compose.material.icons.outlined.CallMissed
import androidx.compose.material.icons.outlined.CallReceived
import androidx.compose.material.icons.outlined.DeviceThermostat
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.LocalGasStation
import androidx.compose.material.icons.outlined.MicOff
import androidx.compose.material.icons.outlined.Opacity
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.SignalCellularAlt
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material.icons.outlined.Thermostat
import androidx.compose.material.icons.outlined.VolumeUp
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.openlauncher.app.R
import com.openlauncher.app.util.CarCore
import com.openlauncher.app.util.Climate
import com.openlauncher.app.util.TwBridge
import kotlinx.coroutines.delay

// Pages drawn to the approved screen designs (1280 × 800): dial pad, in-call, call
// rows, car info, diagnostics and the climate bar. Sizes are the designs' pixels ÷ 1.24.

private val OUTLINE = Color(0x88FFFFFF)
private val GREY = Color(0xFFA6A6A6)
private val CALL_GREEN = Color(0xFF1E8C3A)
private val END_RED = Color(0xFF9E1414)

@Composable
private fun Ico(icon: ImageVector, size: Dp, tint: Color = Color.White) =
    Icon(icon, contentDescription = null, modifier = Modifier.size(size), tint = tint)

// ── Dial pad ─────────────────────────────────────────────────────────────────

/** Number field with backspace, 3 × 4 keypad, green CALL and RECENT / CONTACTS. */
@Composable
internal fun MibDialPad(onCall: (String) -> Unit, onRecents: () -> Unit) {
    var number by remember { mutableStateOf("") }
    val keys = listOf("1" to "", "2" to "ABC", "3" to "DEF", "4" to "GHI", "5" to "JKL", "6" to "MNO", "7" to "PQRS", "8" to "TUV", "9" to "WXYZ", "*" to "", "0" to "+", "#" to "")
    Row(Modifier.fillMaxSize().padding(top = 18.dp)) {
        Column(Modifier.width(548.dp)) {
            Row(
                Modifier.fillMaxWidth().height(69.dp).border(1.dp, OUTLINE, RectangleShape).padding(start = 18.dp, end = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(number.ifEmpty { " " }, color = Color.White, fontFamily = MmiFont, fontSize = 36.sp, maxLines = 1, modifier = Modifier.weight(1f))
                Box(Modifier.size(56.dp).pressable { number = number.dropLast(1) }, contentAlignment = Alignment.Center) {
                    MmiLineIcon(MmiLine.BACKSPACE, Color.White, Modifier.size(34.dp))
                }
            }
            Spacer(Modifier.height(18.dp))
            keys.chunked(3).forEach { row ->
                Row(Modifier.fillMaxWidth().height(104.dp).padding(bottom = 9.dp), horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                    row.forEach { (digit, letters) ->
                        Column(
                            Modifier.weight(1f).fillMaxHeight().border(1.dp, OUTLINE, RectangleShape).pressable { number += digit },
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center,
                        ) {
                            Text(digit, color = Color.White, fontFamily = MmiFont, fontSize = 40.sp)
                            if (letters.isNotEmpty()) Text(letters, color = Color.White, fontFamily = MmiFont, fontSize = 18.sp)
                        }
                    }
                }
            }
        }
        Spacer(Modifier.width(41.dp))
        Column(Modifier.weight(1f).padding(top = 87.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Row(
                Modifier.fillMaxWidth().height(215.dp).background(CALL_GREEN)
                    .pressable { if (number.isNotEmpty()) onCall(number) },
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                MmiLineIcon(MmiLine.CALL, Color.White, Modifier.size(64.dp))
                Spacer(Modifier.width(30.dp))
                Text("CALL", color = Color.White, fontFamily = MmiFont, fontSize = 42.sp)
            }
            Box(
                Modifier.fillMaxWidth().height(208.dp).border(1.dp, OUTLINE, RectangleShape).pressable(onRecents),
                contentAlignment = Alignment.Center,
            ) { Text("RECENT / CONTACTS", color = Color.White, fontFamily = MmiFont, fontSize = 28.sp) }
        }
    }
}

// ── In-call ──────────────────────────────────────────────────────────────────

/**
 * Full in-call screen: photo circle, name, number, timer, the four round buttons and a
 * wide red End Call (Answer appears beside it while ringing). The car's Bluetooth
 * module only lets MMI answer, end and dial, so Mute, Keypad, Speaker and Hold are
 * shown dimmed until it offers them.
 */
@Composable
internal fun MibInCall(call: TwBridge.Call) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(call.state) {
        while (true) {
            now = System.currentTimeMillis()
            delay(1000)
        }
    }
    val ringing = call.state == TwBridge.CallState.INCOMING
    val status = when (call.state) {
        TwBridge.CallState.INCOMING -> "Incoming call"
        TwBridge.CallState.OUTGOING -> "Calling…"
        else -> ((now - call.since) / 1000).let { "%02d:%02d".format(it / 60, it % 60) }
    }
    Column(Modifier.fillMaxSize().background(Color.Black), horizontalAlignment = Alignment.CenterHorizontally) {
        Spacer(Modifier.height(48.dp))
        Box(
            Modifier.size(152.dp).clip(CircleShape).background(Color(0xFF1C1C1C)).border(2.dp, Color(0xFFBDBDBD), CircleShape),
            contentAlignment = Alignment.BottomCenter,
        ) { Ico(Icons.Outlined.Person, 130.dp, Color(0xFFB0B0B0)) }
        Spacer(Modifier.height(18.dp))
        Text(call.name ?: call.number ?: "Unknown", color = Color.White, fontFamily = MmiFont, fontSize = 46.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (call.name != null && call.number != null) Text(call.number, color = GREY, fontFamily = MmiFont, fontSize = 30.sp)
        Text(status, color = Color.White, fontFamily = MmiFont, fontSize = 32.sp)
        Spacer(Modifier.height(36.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(48.dp)) {
            CallRound(Icons.Outlined.MicOff, "Mute")
            CallRound(null, "Keypad")
            CallRound(Icons.Outlined.VolumeUp, "Speaker")
            CallRound(Icons.Outlined.Pause, "Hold")
        }
        Spacer(Modifier.height(46.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            if (ringing) {
                Row(
                    Modifier.size(width = 300.dp, height = 86.dp).background(CALL_GREEN).pressable { TwBridge.answer() },
                    horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically,
                ) {
                    MmiLineIcon(MmiLine.CALL, Color.White, Modifier.size(40.dp))
                    Spacer(Modifier.width(18.dp))
                    Text("Answer", color = Color.White, fontFamily = MmiFont, fontSize = 30.sp)
                }
            }
            Row(
                Modifier.size(width = 371.dp, height = 86.dp).background(END_RED).pressable { TwBridge.hangUp() },
                horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically,
            ) {
                Ico(Icons.Outlined.CallEnd, 52.dp)
                Spacer(Modifier.width(24.dp))
                Text(if (ringing) "Decline" else "End Call", color = Color.White, fontFamily = MmiFont, fontSize = 30.sp)
            }
        }
    }
}

@Composable
private fun CallRound(icon: ImageVector?, label: String) {
    // Not offered by the car's Bluetooth module yet: drawn, but dimmed and inactive.
    val ink = Color.White.copy(alpha = 0.35f)
    Column(
        Modifier.size(128.dp).border(2.dp, Color(0x55FFFFFF), CircleShape),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
    ) {
        if (icon != null) Ico(icon, 42.dp, ink) else MmiLineIcon(MmiLine.DIALPAD, ink, Modifier.size(42.dp))
        Text(label, color = ink, fontFamily = MmiFont, fontSize = 18.sp)
    }
}

// ── Call list row ────────────────────────────────────────────────────────────

/** Call list row: direction icon (missed in red), name, type in grey, time on the right. */
@Composable
internal fun MibCallRow(name: String, type: Int, detail: String, time: String, onClick: () -> Unit) {
    val missed = type == android.provider.CallLog.Calls.MISSED_TYPE
    val outgoing = type == android.provider.CallLog.Calls.OUTGOING_TYPE
    Row(
        Modifier.fillMaxWidth().height(84.dp)
            .drawBehind { drawLine(Color(0x38FFFFFF), Offset(0f, size.height - 1), Offset(size.width, size.height - 1), 1.dp.toPx()) }
            .pressable(onClick).padding(horizontal = 24.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Ico(
            when { missed -> Icons.Outlined.CallMissed; outgoing -> Icons.Outlined.CallMade; else -> Icons.Outlined.CallReceived },
            36.dp, if (missed) Color(0xFFE53935) else Color.White,
        )
        Spacer(Modifier.width(48.dp))
        Column(Modifier.weight(1f)) {
            Text(name, color = Color.White, fontFamily = MmiFont, fontSize = 28.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(detail, color = GREY, fontFamily = MmiFont, fontSize = 19.sp, maxLines = 1)
        }
        Text(time, color = Color.White, fontFamily = MmiFont, fontSize = 23.sp)
    }
}

// ── Car info ─────────────────────────────────────────────────────────────────

/** Car in the middle, three data tiles each side, tyre pressures and doors underneath. */
@Composable
internal fun MibCarInfo() {
    val car by CarCore.data.collectAsStateWithLifecycle()
    val outdoor = Climate.state.collectAsStateWithLifecycle().value.outdoor
    Column(Modifier.fillMaxSize().padding(top = 50.dp)) {
        Row(Modifier.fillMaxWidth().weight(1f)) {
            Column(Modifier.width(236.dp), verticalArrangement = Arrangement.spacedBy(26.dp)) {
                InfoTile(Icons.Outlined.Battery5Bar, "Battery", car.voltage?.let { "%.1f V".format(it) })
                InfoTile(Icons.Outlined.Thermostat, "Coolant", car.coolantC?.let { "$it °C" })
                InfoTile(Icons.Outlined.Opacity, "Oil life", car.oilLifePct?.let { "$it %" })
            }
            Box(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.Center) {
                Image(painterResource(R.drawable.home_art_car), null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp))
            }
            Column(Modifier.width(242.dp), verticalArrangement = Arrangement.spacedBy(26.dp)) {
                InfoTile(Icons.Outlined.LocalGasStation, "Range", car.rangeKm?.let { "$it km" })
                InfoTile(Icons.Outlined.SignalCellularAlt, "Avg consumption", car.avgConsumption?.let { "%.1f L/100km".format(it) })
                InfoTile(Icons.Outlined.DeviceThermostat, "Outside", outdoor?.let { "%.0f °C".format(it) })
            }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(Brush.horizontalGradient(listOf(Color.Transparent, Color(0x55FFFFFF), Color.Transparent))))
        val tyres = car.tyresKpa
        val parts = if (tyres != null && tyres.size == 4) listOf("FL", "FR", "RL", "RR").mapIndexed { i, w -> "$w %.1f bar".format(tyres[i] / 100f) } else listOf("Tyres --")
        Row(Modifier.fillMaxWidth().height(96.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
            parts.forEach { p ->
                Text(p, color = Color(0xFFDADADA), fontFamily = MmiFont, fontSize = 24.sp)
                Text("   |   ", color = Color(0x88FFFFFF), fontFamily = MmiFont, fontSize = 24.sp)
            }
            MmiLineIcon(MmiLine.CAR, Color(0xFFDADADA), Modifier.size(34.dp))
            Spacer(Modifier.width(14.dp))
            Text("Doors: " + (CarCore.doorsText(car.doors)?.lowercase() ?: "all closed"), color = Color(0xFFDADADA), fontFamily = MmiFont, fontSize = 24.sp)
        }
    }
}

@Composable
private fun InfoTile(icon: ImageVector, label: String, value: String?) {
    Row(
        Modifier.fillMaxWidth().height(99.dp).border(1.dp, OUTLINE, RectangleShape).padding(horizontal = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Ico(icon, 46.dp)
        Spacer(Modifier.width(20.dp))
        Column {
            Text(label, color = Color(0xFFDADADA), fontFamily = MmiFont, fontSize = 20.sp, maxLines = 1)
            Text(value ?: "--", color = Color.White, fontFamily = MmiFont, fontSize = 28.sp, maxLines = 1)
        }
    }
}

// ── Diagnostics ──────────────────────────────────────────────────────────────

/**
 * Systems with a status dot, fault codes with Clear codes, and four arc gauges. Fault
 * codes, RPM and intake need an OBD adapter, which is not connected yet; those parts
 * say so instead of showing made-up values. Coolant and battery come from the CAN box.
 */
@Composable
internal fun MibDiagnostics() {
    val car by CarCore.data.collectAsStateWithLifecycle()
    Row(Modifier.fillMaxSize().padding(top = 36.dp)) {
        Column(Modifier.width(400.dp)) {
            Box(Modifier.fillMaxWidth().height(1.dp).background(Color(0x55FFFFFF)))
            listOf("Engine", "Transmission", "ABS", "Airbag", "Body").forEach { system ->
                Row(
                    Modifier.fillMaxWidth().height(82.dp)
                        .drawBehind { drawLine(Color(0x55FFFFFF), Offset(0f, size.height - 1), Offset(size.width, size.height - 1), 1.dp.toPx()) }
                        .padding(horizontal = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(system, color = Color.White, fontFamily = MmiFont, fontSize = 30.sp, modifier = Modifier.weight(1f))
                    Box(Modifier.size(13.dp).clip(CircleShape).background(Color(0xFF6B6B6B)))
                    Spacer(Modifier.width(12.dp))
                    Text("No data", color = GREY, fontFamily = MmiFont, fontSize = 22.sp)
                    Spacer(Modifier.width(18.dp))
                    MmiLineIcon(MmiLine.ARROW, Color.White, Modifier.size(32.dp))
                }
            }
        }
        Spacer(Modifier.width(30.dp))
        Column(Modifier.weight(1f)) {
            Column(Modifier.fillMaxWidth().height(232.dp).border(1.dp, OUTLINE, RectangleShape).padding(24.dp)) {
                Text("Fault codes", color = Color.White, fontFamily = MmiFont, fontSize = 26.sp)
                Spacer(Modifier.height(10.dp))
                Text(
                    "Plug an OBD2 adapter into the car's diagnostic port to read and clear fault codes.",
                    color = GREY, fontFamily = MmiFont, fontSize = 20.sp,
                )
                Spacer(Modifier.weight(1f))
                Box(
                    Modifier.align(Alignment.End).size(width = 185.dp, height = 55.dp).border(1.dp, Color(0x44FFFFFF), RectangleShape),
                    contentAlignment = Alignment.Center,
                ) { Text("Clear codes", color = Color.White.copy(alpha = 0.35f), fontFamily = MmiFont, fontSize = 24.sp) }
            }
            Spacer(Modifier.height(30.dp))
            Row(Modifier.fillMaxWidth().height(162.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                ArcGauge(null, 0f, 8000f, "RPM", { it.toInt().toString() }, Modifier.weight(1f))
                ArcGauge(car.coolantC?.toFloat(), 60f, 120f, "Coolant", { "${it.toInt()}°" }, Modifier.weight(1f))
                ArcGauge(null, -20f, 80f, "Intake", { "${it.toInt()}°" }, Modifier.weight(1f))
                ArcGauge(car.voltage, 11f, 16f, "Battery", { "%.1f V".format(it) }, Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun ArcGauge(value: Float?, min: Float, max: Float, label: String, format: (Float) -> String, modifier: Modifier) {
    Box(modifier.fillMaxHeight().border(1.dp, OUTLINE, RectangleShape)) {
        Canvas(Modifier.fillMaxSize().padding(start = 12.dp, end = 12.dp, top = 16.dp, bottom = 28.dp)) {
            val d = minOf(size.width, size.height * 1.15f)
            val tl = Offset((size.width - d) / 2, 0f)
            val stroke = Stroke(6.dp.toPx(), cap = StrokeCap.Round)
            drawArc(Color(0xFF3A3A3A), 150f, 240f, false, tl, Size(d, d), style = stroke)
            if (value != null) {
                val f = ((value - min) / (max - min)).coerceIn(0f, 1f)
                drawArc(Color(0xFFE6E6E6), 150f, 240f * f, false, tl, Size(d, d), style = stroke)
            }
        }
        Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(value?.let(format) ?: "--", color = Color.White, fontFamily = MmiFont, fontSize = 34.sp)
            Text(label, color = Color(0xFFDADADA), fontFamily = MmiFont, fontSize = 18.sp)
        }
        Row(Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp)) {
            Text(format(min).removeSuffix("°").removeSuffix(" V"), color = Color.White, fontFamily = MmiFont, fontSize = 17.sp)
            Spacer(Modifier.weight(1f))
            Text(format(max).removeSuffix("°").removeSuffix(" V"), color = Color.White, fontFamily = MmiFont, fontSize = 17.sp, textAlign = TextAlign.End)
        }
    }
}

// ── Climate bar ──────────────────────────────────────────────────────────────

/**
 * Climate bar along the bottom of the page: driver temperature, A/C, AUTO (underlined
 * when on), recirculation, fan level as bars, a divider and the passenger temperature.
 * It mirrors the car's climate panel; the arrows show the controls but adjusting is
 * done on the car's own climate panel.
 */
@Composable
internal fun MibClimateBar(state: Climate.State, modifier: Modifier) {
    fun temp(t: Float?, lo: Boolean, hi: Boolean) = when {
        lo -> "LO"
        hi -> "HI"
        t == null -> "--"
        else -> "%.1f °C".format(t)
    }
    Row(
        modifier.fillMaxWidth().height(146.dp).background(Color(0xF5000000))
            .border(1.dp, Brush.verticalGradient(listOf(Color(0x66FFFFFF), Color(0x33FFFFFF))), RectangleShape)
            .padding(horizontal = 24.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TempBlock(temp(state.left, state.leftLo, state.leftHi))
        Spacer(Modifier.width(48.dp))
        ClimateButton(state.ac) { Ico(Icons.Outlined.AcUnit, 34.dp); Text("A/C", color = Color.White, fontFamily = MmiFont, fontSize = 18.sp) }
        Spacer(Modifier.width(12.dp))
        ClimateButton(state.auto) { Text("AUTO", color = Color.White, fontFamily = MmiFont, fontSize = 24.sp) }
        Spacer(Modifier.width(12.dp))
        ClimateButton(state.recirculation) { Ico(Icons.Outlined.Sync, 40.dp) }
        Spacer(Modifier.width(40.dp))
        Ico(Icons.Outlined.Air, 40.dp)
        Spacer(Modifier.width(14.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            repeat(7) { i ->
                Box(Modifier.size(width = 9.dp, height = 22.dp).background(if (i < state.fan) Color.White else Color(0xFF555555)))
            }
        }
        Spacer(Modifier.weight(1f))
        Box(Modifier.width(1.dp).height(96.dp).background(Color(0x66FFFFFF)))
        Spacer(Modifier.width(36.dp))
        TempBlock(temp(state.right, state.rightLo, state.rightHi))
    }
}

@Composable
private fun TempBlock(text: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Ico(Icons.Outlined.ExpandLess, 34.dp)
        Text(text, color = Color.White, fontFamily = MmiFont, fontSize = 42.sp)
        Ico(Icons.Outlined.ExpandMore, 34.dp)
    }
}

@Composable
private fun ClimateButton(on: Boolean, content: @Composable () -> Unit) {
    Box(
        Modifier.size(width = 120.dp, height = 88.dp).border(1.dp, OUTLINE, RectangleShape)
            .drawBehind { if (on) drawRect(Color.White, Offset(size.width * 0.13f, size.height - 4.dp.toPx()), Size(size.width * 0.74f, 3.dp.toPx())) },
        contentAlignment = Alignment.Center,
    ) { Column(horizontalAlignment = Alignment.CenterHorizontally) { content() } }
}
