package com.openlauncher.app.ui.screen

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.media.audiofx.AudioEffect
import android.net.Uri
import android.provider.Settings
import android.text.format.DateFormat
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openlauncher.app.R
import com.openlauncher.app.model.WeatherState
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

private enum class MmiPage { HOME, DASHBOARD, SETTINGS }

private val MmiInk = Color(0xFFF0F2F4)
private val MmiDim = Color(0xFF9AA0A8)
private val MmiCyan = Color(0xFF3EC6F0)
private val MmiFont = FontFamily.SansSerif

/**
 * Audi MMI-style home: graphite grid floor, chrome horizon bar, the owner's car
 * inside an elliptical ring of chrome menu icons, weather card on the right and a
 * trapezoid date bar. Dashboard opens the regular widget grid; Settings opens an
 * MMI-styled settings list.
 */
@Composable
fun MmiHomeScreen(
    weather: WeatherState?,
    placeName: String?,
    metric: Boolean,
    nowPlayingPackage: String?,
    onLaunchPackage: (String) -> Unit,
    onOpenApps: () -> Unit,
    onOpenLauncherSettings: () -> Unit,
    dashboard: @Composable () -> Unit,
) {
    var page by rememberSaveable { mutableStateOf(MmiPage.HOME) }
    var selected by rememberSaveable { mutableStateOf("nav") }
    BackHandler(enabled = page != MmiPage.HOME) { page = MmiPage.HOME }
    val context = LocalContext.current

    BoxWithConstraints(Modifier.fillMaxSize().mmiBackdrop()) {
        val barHeight = maxHeight * 0.11f
        val cardWidth = maxWidth * 0.24f
        Column(Modifier.fillMaxSize()) {
            TopBar(
                height = barHeight,
                title = when (page) {
                    MmiPage.HOME -> null
                    MmiPage.DASHBOARD -> "Dashboard"
                    MmiPage.SETTINGS -> "Settings"
                },
                onBack = { page = MmiPage.HOME },
            )
            Box(Modifier.weight(1f).fillMaxWidth()) {
                when (page) {
                    MmiPage.HOME -> Row(Modifier.fillMaxSize()) {
                        Orbit(
                            modifier = Modifier.weight(1f).fillMaxHeight(),
                            selected = selected,
                            onSelect = { key ->
                                selected = key
                                when (key) {
                                    "dashboard" -> page = MmiPage.DASHBOARD
                                    "video" -> context.openVideo()
                                    "settings" -> page = MmiPage.SETTINGS
                                    "music" -> context.openMusic(nowPlayingPackage, onLaunchPackage, onOpenApps)
                                    "phone" -> context.launchFirst(Intent(Intent.ACTION_DIAL))
                                    "nav" -> context.openNavigation()
                                    "apps" -> onOpenApps()
                                    else -> Unit
                                }
                            },
                        )
                        WeatherCard(
                            weather = weather,
                            placeName = placeName,
                            metric = metric,
                            modifier = Modifier.fillMaxHeight().width(cardWidth)
                                .padding(end = 16.dp, top = 4.dp, bottom = 8.dp),
                        )
                    }
                    MmiPage.SETTINGS -> SettingsPanel(onOpenLauncherSettings)
                    MmiPage.DASHBOARD -> dashboard()
                }
            }
            DateBar(barHeight)
        }
    }
}

// ── Backdrop ─────────────────────────────────────────────────────────────────

private fun Modifier.mmiBackdrop() = drawWithCache {
    val w = size.width
    val h = size.height
    val base = Brush.verticalGradient(
        0f to Color(0xFF16181B),
        0.12f to Color(0xFF0A0B0D),
        1f to Color(0xFF0C0D10),
    )
    val horizonY = h * 0.28f
    val vanishX = w * 0.5f
    val line = Color(0xFF1B1E23)
    val floorRows = (1..14).map { i -> val t = i / 14f; horizonY + (h - horizonY) * t * t }
    val vignette = Brush.radialGradient(
        listOf(Color.Transparent, Color(0xB0000000)),
        center = Offset(w / 2f, h * 0.55f),
        radius = w * 0.75f,
    )
    val chromeY = h * 0.105f
    val chromeGlow = Brush.radialGradient(
        listOf(Color(0x40A0B4CC), Color.Transparent),
        center = Offset(w / 2f, chromeY),
        radius = w * 0.35f,
    )
    val chrome = Brush.horizontalGradient(
        0f to Color.Transparent,
        0.2f to Color(0x553A4048),
        0.5f to Color(0xFFB4BCC6),
        0.8f to Color(0x553A4048),
        1f to Color.Transparent,
    )
    val stroke = 1.dp.toPx()
    onDrawBehind {
        drawRect(base)
        floorRows.forEach { y -> drawLine(line, Offset(0f, y), Offset(w, y), stroke) }
        val spokes = 22
        for (i in -spokes..spokes) {
            val step = w / spokes
            drawLine(line, Offset(vanishX + i * step * 0.18f, horizonY), Offset(vanishX + i * step * 1.7f, h), stroke)
        }
        drawRect(vignette)
        drawRect(chromeGlow, topLeft = Offset.Zero, size = Size(w, chromeY * 2f))
        drawRect(chrome, topLeft = Offset(0f, chromeY - stroke), size = Size(w, stroke * 2.5f))
    }
}

// ── Top bar and date bar ─────────────────────────────────────────────────────

@Composable
private fun TopBar(height: Dp, title: String?, onBack: () -> Unit) {
    val titleSize = with(LocalDensity.current) { (height * 0.36f).toSp() }
    Box(Modifier.fillMaxWidth().height(height)) {
        if (title != null) {
            Row(
                Modifier.align(Alignment.CenterStart).padding(start = 32.dp)
                    .clickable(onClick = onBack),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Default.ChevronLeft, "Back", tint = MmiDim, modifier = Modifier.size(height * 0.45f))
                Text(title, color = MmiInk, fontFamily = MmiFont, fontSize = titleSize)
            }
        }
    }
}

@Composable
private fun DateBar(height: Dp) {
    val context = LocalContext.current
    val now by produceState(System.currentTimeMillis()) {
        while (true) {
            value = System.currentTimeMillis()
            delay(1000 - value % 1000)
        }
    }
    val dateFmt = remember { SimpleDateFormat("MMMM d, yyyy", Locale.getDefault()) }
    val timeFmt = remember(context) {
        SimpleDateFormat(if (DateFormat.is24HourFormat(context)) "HH:mm" else "h:mm a", Locale.getDefault())
    }
    val density = LocalDensity.current
    val dateSize = with(density) { (height * 0.42f).toSp() }
    val timeSize = with(density) { (height * 0.32f).toSp() }
    Box(
        Modifier.fillMaxWidth().height(height).drawWithCache {
            val w = size.width
            val bh = size.height
            val trap = Path().apply {
                moveTo(w * 0.25f, 0f)
                lineTo(w * 0.27f, bh * 0.9f)
                lineTo(w * 0.73f, bh * 0.9f)
                lineTo(w * 0.75f, 0f)
                close()
            }
            val edge = Color(0xFF41464E)
            val stroke = 1.5.dp.toPx()
            onDrawBehind {
                drawRect(Color(0xFF07080A))
                drawPath(trap, Brush.verticalGradient(listOf(Color(0xFF17191C), Color(0xFF0B0C0E))))
                drawPath(trap, edge, style = Stroke(stroke))
                drawLine(edge, Offset(0f, 0f), Offset(w * 0.25f, 0f), stroke)
                drawLine(edge, Offset(w * 0.75f, 0f), Offset(w, 0f), stroke)
            }
        },
        contentAlignment = Alignment.Center,
    ) {
        Text(dateFmt.format(Date(now)), color = MmiInk, fontFamily = MmiFont, fontSize = dateSize)
        Text(
            timeFmt.format(Date(now)), color = MmiDim, fontFamily = MmiFont, fontSize = timeSize,
            modifier = Modifier.align(Alignment.CenterEnd).padding(end = 28.dp),
        )
    }
}

// ── Orbit menu ───────────────────────────────────────────────────────────────

private data class OrbitItem(val key: String, val label: String, val glyph: MmiGlyph, val angle: Float)

private val ORBIT_ITEMS = listOf(
    OrbitItem("dashboard", "Dashboard", MmiGlyph.GAUGE, -106f),
    OrbitItem("video", "Video", MmiGlyph.REEL, -64f),
    OrbitItem("settings", "Settings", MmiGlyph.GEARS, -26f),
    OrbitItem("music", "Music", MmiGlyph.NOTE, 18f),
    OrbitItem("phone", "Phone", MmiGlyph.HANDSET, 64f),
    OrbitItem("nav", "Navigation", MmiGlyph.FLAG, 110f),
)

@Composable
private fun Orbit(modifier: Modifier, selected: String, onSelect: (String) -> Unit) {
    BoxWithConstraints(modifier) {
        val w = maxWidth
        val h = maxHeight
        val cx = w * 0.42f
        val cy = h * 0.50f
        val rx = w * 0.38f
        val ry = h * 0.44f
        val iconSize = h * 0.13f
        val labelSize = with(LocalDensity.current) { (h * 0.05f).toSp() }
        val carX = w * 0.03f
        val carY = h * 0.15f
        val carW = w * 0.46f
        val carH = carW * (473f / 900f)

        Canvas(Modifier.fillMaxSize()) {
            val c = Offset(cx.toPx(), cy.toPx())
            val rxp = rx.toPx()
            val ryp = ry.toPx()
            val band = size.height * 0.075f
            val ringTL = Offset(c.x - rxp, c.y - ryp)
            val ringSize = Size(rxp * 2, ryp * 2)
            val outerTL = Offset(ringTL.x - band / 2, ringTL.y - band / 2)
            val outerSize = Size(ringSize.width + band, ringSize.height + band)
            val innerTL = Offset(ringTL.x + band / 2, ringTL.y + band / 2)
            val innerSize = Size(ringSize.width - band, ringSize.height - band)

            // Floor shadow, then the glossy black tube.
            drawOval(Color(0x80000000), topLeft = ringTL + Offset(0f, band * 0.7f), size = ringSize, style = Stroke(band * 1.5f))
            drawOval(
                Brush.verticalGradient(
                    listOf(Color(0xFF1E2125), Color(0xFF060607), Color(0xFF15171A)),
                    startY = c.y - ryp, endY = c.y + ryp,
                ),
                topLeft = ringTL, size = ringSize, style = Stroke(band),
            )
            // Faint rim all round, bright chrome rim along the lower right.
            drawOval(Color(0x30FFFFFF), topLeft = outerTL, size = outerSize, style = Stroke(1.5.dp.toPx()))
            drawOval(Color(0x70000000), topLeft = innerTL, size = innerSize, style = Stroke(1.5.dp.toPx()))
            drawArc(
                Brush.linearGradient(
                    listOf(Color(0x00FFFFFF), Color(0xFFE8ECF0), Color(0xFFFFFFFF), Color(0x00FFFFFF)),
                    start = Offset(c.x + rxp, c.y - ryp * 0.4f), end = Offset(c.x - rxp * 0.7f, c.y + ryp),
                ),
                startAngle = -35f, sweepAngle = 175f, useCenter = false,
                topLeft = outerTL, size = outerSize,
                style = Stroke(3.dp.toPx(), cap = StrokeCap.Round),
            )
            drawArc(
                Color(0x60FFFFFF), startAngle = 10f, sweepAngle = 140f, useCenter = false,
                topLeft = innerTL, size = innerSize, style = Stroke(1.dp.toPx(), cap = StrokeCap.Round),
            )

            // Blue under-glow beneath the car's wheels.
            val gy = carY.toPx() + carH.toPx() * 0.93f
            val gx = carX.toPx() + carW.toPx() * 0.47f
            val gw = carW.toPx() * 0.62f
            drawOval(
                Brush.horizontalGradient(
                    listOf(Color.Transparent, MmiCyan.copy(alpha = 0.35f), Color.Transparent),
                    startX = gx - gw, endX = gx + gw,
                ),
                topLeft = Offset(gx - gw, gy - size.height * 0.03f), size = Size(gw * 2, size.height * 0.06f),
            )
            drawOval(
                Brush.horizontalGradient(
                    listOf(Color.Transparent, MmiCyan, Color(0xFFBFF1FF), MmiCyan, Color.Transparent),
                    startX = gx - gw * 0.85f, endX = gx + gw * 0.85f,
                ),
                topLeft = Offset(gx - gw * 0.85f, gy - size.height * 0.007f), size = Size(gw * 1.7f, size.height * 0.014f),
            )
            drawOval(
                Color(0xB0000000),
                topLeft = Offset(gx - gw * 0.55f, gy - size.height * 0.04f), size = Size(gw * 1.1f, size.height * 0.045f),
            )
        }

        Image(
            painter = painterResource(R.drawable.mmi_car),
            contentDescription = "Your car",
            contentScale = ContentScale.Fit,
            modifier = Modifier.offset(x = carX, y = carY).size(carW, carH),
        )

        ORBIT_ITEMS.forEach { item ->
            val rad = Math.toRadians(item.angle.toDouble())
            OrbitEntry(
                label = item.label, glyph = item.glyph,
                x = cx + rx * cos(rad).toFloat() - iconSize / 2,
                y = cy + ry * sin(rad).toFloat() - iconSize / 2,
                iconSize = iconSize, labelSize = labelSize,
                selected = selected == item.key,
                onClick = { onSelect(item.key) },
            )
        }
        OrbitEntry(
            label = "APPS", glyph = MmiGlyph.APPS,
            x = w * 0.02f, y = h * 0.72f,
            iconSize = iconSize, labelSize = labelSize,
            selected = selected == "apps",
            onClick = { onSelect("apps") },
        )
    }
}

@Composable
private fun OrbitEntry(
    label: String,
    glyph: MmiGlyph,
    x: Dp,
    y: Dp,
    iconSize: Dp,
    labelSize: TextUnit,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        Modifier.offset(x = x, y = y)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(iconSize).drawBehind {
                drawOval(
                    Color(0x99000000),
                    topLeft = Offset(size.width * 0.12f, size.height * 0.86f),
                    size = Size(size.width * 0.76f, size.height * 0.16f),
                )
            }
        ) {
            MmiIcon(glyph, Modifier.fillMaxSize())
        }
        Spacer(Modifier.width(6.dp))
        Text(
            label, color = MmiInk, fontFamily = MmiFont, fontSize = labelSize,
            modifier = Modifier
                .border(2.dp, if (selected) MmiCyan else Color.Transparent, RoundedCornerShape(4.dp))
                .padding(horizontal = 6.dp, vertical = 2.dp),
        )
    }
}

// ── Weather card ─────────────────────────────────────────────────────────────

@Composable
private fun WeatherCard(weather: WeatherState?, placeName: String?, metric: Boolean, modifier: Modifier) {
    fun shown(c: Double) = (if (metric) c else c * 9.0 / 5.0 + 32.0).roundToInt()
    val unit = if (metric) "°C" else "°F"
    val panelShape = RoundedCornerShape(3.dp)
    val panel = Modifier.fillMaxWidth().clip(panelShape)
        .background(Brush.verticalGradient(listOf(Color(0xFF17191C), Color(0xFF0E0F12))))
        .border(1.dp, Color(0xFF25282D), panelShape)
    val temps = weather?.hourlyForecast?.take(24)?.map { it.temperatureCelsius }.orEmpty()

    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Box(panel.weight(1f).padding(12.dp)) {
            Row(Modifier.align(Alignment.TopStart)) {
                Text(
                    weather?.let { shown(it.temperatureCelsius).toString() } ?: "--",
                    color = MmiInk, fontFamily = MmiFont, fontWeight = FontWeight.Light, fontSize = 54.sp,
                )
                Text(unit, color = MmiInk, fontFamily = MmiFont, fontSize = 16.sp, modifier = Modifier.padding(top = 8.dp))
            }
            if (temps.isNotEmpty()) {
                Text(
                    "${shown(temps.min())}~${shown(temps.max())}$unit",
                    color = MmiInk, fontFamily = MmiFont, fontSize = 18.sp,
                    modifier = Modifier.align(Alignment.TopEnd).padding(top = 8.dp),
                )
            }
            WeatherGlyph(
                code = weather?.weatherCode,
                isDay = weather?.isDay ?: true,
                modifier = Modifier.align(Alignment.Center).fillMaxWidth(0.62f).aspectRatio(1f),
            )
            Text(
                weather?.conditionLabel ?: "No weather data",
                color = MmiDim, fontFamily = MmiFont, fontSize = 17.sp,
                modifier = Modifier.align(Alignment.BottomCenter),
            )
        }
        WeatherRow(panel, Icons.Default.Air, weather?.windspeedDisplay(metric) ?: "--")
        WeatherRow(panel, Icons.Default.Thermostat, weather?.let { "Feels ${it.feelsLikeDisplay(metric)}" } ?: "--")
        WeatherRow(panel, Icons.Default.Umbrella, weather?.hourlyForecast?.firstOrNull()?.let { "${it.precipitationChance}%" } ?: "--")
        Text(
            placeName ?: "",
            color = MmiInk, fontFamily = MmiFont, fontSize = 19.sp, maxLines = 1,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
        )
    }
}

@Composable
private fun WeatherRow(panel: Modifier, icon: ImageVector, value: String) {
    Row(
        panel.height(40.dp).padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        Icon(icon, null, tint = MmiInk, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(18.dp))
        Text(value, color = MmiInk, fontFamily = MmiFont, fontSize = 16.sp)
    }
}

// ── Settings page ────────────────────────────────────────────────────────────

@Composable
private fun SettingsPanel(onOpenLauncherSettings: () -> Unit) {
    val context = LocalContext.current
    val rows: List<Pair<String, () -> Unit>> = listOf(
        "System" to { context.launchFirst(Intent(Settings.ACTION_SETTINGS)); Unit },
        "Navigation" to { context.launchFirst(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)); Unit },
        "Volume" to {
            (context.getSystemService(Context.AUDIO_SERVICE) as AudioManager)
                .adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_SAME, AudioManager.FLAG_SHOW_UI)
        },
        "Sound" to {
            context.launchFirst(
                Intent(AudioEffect.ACTION_DISPLAY_AUDIO_EFFECT_CONTROL_PANEL).putExtra(AudioEffect.EXTRA_AUDIO_SESSION, 0),
                Intent(Settings.ACTION_SOUND_SETTINGS),
            )
            Unit
        },
        "Bluetooth" to { context.launchFirst(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)); Unit },
        "Wi-Fi" to { context.launchFirst(Intent(Settings.ACTION_WIFI_SETTINGS)); Unit },
        "Display" to { context.launchFirst(Intent(Settings.ACTION_DISPLAY_SETTINGS)); Unit },
        "Language" to { context.launchFirst(Intent(Settings.ACTION_LOCALE_SETTINGS)); Unit },
        "Time" to { context.launchFirst(Intent(Settings.ACTION_DATE_SETTINGS)); Unit },
        "Launcher" to onOpenLauncherSettings,
    )
    BoxWithConstraints(Modifier.fillMaxSize().padding(start = 24.dp, end = 24.dp, bottom = 8.dp)) {
        val panelHeight = maxHeight
        val panelWidth = maxWidth
        val rowHeight = panelHeight / 6
        val labelSize = with(LocalDensity.current) { (rowHeight * 0.36f).toSp() }
        val frameShape = RoundedCornerShape(6.dp)
        Box(
            Modifier.fillMaxSize().clip(frameShape)
                .background(Color(0xE60A0B0D))
                .border(
                    1.5.dp,
                    Brush.verticalGradient(listOf(Color(0xFFB8BEC6), Color(0xFF2A2D32), Color(0xFF8E959E))),
                    frameShape,
                ),
        ) {
            RotaryDial(Modifier.align(Alignment.CenterEnd).padding(end = 24.dp).size(panelHeight * 0.85f))
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                rows.forEach { (label, action) ->
                    Row(
                        Modifier.fillMaxWidth().height(rowHeight).clickable(onClick = action)
                            .padding(start = panelWidth * 0.1f, end = panelWidth * 0.28f),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(label, color = MmiInk, fontFamily = MmiFont, fontSize = labelSize)
                        Spacer(Modifier.weight(1f))
                        Icon(Icons.Default.PlayArrow, null, tint = Color.White, modifier = Modifier.size(rowHeight * 0.5f))
                    }
                }
            }
        }
        // Silver pull tab on the left edge, as on the MMI list screens.
        Box(
            Modifier.align(Alignment.CenterStart).offset(x = (-14).dp)
                .size(width = 40.dp, height = maxHeight * 0.24f)
                .clip(RoundedCornerShape(topEnd = 14.dp, bottomEnd = 14.dp))
                .background(Brush.horizontalGradient(listOf(Color(0xFF8E959E), Color(0xFFE9ECEF), Color(0xFFB4BAC2))))
                .clickable(onClick = onOpenLauncherSettings),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Default.Menu, "Launcher settings", tint = Color(0xFF3A3F46))
        }
    }
}

@Composable
private fun RotaryDial(modifier: Modifier) {
    Canvas(modifier) {
        val r = size.minDimension / 2f
        val c = Offset(size.width * 0.35f, size.height / 2f)
        drawCircle(
            Brush.radialGradient(listOf(Color(0xFF1A1C20), Color(0xFF070809)), center = c, radius = r),
            radius = r, center = c,
        )
        drawArc(
            Brush.verticalGradient(listOf(Color(0x33FFFFFF), Color(0xFFDDE1E6), Color(0x33FFFFFF))),
            startAngle = -70f, sweepAngle = 140f, useCenter = false,
            topLeft = Offset(c.x - r, c.y - r), size = Size(r * 2, r * 2),
            style = Stroke(3.dp.toPx()),
        )
        var a = -60f
        while (a <= 60f) {
            val rad = Math.toRadians(a.toDouble())
            val outer = Offset(c.x + (r * 0.93f) * cos(rad).toFloat(), c.y + (r * 0.93f) * sin(rad).toFloat())
            val inner = Offset(c.x + (r * 0.83f) * cos(rad).toFloat(), c.y + (r * 0.83f) * sin(rad).toFloat())
            drawLine(Color(0xAAB8BEC6), inner, outer, 1.5.dp.toPx())
            a += 4f
        }
        drawCircle(
            Brush.radialGradient(listOf(Color.White, Color.Transparent), center = c, radius = r * 0.08f),
            radius = r * 0.08f, center = c,
        )
    }
}

// ── Intents ──────────────────────────────────────────────────────────────────

private fun Context.launchFirst(vararg intents: Intent): Boolean {
    for (intent in intents) {
        try {
            startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return true
        } catch (_: ActivityNotFoundException) {
        } catch (_: SecurityException) {
        }
    }
    return false
}

private val NAV_PACKAGES = listOf("com.google.android.apps.maps", "com.waze", "com.here.app.maps")
private val VIDEO_PACKAGES = listOf("org.videolan.vlc", "com.mxtech.videoplayer.ad", "com.google.android.youtube")

private fun Context.openNavigation() {
    val launch = NAV_PACKAGES.firstNotNullOfOrNull { packageManager.getLaunchIntentForPackage(it) }
    if (launch == null || !launchFirst(launch)) launchFirst(Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0")))
}

private fun Context.openMusic(nowPlayingPackage: String?, onLaunchPackage: (String) -> Unit, onOpenApps: () -> Unit) {
    when {
        !nowPlayingPackage.isNullOrEmpty() -> onLaunchPackage(nowPlayingPackage)
        launchFirst(Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_MUSIC)) -> Unit
        else -> onOpenApps()
    }
}

private fun Context.openVideo() {
    val launch = VIDEO_PACKAGES.firstNotNullOfOrNull { packageManager.getLaunchIntentForPackage(it) }
    if (launch == null || !launchFirst(launch)) {
        launchFirst(Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_GALLERY))
    }
}
