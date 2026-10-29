package com.openlauncher.app.ui.screen

import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.media.audiofx.AudioEffect
import android.net.Uri
import android.provider.Settings
import android.text.format.DateFormat
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.openlauncher.app.R
import com.openlauncher.app.data.CarPrefs
import com.openlauncher.app.model.AppInfo
import com.openlauncher.app.model.NowPlayingState
import com.openlauncher.app.map.MapData
import com.openlauncher.app.map.PlaceSearch
import com.openlauncher.app.model.HardwareRadioState
import com.openlauncher.app.service.NavGuidance
import com.openlauncher.app.map.NavigationEngine
import com.openlauncher.app.service.NavigationTracker
import com.openlauncher.app.util.IntroVideo
import com.openlauncher.app.util.LocationData
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

private enum class MmiPage(val title: String?, val inSettings: Boolean = false) {
    HOME(null), DASHBOARD("Dashboard"), SETTINGS("Settings"),
    MUSIC("Music"), RADIO("Radio"), PHONE("Phone"), APPS("Apps"),
    NAV("Navigation"), SYSTEM("System check", true),
    DISPLAY("Display", true), SOUND("Sound", true), BLUETOOTH("Bluetooth", true), NAV_SETTINGS("Navigation settings", true),
}

/**
 * Audi MMI-style shell. HOME is the car-and-ring menu with a drive card that
 * becomes a live navigation card while our guidance (or Maps/Waze) is running; every other entry
 * opens an MMI-styled page inside the same frame (chrome bar, date bar), with a
 * zoom-and-fade transition between them.
 */
@Composable
fun MmiHomeScreen(
    metric: Boolean,
    onSetMetric: (Boolean) -> Unit,
    nowPlaying: NowPlayingState?,
    onPlayPause: () -> Unit,
    onNext: () -> Unit,
    onPrev: () -> Unit,
    hardwareRadio: HardwareRadioState?,
    radioPackage: String,
    onLaunchRadio: () -> Unit,
    onRadioSeekUp: () -> Unit,
    onRadioSeekDown: () -> Unit,
    onRadioFm: () -> Unit,
    onRadioAm: () -> Unit,
    apps: List<AppInfo>,
    location: LocationData?,
    bearing: Float,
    voltage: Float?,
    onLaunchPackage: (String) -> Unit,
    homeSignal: Int,
) {
    var page by rememberSaveable { mutableStateOf(MmiPage.HOME) }
    var selected by rememberSaveable { mutableStateOf("nav") }
    // Bluetooth is reached from Settings or from the Phone page; Back returns to whichever.
    var bluetoothFrom by rememberSaveable { mutableStateOf(MmiPage.SETTINGS) }
    val back = {
        page = when {
            page == MmiPage.BLUETOOTH -> bluetoothFrom
            page.inSettings -> MmiPage.SETTINGS
            else -> MmiPage.HOME
        }
    }
    BackHandler(enabled = page != MmiPage.HOME) { back() }
    // Pressing the unit's Home key returns to the ring menu.
    LaunchedEffect(homeSignal) { if (homeSignal > 0) page = MmiPage.HOME }
    val context = LocalContext.current
    val guidance by NavigationTracker.guidance.collectAsStateWithLifecycle()
    val ownNav by NavigationEngine.state.collectAsStateWithLifecycle()

    val openRadioApp = {
        if (radioPackage.isNotEmpty() || hardwareRadio != null) onLaunchRadio()
        else apps.findByLabel("radio", "fm")?.let { onLaunchPackage(it.packageName) } ?: onLaunchRadio()
    }
    val openMusicApp = {
        val pkg = nowPlaying?.controller?.packageName
        val btMusic = apps.findByLabel("bt music", "bluetooth music", "a2dp", "bt audio")
        when {
            !pkg.isNullOrEmpty() -> onLaunchPackage(pkg)
            btMusic != null -> onLaunchPackage(btMusic.packageName)
            context.launchFirst(Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_MUSIC)) -> Unit
            else -> apps.findByLabel("music")?.let { onLaunchPackage(it.packageName) } ?: Unit
        }
    }
    // With no media session (some vendor Bluetooth-music apps publish none), fall back to
    // media-button presses, which the Bluetooth app forwards to the phone like steering-wheel keys.
    val audio = remember(context) { context.getSystemService(Context.AUDIO_SERVICE) as AudioManager }
    fun mediaKey(code: Int) {
        audio.dispatchMediaKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, code))
        audio.dispatchMediaKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_UP, code))
    }
    val playPause = { if (nowPlaying?.controller != null) onPlayPause() else mediaKey(android.view.KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE) }
    val next = { if (nowPlaying?.controller != null) onNext() else mediaKey(android.view.KeyEvent.KEYCODE_MEDIA_NEXT) }
    val prev = { if (nowPlaying?.controller != null) onPrev() else mediaKey(android.view.KeyEvent.KEYCODE_MEDIA_PREVIOUS) }

    BoxWithConstraints(Modifier.fillMaxSize().mmiBackdrop()) {
        val barHeight = maxHeight * 0.11f
        val cardWidth = maxWidth * 0.24f
        Column(Modifier.fillMaxSize()) {
            TopBar(
                height = barHeight,
                title = page.title,
                nowPlaying = nowPlaying,
                onBack = back,
                onTapNowPlaying = { page = MmiPage.MUSIC },
            )
            AnimatedContent(
                targetState = page,
                transitionSpec = {
                    (fadeIn(tween(260, delayMillis = 60)) +
                        scaleIn(tween(320, easing = FastOutSlowInEasing), initialScale = 0.94f)) togetherWith
                        (fadeOut(tween(180)) + scaleOut(tween(220), targetScale = 1.04f))
                },
                modifier = Modifier.weight(1f).fillMaxWidth(),
                label = "mmi_page",
            ) { target ->
                when (target) {
                    MmiPage.HOME -> Row(Modifier.fillMaxSize()) {
                        Orbit(
                            modifier = Modifier.weight(1f).fillMaxHeight(),
                            selected = selected,
                            onSelect = { key ->
                                selected = key
                                when (key) {
                                    "dashboard" -> page = MmiPage.DASHBOARD
                                    "video" -> context.openVideo(apps, onLaunchPackage)
                                    "settings" -> page = MmiPage.SETTINGS
                                    "music" -> page = MmiPage.MUSIC
                                    "radio" -> page = MmiPage.RADIO
                                    "phone" -> page = MmiPage.PHONE
                                    "nav" -> page = MmiPage.NAV
                                    "apps" -> page = MmiPage.APPS
                                    else -> Unit
                                }
                            },
                        )
                        Box(Modifier.fillMaxHeight().width(cardWidth).padding(end = 16.dp, top = 4.dp, bottom = 8.dp)) {
                            val active = guidance
                            if (ownNav.destination != null) {
                                MmiRouteCard(ownNav, metric, Modifier.fillMaxSize()) { page = MmiPage.NAV }
                            } else if (active != null) {
                                NavCard(active, Modifier.fillMaxSize()) { context.openNavigation(active) }
                            } else {
                                DriveCard(location, bearing, voltage, metric, Modifier.fillMaxSize())
                            }
                        }
                    }
                    MmiPage.DASHBOARD -> MmiDashboardPage(location, bearing, voltage, metric)
                    MmiPage.SETTINGS -> SettingsPanel(metric, onSetMetric) {
                        if (it == MmiPage.BLUETOOTH) bluetoothFrom = MmiPage.SETTINGS
                        page = it
                    }
                    MmiPage.DISPLAY -> MmiDisplayPage()
                    MmiPage.SOUND -> MmiSoundPage(apps, onLaunchPackage)
                    MmiPage.BLUETOOTH -> MmiBluetoothPage(apps, onLaunchPackage)
                    MmiPage.NAV_SETTINGS -> MmiNavSettingsPage(metric, onSetMetric)
                    MmiPage.NAV -> MmiNavigationPage(location, metric)
                    MmiPage.SYSTEM -> MmiSystemCheckPage(apps, location)
                    MmiPage.MUSIC -> MmiMusicPage(
                        state = nowPlaying,
                        onPlayPause = playPause, onNext = next, onPrev = prev,
                        onOpenSource = openMusicApp,
                        onOpenMusicApp = openMusicApp,
                    )
                    MmiPage.RADIO -> MmiRadioPage(
                        radio = hardwareRadio,
                        onOpenRadio = openRadioApp,
                        onSeekUp = onRadioSeekUp, onSeekDown = onRadioSeekDown,
                        onFm = onRadioFm, onAm = onRadioAm,
                    )
                    MmiPage.PHONE -> MmiPhonePage(apps) {
                        bluetoothFrom = MmiPage.PHONE
                        page = MmiPage.BLUETOOTH
                    }
                    MmiPage.APPS -> {
                        val prefs = remember(context) { CarPrefs.get(context) }
                        val hidden by prefs.hiddenApps.collectAsStateWithLifecycle()
                        MmiAppsPage(apps, hidden, prefs::toggleHidden, onLaunchPackage)
                    }
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
private fun TopBar(
    height: Dp,
    title: String?,
    nowPlaying: NowPlayingState?,
    onBack: () -> Unit,
    onTapNowPlaying: () -> Unit,
) {
    val titleSize = with(LocalDensity.current) { (height * 0.36f).toSp() }
    Box(Modifier.fillMaxWidth().height(height)) {
        if (title != null) {
            Row(
                Modifier.align(Alignment.CenterStart).padding(start = 28.dp).pressable(onBack),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                MmiLineIcon(MmiLine.CHEVRON_LEFT, MmiDim, Modifier.size(height * 0.4f))
                Spacer(Modifier.width(6.dp))
                Text(title, color = MmiInk, fontFamily = MmiFont, fontSize = titleSize)
            }
        }
        if (nowPlaying != null && nowPlaying.title.isNotBlank()) {
            Row(
                Modifier.align(Alignment.CenterEnd).padding(end = 28.dp).widthIn(max = 420.dp).pressable(onTapNowPlaying),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                MmiLineIcon(if (nowPlaying.isPlaying) MmiLine.NOTE else MmiLine.PAUSE, MmiCyan, Modifier.size(height * 0.3f))
                Spacer(Modifier.width(10.dp))
                AnimatedContent(
                    targetState = listOf(nowPlaying.title, nowPlaying.artist).filter { it.isNotBlank() }.joinToString(" – "),
                    transitionSpec = {
                        (slideInVertically(tween(320)) { it } + fadeIn(tween(260))) togetherWith
                            (slideOutVertically(tween(220)) { -it } + fadeOut(tween(180)))
                    },
                    label = "now_playing",
                ) { text ->
                    Text(text, color = MmiDim, fontFamily = MmiFont, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
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
            delay(60_000 - value % 60_000)
        }
    }
    val dateFmt = remember { SimpleDateFormat("MMMM d, yyyy", Locale.getDefault()) }
    val clock24 by CarPrefs.get(context).clock24.collectAsStateWithLifecycle()
    val timeFmt = remember(clock24) {
        SimpleDateFormat(if (clock24) "HH:mm" else "h:mm a", Locale.getDefault())
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
    OrbitItem("dashboard", "Dashboard", MmiGlyph.GAUGE, -110f),
    OrbitItem("video", "Video", MmiGlyph.REEL, -74f),
    OrbitItem("settings", "Settings", MmiGlyph.GEARS, -38f),
    OrbitItem("music", "Music", MmiGlyph.NOTE, -2f),
    OrbitItem("radio", "Radio", MmiGlyph.RADIO, 34f),
    OrbitItem("phone", "Phone", MmiGlyph.HANDSET, 70f),
    OrbitItem("nav", "Navigation", MmiGlyph.FLAG, 108f),
)

@Composable
private fun Orbit(modifier: Modifier, selected: String, onSelect: (String) -> Unit) {
    // One-shot entrance: the ring draws in, the car slides in, items pop in one by one.
    val intro = remember { Animatable(0f) }
    LaunchedEffect(Unit) { intro.animateTo(1f, tween(900, easing = FastOutSlowInEasing)) }

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

        Canvas(Modifier.fillMaxSize().graphicsLayer { alpha = (intro.value * 1.6f).coerceAtMost(1f) }) {
            val c = Offset(cx.toPx(), cy.toPx())
            val rxp = rx.toPx()
            val ryp = ry.toPx()
            val band = size.height * 0.035f
            val ringTL = Offset(c.x - rxp, c.y - ryp)
            val ringSize = Size(rxp * 2, ryp * 2)
            val outerTL = Offset(ringTL.x - band / 2, ringTL.y - band / 2)
            val outerSize = Size(ringSize.width + band, ringSize.height + band)
            val innerTL = Offset(ringTL.x + band / 2, ringTL.y + band / 2)
            val innerSize = Size(ringSize.width - band, ringSize.height - band)

            // The MMI "disc": a near-black floor inside the ring, slightly lighter towards the rim.
            drawOval(
                Brush.radialGradient(
                    listOf(Color(0xF0030304), Color(0xE0070809), Color(0xC0121418)),
                    center = c, radius = rxp,
                ),
                topLeft = ringTL, size = ringSize,
            )
            drawOval(Color(0x80000000), topLeft = ringTL + Offset(0f, band * 0.7f), size = ringSize, style = Stroke(band * 1.5f))
            drawOval(
                Brush.verticalGradient(
                    listOf(Color(0xFF1E2125), Color(0xFF060607), Color(0xFF15171A)),
                    startY = c.y - ryp, endY = c.y + ryp,
                ),
                topLeft = ringTL, size = ringSize, style = Stroke(band),
            )
            drawOval(Color(0x30FFFFFF), topLeft = outerTL, size = outerSize, style = Stroke(1.5.dp.toPx()))
            drawOval(Color(0x70000000), topLeft = innerTL, size = innerSize, style = Stroke(1.5.dp.toPx()))
            drawArc(
                Brush.linearGradient(
                    listOf(Color(0x00FFFFFF), Color(0xFFE8ECF0), Color(0xFFFFFFFF), Color(0x00FFFFFF)),
                    start = Offset(c.x + rxp, c.y - ryp * 0.4f), end = Offset(c.x - rxp * 0.7f, c.y + ryp),
                ),
                startAngle = -35f, sweepAngle = 175f * intro.value, useCenter = false,
                topLeft = outerTL, size = outerSize,
                style = Stroke(3.dp.toPx(), cap = StrokeCap.Round),
            )
            drawArc(
                Color(0x60FFFFFF), startAngle = 10f, sweepAngle = 140f * intro.value, useCenter = false,
                topLeft = innerTL, size = innerSize, style = Stroke(1.dp.toPx(), cap = StrokeCap.Round),
            )

            val gy = carY.toPx() + carH.toPx() * 0.93f
            val gx = carX.toPx() + carW.toPx() * 0.47f
            val gw = carW.toPx() * 0.62f * intro.value
            if (gw > 1f) {
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
            }
            drawOval(
                Color(0xB0000000),
                topLeft = Offset(gx - carW.toPx() * 0.34f, gy - size.height * 0.04f), size = Size(carW.toPx() * 0.68f, size.height * 0.045f),
            )
        }

        val slide = with(LocalDensity.current) { 60.dp.toPx() }
        Image(
            painter = painterResource(R.drawable.mmi_car),
            contentDescription = "Your car",
            contentScale = ContentScale.Fit,
            modifier = Modifier.offset(x = carX, y = carY).size(carW, carH).graphicsLayer {
                alpha = intro.value
                translationX = (1f - intro.value) * -slide
            },
        )

        ORBIT_ITEMS.forEachIndexed { index, item ->
            val rad = Math.toRadians(item.angle.toDouble())
            OrbitEntry(
                label = item.label, glyph = item.glyph,
                x = cx + rx * cos(rad).toFloat() - iconSize / 2,
                y = cy + ry * sin(rad).toFloat() - iconSize / 2,
                iconSize = iconSize, labelSize = labelSize,
                selected = selected == item.key,
                // Lower-right entries sit where the ring climbs; drop their label below it.
                labelDrop = if (item.angle in 50f..90f) iconSize * 0.42f else 0.dp,
                appear = { ((intro.value - 0.25f - index * 0.07f) / 0.3f).coerceIn(0f, 1f) },
                onClick = { onSelect(item.key) },
            )
        }
        OrbitEntry(
            label = "APPS", glyph = MmiGlyph.APPS,
            x = w * 0.02f, y = h * 0.72f,
            iconSize = iconSize, labelSize = labelSize,
            selected = selected == "apps",
            appear = { ((intro.value - 0.2f) / 0.3f).coerceIn(0f, 1f) },
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
    appear: () -> Float,
    onClick: () -> Unit,
    labelDrop: Dp = 0.dp,
) {
    val boxColor by animateColorAsState(if (selected) MmiCyan else Color.Transparent, tween(220), label = "select")
    Row(
        Modifier.offset(x = x, y = y)
            .graphicsLayer {
                val a = appear()
                alpha = a
                scaleX = 0.6f + 0.4f * a
                scaleY = 0.6f + 0.4f * a
            }
            .pressable(onClick),
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
                .offset(y = labelDrop)
                .border(2.dp, boxColor, RoundedCornerShape(4.dp))
                .padding(horizontal = 6.dp, vertical = 2.dp),
        )
    }
}

// ── Right-hand cards ─────────────────────────────────────────────────────────

@Composable
private fun NavCard(guidance: NavGuidance, modifier: Modifier, onClick: () -> Unit) {
    val context = LocalContext.current
    Column(modifier.pressable(onClick), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Column(
            Modifier.fillMaxWidth().weight(1f).mmiPanel().padding(14.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceEvenly,
        ) {
            Text("NAVIGATION", color = MmiCyan, fontFamily = MmiFont, fontSize = 12.sp, letterSpacing = 3.sp)
            val arrow = guidance.arrow
            if (arrow != null) {
                Image(
                    bitmap = arrow.asImageBitmap(), contentDescription = null,
                    colorFilter = ColorFilter.tint(MmiInk),
                    modifier = Modifier.fillMaxWidth(0.45f).aspectRatio(1f),
                )
            } else {
                MmiIcon(MmiGlyph.FLAG, Modifier.fillMaxWidth(0.4f).aspectRatio(1f))
            }
            Text(
                guidance.instruction, color = MmiInk, fontFamily = MmiFont, fontWeight = FontWeight.Light,
                fontSize = 30.sp, maxLines = 2, textAlign = TextAlign.Center, overflow = TextOverflow.Ellipsis,
            )
            guidance.detail?.let {
                Text(it, color = MmiDim, fontFamily = MmiFont, fontSize = 16.sp, maxLines = 2, textAlign = TextAlign.Center, overflow = TextOverflow.Ellipsis)
            }
        }
        Row(Modifier.fillMaxWidth().height(48.dp).mmiPanel().padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                guidance.eta ?: (context.appLabel(guidance.sourcePackage) ?: "Guidance active"),
                color = MmiInk, fontFamily = MmiFont, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun DriveCard(location: LocationData?, bearing: Float, voltage: Float?, metric: Boolean, modifier: Modifier) {
    val context = LocalContext.current
    val speed = (location?.speedMps ?: 0f) * if (metric) 3.6f else 2.237f
    val heading = remember(bearing) { compassPoint(bearing) }
    // Area name from the offline map index, refreshed every ~500 m.
    val search = remember(context) { PlaceSearch(MapData.search(context)) }
    var area by remember { mutableStateOf<String?>(null) }
    val cellLat = location?.let { (it.latitude * 200).roundToInt() }
    val cellLon = location?.let { (it.longitude * 200).roundToInt() }
    LaunchedEffect(cellLat, cellLon) {
        val loc = location ?: return@LaunchedEffect
        if (!MapData.isInstalled(context)) return@LaunchedEffect
        area = search.nearby(AREA_KINDS, loc.latitude, loc.longitude, 4.0).firstOrNull()?.title ?: area
    }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Box(Modifier.fillMaxWidth().weight(1f).mmiPanel().padding(12.dp)) {
            Row(Modifier.align(Alignment.TopStart)) {
                Text(speed.roundToInt().toString(), color = MmiInk, fontFamily = MmiFont, fontWeight = FontWeight.Light, fontSize = 54.sp)
                Text(if (metric) "km/h" else "mph", color = MmiInk, fontFamily = MmiFont, fontSize = 14.sp, modifier = Modifier.padding(top = 10.dp, start = 2.dp))
            }
            Text(
                heading, color = MmiInk, fontFamily = MmiFont, fontSize = 18.sp,
                modifier = Modifier.align(Alignment.TopEnd).padding(top = 10.dp),
            )
            CompassRose(bearing, Modifier.align(Alignment.Center).padding(top = 28.dp).fillMaxWidth(0.5f).aspectRatio(1f))
            Text(
                if (location == null) "Waiting for GPS" else if (speed < 3f) "Parked" else "Heading ${compassWord(bearing)}",
                color = MmiDim, fontFamily = MmiFont, fontSize = 17.sp,
                modifier = Modifier.align(Alignment.BottomCenter),
            )
        }
        DriveRow(MmiLine.MOUNTAIN, location?.let { "${(if (metric) it.altitude else it.altitude * 3.281).roundToInt()} ${if (metric) "m" else "ft"}" } ?: "--")
        DriveRow(MmiLine.LOCATE, location?.satellitesUsed?.takeIf { it >= 0 }?.let { "$it satellites" } ?: if (location != null) "GPS fix" else "Searching")
        DriveRow(MmiLine.BATTERY, voltage?.let { "%.1f V".format(it) } ?: "--")
        Text(
            area ?: "",
            color = MmiInk, fontFamily = MmiFont, fontSize = 19.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
        )
    }
}

private val AREA_KINDS = listOf("neighbourhood", "suburb", "quarter", "village", "town", "city")

private fun compassPoint(bearing: Float): String =
    listOf("N", "NE", "E", "SE", "S", "SW", "W", "NW")[(((bearing % 360f) + 360f) % 360f / 45f + 0.5f).toInt() % 8]

private fun compassWord(bearing: Float): String =
    listOf("north", "north-east", "east", "south-east", "south", "south-west", "west", "north-west")[(((bearing % 360f) + 360f) % 360f / 45f + 0.5f).toInt() % 8]

/** Chrome compass: the rose turns so its red needle always points north. */
@Composable
private fun CompassRose(bearing: Float, modifier: Modifier) {
    val turn by androidx.compose.animation.core.animateFloatAsState(-bearing, tween(400), label = "rose")
    Canvas(modifier) {
        val r = size.minDimension / 2f
        val c = center
        drawCircle(Brush.radialGradient(listOf(Color(0xFF1B1E23), Color(0xFF0A0B0D)), c, r), r)
        drawCircle(Brush.linearGradient(listOf(Color(0xFFF2F4F7), Color(0xFF4F555D), Color(0xFFDDE1E6)), Offset(c.x - r, c.y - r), Offset(c.x + r, c.y + r)), r, style = Stroke(2.dp.toPx()))
        rotate(turn, c) {
            for (i in 0 until 36) {
                val a = Math.toRadians(i * 10.0)
                val outer = r * 0.9f
                val inner = if (i % 9 == 0) r * 0.74f else r * 0.82f
                drawLine(
                    Color(0x99B8BEC6),
                    Offset(c.x + inner * sin(a).toFloat(), c.y - inner * cos(a).toFloat()),
                    Offset(c.x + outer * sin(a).toFloat(), c.y - outer * cos(a).toFloat()),
                    if (i % 9 == 0) 2.dp.toPx() else 1.dp.toPx(),
                )
            }
            val north = Path().apply {
                moveTo(c.x, c.y - r * 0.66f); lineTo(c.x + r * 0.12f, c.y); lineTo(c.x - r * 0.12f, c.y); close()
            }
            val south = Path().apply {
                moveTo(c.x, c.y + r * 0.66f); lineTo(c.x + r * 0.12f, c.y); lineTo(c.x - r * 0.12f, c.y); close()
            }
            drawPath(north, Brush.verticalGradient(listOf(Color(0xFFFF5A4E), Color(0xFFB0261C)), c.y - r * 0.66f, c.y))
            drawPath(south, Brush.verticalGradient(listOf(Color(0xFFE9ECEF), Color(0xFF7D838B)), c.y, c.y + r * 0.66f))
        }
        drawCircle(Brush.radialGradient(listOf(Color.White, Color(0xFF8C939C)), c, r * 0.06f), r * 0.06f, c)
    }
}

@Composable
private fun DriveRow(icon: MmiLine, value: String) {
    Row(
        Modifier.fillMaxWidth().height(40.dp).mmiPanel().padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        MmiLineIcon(icon, MmiInk, Modifier.size(22.dp))
        Spacer(Modifier.width(18.dp))
        Text(value, color = MmiInk, fontFamily = MmiFont, fontSize = 16.sp)
    }
}

// ── Settings page ────────────────────────────────────────────────────────────

@Composable
private fun SettingsPanel(metric: Boolean, onSetMetric: (Boolean) -> Unit, onOpen: (MmiPage) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var introSet by remember { mutableStateOf(IntroVideo.isSet(context)) }
    val pickIntro = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch { introSet = IntroVideo.import(context, uri) }
    }
    val openSystem = { context.launchFirst(Intent(Settings.ACTION_SETTINGS)); Unit }
    val rows: List<Pair<String, () -> Unit>> = listOf(
        "Display" to { onOpen(MmiPage.DISPLAY) },
        "Sound" to { onOpen(MmiPage.SOUND) },
        "Bluetooth" to { onOpen(MmiPage.BLUETOOTH) },
        "Navigation" to { onOpen(MmiPage.NAV_SETTINGS) },
        (if (metric) "Units: km/h, metres" else "Units: mph, feet") to { onSetMetric(!metric) },
        (if (introSet) "Startup video: on (tap to remove)" else "Startup video: import from USB") to {
            if (introSet) {
                IntroVideo.remove(context)
                introSet = false
            } else {
                pickIntro.launch(arrayOf("video/*"))
            }
        },
        "Media access" to {
            context.launchFirst(Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS"), Intent(Settings.ACTION_SETTINGS))
            Unit
        },
        "Default home screen" to {
            context.launchFirst(Intent(Settings.ACTION_HOME_SETTINGS), Intent(Settings.ACTION_SETTINGS))
            Unit
        },
        "Date & time" to { context.launchFirst(Intent(Settings.ACTION_DATE_SETTINGS)); Unit },
        "System check" to { onOpen(MmiPage.SYSTEM) },
        "Android settings" to openSystem,
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
                        Modifier.fillMaxWidth().height(rowHeight).pressable(action)
                            .padding(start = panelWidth * 0.1f, end = panelWidth * 0.28f),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(label, color = MmiInk, fontFamily = MmiFont, fontSize = labelSize)
                        Spacer(Modifier.weight(1f))
                        MmiLineIcon(MmiLine.ARROW, Color.White, Modifier.size(rowHeight * 0.42f))
                    }
                }
            }
        }
        Box(
            Modifier.align(Alignment.CenterStart).offset(x = (-14).dp)
                .size(width = 40.dp, height = maxHeight * 0.24f)
                .clip(RoundedCornerShape(topEnd = 14.dp, bottomEnd = 14.dp))
                .background(Brush.horizontalGradient(listOf(Color(0xFF8E959E), Color(0xFFE9ECEF), Color(0xFFB4BAC2))))
                .pressable(openSystem),
            contentAlignment = Alignment.Center,
        ) {
            MmiLineIcon(MmiLine.MENU, Color(0xFF3A3F46), Modifier.size(24.dp))
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

private val NAV_PACKAGES = listOf("com.google.android.apps.maps", "com.waze", "com.here.app.maps")
private val VIDEO_PACKAGES = listOf("org.videolan.vlc", "com.mxtech.videoplayer.ad", "com.google.android.youtube")

private fun Context.openNavigation(guidance: NavGuidance?) {
    if (guidance != null && launchPackage(guidance.sourcePackage)) return
    if (NAV_PACKAGES.any { launchPackage(it) }) return
    launchFirst(Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0")))
}

private fun Context.openVideo(apps: List<AppInfo>, onLaunchPackage: (String) -> Unit) {
    if (VIDEO_PACKAGES.any { launchPackage(it) }) return
    val video = apps.findByLabel("video", "movie")
    if (video != null) onLaunchPackage(video.packageName)
    else launchFirst(Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_GALLERY))
}
