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
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
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
import com.openlauncher.app.map.RoadMemory
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import com.openlauncher.app.model.HardwareRadioState
import com.openlauncher.app.service.NavGuidance
import com.openlauncher.app.map.NavigationEngine
import com.openlauncher.app.service.NavigationTracker
import com.openlauncher.app.util.BtConnect
import com.openlauncher.app.util.IntroVideo
import com.openlauncher.app.util.LocationData
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

private enum class MmiPage(val title: String?, val inSettings: Boolean = false) {
    HOME(null), DASHBOARD("Dashboard"), SETTINGS("Settings"),
    MUSIC("Music"), RADIO("Radio"), BT_HUB("Bluetooth"), PHONE("Telephone"), CONTACTS("Contacts"), APPS("Apps"),
    NAV("Navigation"),
    PERMISSIONS("Permissions", true), CLEANUP("Unit cleanup & speed", true), STORAGE("Storage & about", true), DISPLAY("Display", true), SOUND("Sound", true), EQ("Equaliser", true), BLUETOOTH("Bluetooth devices", true), NAV_SETTINGS("Navigation settings", true),
}

/**
 * Audi MMI-style shell. HOME is the tile grid with a shortcut rail; every other entry
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
    // Flows, not values: GPS updates often, and collecting them only
    // where they're shown keeps the rest of the shell from redrawing.
    location: StateFlow<LocationData?>,
    bearing: StateFlow<Float>,
    voltage: StateFlow<Float?>,
    onLaunchPackage: (String) -> Unit,
    homeSignal: Int,
) {
    var page by rememberSaveable { mutableStateOf(MmiPage.HOME) }
    var selected by rememberSaveable { mutableStateOf("nav") }
    // Bluetooth is reached from Settings or from the Phone page; Back returns to whichever.
    var bluetoothFrom by rememberSaveable { mutableStateOf(MmiPage.SETTINGS) }
    var pairOnOpen by rememberSaveable { mutableStateOf(false) }
    val back = {
        page = when {
            page == MmiPage.BLUETOOTH -> bluetoothFrom
            page == MmiPage.PHONE || page == MmiPage.CONTACTS -> MmiPage.BT_HUB
            page.inSettings -> MmiPage.SETTINGS
            else -> MmiPage.HOME
        }
    }
    BackHandler(enabled = page != MmiPage.HOME) { back() }
    // First start after install: show the permissions checklist once if anything is missing.
    val startContext = LocalContext.current
    LaunchedEffect(Unit) {
        val prefs = startContext.getSharedPreferences("mmi_prefs", Context.MODE_PRIVATE)
        if (!prefs.getBoolean("permissions_shown", false) && !startContext.allPermissionsGranted()) {
            prefs.edit().putBoolean("permissions_shown", true).apply()
            page = MmiPage.PERMISSIONS
        }
    }
    // Pressing the unit's Home key returns to the ring menu.
    LaunchedEffect(homeSignal) { if (homeSignal > 0) page = MmiPage.HOME }
    val context = LocalContext.current

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
    // TS10 units: Bluetooth music is driven through the vendor Bluetooth app's commands.
    val tw = com.openlauncher.app.util.TwBridge
    val playPause = {
        when {
            nowPlaying?.controller != null -> onPlayPause()
            tw.available -> tw.btToggle()
            else -> mediaKey(android.view.KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
        }
    }
    val next = {
        when {
            nowPlaying?.controller != null -> onNext()
            tw.available -> tw.btNext()
            else -> mediaKey(android.view.KeyEvent.KEYCODE_MEDIA_NEXT)
        }
    }
    val prev = {
        when {
            nowPlaying?.controller != null -> onPrev()
            tw.available -> tw.btPrev()
            else -> mediaKey(android.view.KeyEvent.KEYCODE_MEDIA_PREVIOUS)
        }
    }

    // The Astra CD300 dash panel (codes learned from the car) drives MMI, so the
    // knob and buttons replace the touchscreen for moving between pages.
    val ringOrder = remember { HOME_TILES.map { it.key } }
    fun openRing(key: String) {
        selected = key
        when (key) {
            "carinfo" -> page = MmiPage.DASHBOARD
            "video" -> context.openVideo(apps, onLaunchPackage)
            "settings" -> page = MmiPage.SETTINGS
            "music" -> page = MmiPage.MUSIC
            "radio" -> page = MmiPage.RADIO
            "phone" -> page = MmiPage.BT_HUB
            "nav" -> page = MmiPage.NAV
            "apps" -> page = MmiPage.APPS
            "eq" -> page = MmiPage.EQ
            // The unit's phone-mirroring and weather apps, when installed; else the app list.
            "phonelink" -> apps.findByLabel("phonelink", "phone link", "carlink", "zlink", "easyconnect", "easy connect", "carplay", "autokit")
                ?.let { onLaunchPackage(it.packageName) } ?: run { page = MmiPage.APPS }
            "weather" -> apps.findByLabel("weather")?.let { onLaunchPackage(it.packageName) } ?: run { page = MmiPage.APPS }
        }
    }
    fun stepRing(dir: Int) {
        val i = ringOrder.indexOf(selected).coerceAtLeast(0)
        selected = ringOrder[(i + dir + ringOrder.size) % ringOrder.size]
    }
    var lastTouch by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var idleFade by remember { mutableStateOf(false) }
    // Knob pages: Home moves through the tiles, Apps its own grid, Radio tunes; every other
    // page (Music too: the knob never skips songs) moves a highlight through its buttons
    // and the knob press clicks it.
    val knob = com.openlauncher.app.util.KnobTarget
    val keyScope = rememberCoroutineScope()
    val activity = remember(context) { context.findActivity() }
    fun turn(dir: Int) {
        com.openlauncher.app.util.UiSound.tick()
        when (page) {
            MmiPage.RADIO -> com.openlauncher.app.util.TwBridge.radioStep(dir > 0)
            MmiPage.HOME -> stepRing(dir)
            MmiPage.APPS -> knob.appsStep.tryEmit(dir)
            else -> knob.focusStep(activity, dir > 0)
        }
    }
    fun press() {
        when (page) {
            MmiPage.HOME -> { com.openlauncher.app.util.UiSound.open(); openRing(selected) }
            MmiPage.APPS -> { com.openlauncher.app.util.UiSound.open(); knob.appsOpen.tryEmit(Unit) }
            MmiPage.MUSIC -> playPause()
            MmiPage.RADIO -> Unit
            else -> knob.focusPress(activity)
        }
    }
    fun onCarKey(code: Int) {
        lastTouch = System.currentTimeMillis()
        idleFade = false
        // Dash buttons that open a page get the same small chime as the knob press.
        if (code in PAGE_KEYS) com.openlauncher.app.util.UiSound.open()
        when (code) {
            16 -> page = MmiPage.RADIO
            32 -> page = MmiPage.BT_HUB
            90 -> page = MmiPage.SETTINGS
            24 -> {                                            // TONE -> equaliser
                page = MmiPage.EQ
                // The car's own sound app answers TONE too and opens over MMI: come back on top.
                keyScope.launch {
                    for (wait in longArrayOf(350, 900)) {
                        delay(wait)
                        runCatching {
                            context.startActivity(
                                Intent(context, com.openlauncher.app.MainActivity::class.java)
                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                            )
                        }
                    }
                }
            }
            40 -> if (radioPackage.isNotEmpty()) onLaunchRadio()
            18 -> back()
            33 -> page = MmiPage.MUSIC
            // Big knob: 6 anticlockwise, 5 clockwise. Side keys: 19 down, 21 up.
            6, 19 -> turn(-1)
            5, 21 -> turn(1)
            66, 37 -> press()                                   // knob press / INFO
            in 49..54 -> {
                // Saved favourite channels: tune from anywhere (open Radio to see it).
                com.openlauncher.app.util.TwBridge.radioPresets().getOrNull(code - 49)?.takeIf { it > 0f }?.let {
                    if (page != MmiPage.RADIO) page = MmiPage.RADIO
                    com.openlauncher.app.util.TwBridge.radioTune(it > 200f, it)
                }
            }
            7 -> if (page == MmiPage.RADIO) {
                com.openlauncher.app.util.TwBridge.radioLast().let { (_, f) ->
                    com.openlauncher.app.util.TwBridge.radioSavePreset(0, f)
                }
            }
        }
    }
    val carKeyHandler by rememberUpdatedState(::onCarKey)
    LaunchedEffect(Unit) {
        com.openlauncher.app.util.CarCore.keys.collect { k -> if (k.press == 1) carKeyHandler(k.code) }
    }

    // Idle: with no touch, no music playing and no phone connected for a while,
    // the screen fades over to the map, like a parked MMI.
    val playing = nowPlaying?.isPlaying == true
    // Opening Music selects Bluetooth as the car's audio source, like the vendor
    // launcher's Bluetooth page; otherwise the phone's music never reaches the speakers.
    LaunchedEffect(page) {
        if (page == MmiPage.MUSIC && tw.available && nowPlaying?.controller == null) tw.acquireBluetoothAudio(play = true)
        // Opening Radio makes the radio the car's source again, on the last station.
        // Only with Car Core: the fallback route opens the vendor radio app on top of MMI.
        if (page == MmiPage.RADIO && tw.available && com.openlauncher.app.util.CarCore.active.value) tw.radioResume()
        com.openlauncher.app.util.KnobTarget.radio = page == MmiPage.RADIO
    }
    LaunchedEffect(page, lastTouch, playing) {
        // Like a parked MMI: only from the home screen, never while reading a page or playing music.
        if (page != MmiPage.HOME || playing) return@LaunchedEffect
        delay(IDLE_TO_MAP_MS)
        val busy = BtConnect.anyConnected() || nowPlaying?.isPlaying == true || !MapData.isInstalled(context)
        if (!busy) {
            idleFade = true
            page = MmiPage.NAV
        }
    }

    BoxWithConstraints(
        Modifier.fillMaxSize().mmiBackdrop().pointerInput(Unit) {
            awaitPointerEventScope {
                while (true) {
                    awaitPointerEvent(PointerEventPass.Initial)
                    lastTouch = System.currentTimeMillis()
                    RoadMemory.onTouch()
                    idleFade = false
                }
            }
        },
    ) {
        val barHeight = maxHeight * 0.11f
        // Navigation is full screen with its own rail and buttons, as in the Audi MMI.
        val fullScreen = page == MmiPage.NAV
        Column(Modifier.fillMaxSize()) {
            if (!fullScreen) {
                TopBar(
                    height = barHeight,
                    title = page.title,
                    nowPlaying = nowPlaying,
                    onBack = back,
                    onTapNowPlaying = { page = MmiPage.MUSIC },
                )
            }
            AnimatedContent(
                targetState = page,
                transitionSpec = {
                    if (targetState == MmiPage.NAV || initialState == MmiPage.NAV) {
                        // The map is a separate GPU surface: it can't be faded or scaled with the
                        // page, and animating both pages at once is what made the switch stutter.
                        // Switch instantly; the map fades itself in on its first frame.
                        androidx.compose.animation.EnterTransition.None togetherWith androidx.compose.animation.ExitTransition.None
                    } else {
                        // Short: while it runs both pages are drawn, which is the costly part on the A133.
                        (fadeIn(tween(180, delayMillis = 40)) +
                            scaleIn(tween(220, easing = FastOutSlowInEasing), initialScale = 0.97f)) togetherWith
                            fadeOut(tween(110))
                    }
                },
                modifier = Modifier.weight(1f).fillMaxWidth(),
                label = "mmi_page",
            ) { target ->
                when (target) {
                    MmiPage.HOME -> HomeGrid(selected, onOpen = { openRing(it) })
                    MmiPage.DASHBOARD -> LiveDashboard(location, bearing, voltage, metric)
                    MmiPage.SETTINGS -> SettingsPanel(metric, onSetMetric) {
                        if (it == MmiPage.BLUETOOTH) {
                            bluetoothFrom = MmiPage.SETTINGS
                            pairOnOpen = false
                        }
                        page = it
                    }
                    MmiPage.DISPLAY -> MmiDisplayPage()
                    MmiPage.PERMISSIONS -> MmiPermissionsPage()
                    MmiPage.CLEANUP -> MmiCleanupPage()
                    MmiPage.STORAGE -> MmiStoragePage()
                    MmiPage.SOUND -> MmiSoundPage(apps, onLaunchPackage)
                    MmiPage.BLUETOOTH -> MmiBluetoothPage(apps, onLaunchPackage, startPairing = pairOnOpen)
                    MmiPage.BT_HUB -> MmiBluetoothHub(
                        onTelephone = { page = MmiPage.PHONE },
                        onMusic = { page = MmiPage.MUSIC },
                        onContacts = { page = MmiPage.CONTACTS },
                        onDevices = { pairOnOpen = false; bluetoothFrom = MmiPage.BT_HUB; page = MmiPage.BLUETOOTH },
                        onConnectNew = { pairOnOpen = true; bluetoothFrom = MmiPage.BT_HUB; page = MmiPage.BLUETOOTH },
                    )
                    MmiPage.NAV_SETTINGS -> MmiNavSettingsPage(metric, onSetMetric)
                    MmiPage.NAV -> MmiNavigationPage(location.collectAsStateWithLifecycle().value, metric) { key ->
                        page = when (key) {
                            "radio" -> MmiPage.RADIO
                            "music" -> MmiPage.MUSIC
                            "phone" -> MmiPage.BT_HUB
                            "settings" -> MmiPage.NAV_SETTINGS
                            else -> MmiPage.HOME
                        }
                    }
                    MmiPage.EQ -> MmiEqualiserPage()
                    MmiPage.MUSIC -> MmiMusicPage(
                        state = nowPlaying,
                        onPlayPause = playPause, onNext = next, onPrev = prev,
                        onOpenSource = openMusicApp,
                        onOpenMusicApp = openMusicApp,
                        onOpenBluetooth = { page = MmiPage.BT_HUB },
                    )
                    MmiPage.RADIO -> MmiRadioPage(
                        radio = hardwareRadio,
                        onOpenRadio = openRadioApp,
                        onSeekUp = onRadioSeekUp, onSeekDown = onRadioSeekDown,
                        onFm = onRadioFm, onAm = onRadioAm,
                        onTune = if (com.openlauncher.app.util.TwBridge.available) { am, f -> com.openlauncher.app.util.TwBridge.radioTune(am, f) } else null,
                    )
                    MmiPage.CONTACTS -> MmiContactsPage()
                    MmiPage.PHONE -> MmiPhonePage(apps, onOpenContacts = { page = MmiPage.CONTACTS }) {
                        bluetoothFrom = MmiPage.PHONE
                        pairOnOpen = false
                        page = MmiPage.BLUETOOTH
                    }
                    MmiPage.APPS -> {
                        val prefs = remember(context) { CarPrefs.get(context) }
                        val hidden by prefs.hiddenApps.collectAsStateWithLifecycle()
                        MmiAppsPage(apps, hidden, prefs::toggleHidden, onLaunchPackage)
                    }
                }
            }
            if (!fullScreen) DateBar(barHeight)
        }
        HazardBanner(Modifier.align(Alignment.TopCenter).padding(top = 10.dp))
        CallBar(Modifier.align(Alignment.TopCenter).padding(top = 10.dp))
        ClimatePopup(Modifier.align(Alignment.BottomCenter).padding(bottom = 18.dp))
        DoorBanner(Modifier.align(Alignment.TopCenter).padding(top = 10.dp))
        HomeAppPrompt()
    }
}

/**
 * Until MMI is the unit's home app, offer to make it so on every start, in MMI style.
 * The role dialog (Android 10+) or the home chooser does the switch.
 */
@Composable
private fun HomeAppPrompt() {
    val context = LocalContext.current
    var isHome by remember { mutableStateOf(true) }
    var dismissed by rememberSaveable { mutableStateOf(false) }
    val request = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        isHome = context.isDefaultHome()
    }
    androidx.lifecycle.compose.LifecycleEventEffect(androidx.lifecycle.Lifecycle.Event.ON_RESUME) { isHome = context.isDefaultHome() }
    LaunchedEffect(Unit) {
        delay(1500)
        isHome = context.isDefaultHome()
    }
    if (isHome || dismissed) return
    Box(Modifier.fillMaxSize().background(Color(0xB0000000)).pressable { }, contentAlignment = Alignment.Center) {
        Column(
            Modifier.width(520.dp).mmiPanel().padding(28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("Make MMI your home screen?", color = MmiInk, fontFamily = MmiFont, fontWeight = FontWeight.Light, fontSize = 26.sp)
            Spacer(Modifier.height(10.dp))
            Text(
                "The unit then always starts in MMI, and the Home button and steering-wheel keys come back here.",
                color = MmiDim, fontFamily = MmiFont, fontSize = 16.sp, textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(22.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                ChromePill("Not now", { dismissed = true }, null, Modifier.width(160.dp))
                ChromePill("Make MMI home", {
                    val intent = context.homeRequestIntents().firstOrNull { it.resolveActivity(context.packageManager) != null }
                    if (intent != null) runCatching { request.launch(intent) }
                }, MmiLine.HOME, Modifier.width(240.dp))
            }
        }
    }
}


/** Pothole / speed bump warning from road memory, shown over every page. */
@Composable
private fun HazardBanner(modifier: Modifier) {
    val alert by RoadMemory.alert.collectAsStateWithLifecycle()
    val metric by CarPrefs.get(LocalContext.current).metric.collectAsStateWithLifecycle()
    val current = alert
    androidx.compose.animation.AnimatedVisibility(
        visible = current != null, modifier = modifier,
        enter = fadeIn(tween(200)) + androidx.compose.animation.slideInVertically { -it / 2 },
        exit = fadeOut(tween(300)),
    ) {
        val pulse = rememberInfiniteTransition(label = "hazard")
        val glow by pulse.animateFloat(0.75f, 1f, infiniteRepeatable(tween(450), RepeatMode.Reverse), label = "hazard_glow")
        Row(
            Modifier.graphicsLayer { alpha = glow }.clip(RoundedCornerShape(4.dp))
                .background(Color(0xF0E39A12)).padding(horizontal = 18.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            MmiLineIcon(MmiLine.BUMP, Color.Black, Modifier.size(30.dp))
            Spacer(Modifier.width(12.dp))
            Text(
                (current?.type?.label ?: "") + "  " + (current?.let { com.openlauncher.app.map.formatDistance(it.distanceM, metric) } ?: ""),
                color = Color.Black, fontFamily = MmiFont, fontWeight = FontWeight.Medium, fontSize = 22.sp,
            )
        }
    }
}

/**
 * Slim MMI call bar, driven by the vendor Bluetooth app's call messages: who is
 * calling, a timer once connected, and answer / hang up.
 */
@Composable
private fun CallBar(modifier: Modifier) {
    val call by com.openlauncher.app.util.TwBridge.call.collectAsStateWithLifecycle()
    val state = call.state
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(state) {
        while (state == com.openlauncher.app.util.TwBridge.CallState.ACTIVE) { now = System.currentTimeMillis(); delay(1000) }
    }
    androidx.compose.animation.AnimatedVisibility(
        visible = state != com.openlauncher.app.util.TwBridge.CallState.NONE, modifier = modifier,
        enter = fadeIn(tween(200)) + androidx.compose.animation.slideInVertically { -it / 2 },
        exit = fadeOut(tween(250)),
    ) {
        val who = call.name ?: call.number ?: "Phone"
        val status = when (state) {
            com.openlauncher.app.util.TwBridge.CallState.INCOMING -> "Incoming call"
            com.openlauncher.app.util.TwBridge.CallState.OUTGOING -> "Calling…"
            else -> ((now - call.since).coerceAtLeast(0) / 1000).let { "%d:%02d".format(it / 60, it % 60) }
        }
        Row(
            Modifier.widthIn(min = 420.dp).clip(RoundedCornerShape(6.dp)).background(Color(0xF0101215))
                .border(1.dp, Color(0xFF3A3F46), RoundedCornerShape(6.dp)).padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            MmiLineIcon(MmiLine.CALL, MmiCyan, Modifier.size(26.dp))
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f, fill = false)) {
                Text(who, color = MmiInk, fontFamily = MmiFont, fontSize = 20.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(status, color = MmiDim, fontFamily = MmiFont, fontSize = 14.sp)
            }
            Spacer(Modifier.width(20.dp))
            if (state == com.openlauncher.app.util.TwBridge.CallState.INCOMING) {
                Box(
                    Modifier.size(48.dp).clip(RoundedCornerShape(24.dp)).background(Color(0xFF2E9E4F))
                        .pressable { com.openlauncher.app.util.TwBridge.answer() },
                    contentAlignment = Alignment.Center,
                ) { MmiLineIcon(MmiLine.CALL, Color.White, Modifier.size(24.dp)) }
                Spacer(Modifier.width(12.dp))
            }
            Box(
                Modifier.size(48.dp).clip(RoundedCornerShape(24.dp)).background(Color(0xFFD23C3C))
                    .pressable { com.openlauncher.app.util.TwBridge.hangUp() },
                contentAlignment = Alignment.Center,
            ) { MmiLineIcon(MmiLine.CLOSE, Color.White, Modifier.size(22.dp)) }
        }
    }
}

/** MMI's door warning: which door, boot or bonnet is open (MMI Car Core). */
@Composable
private fun DoorBanner(modifier: Modifier) {
    val car by com.openlauncher.app.util.CarCore.data.collectAsStateWithLifecycle()
    val text = com.openlauncher.app.util.CarCore.doorsText(car.doors)
    androidx.compose.animation.AnimatedVisibility(
        visible = text != null, modifier = modifier,
        enter = fadeIn(tween(200)) + androidx.compose.animation.slideInVertically { -it / 2 },
        exit = fadeOut(tween(300)),
    ) {
        Row(
            Modifier.clip(RoundedCornerShape(6.dp)).background(Color(0xF0101215))
                .border(1.dp, Color(0xFFE39A12), RoundedCornerShape(6.dp)).padding(horizontal = 20.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            MmiLineIcon(MmiLine.CLOSE, Color(0xFFE39A12), Modifier.size(22.dp))
            Spacer(Modifier.width(12.dp))
            Text(text ?: "", color = MmiInk, fontFamily = MmiFont, fontSize = 20.sp)
        }
    }
}

/** MMI's climate pop-up: shown for a few seconds whenever temperature, fan or airflow changes. */
@Composable
private fun ClimatePopup(modifier: Modifier) {
    val climate by com.openlauncher.app.util.Climate.state.collectAsStateWithLifecycle()
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(climate.changedAt) {
        if (climate.changedAt == 0L || !com.openlauncher.app.util.Climate.replaceVendorPopup()) return@LaunchedEffect
        visible = true
        delay(3500)
        visible = false
    }
    fun temp(t: Float?, lo: Boolean, hi: Boolean) = when {
        lo -> "LO"
        hi -> "HI"
        t == null -> "--"
        t % 1f == 0f -> "${t.toInt()}°"
        else -> "%.1f°".format(t)
    }
    val airflow = when (climate.airflow) {
        1 -> "Feet"; 2 -> "Face"; 3 -> "Face + feet"; 4 -> "Feet + windscreen"; 5 -> "Windscreen"; 6 -> "Face + windscreen"; 7 -> "All"
        else -> ""
    }
    androidx.compose.animation.AnimatedVisibility(
        visible = visible, modifier = modifier,
        enter = fadeIn(tween(180)) + androidx.compose.animation.slideInVertically { it / 2 },
        exit = fadeOut(tween(300)),
    ) {
        Row(
            Modifier.clip(RoundedCornerShape(6.dp)).background(Color(0xF0101215))
                .border(1.dp, Color(0xFF3A3F46), RoundedCornerShape(6.dp)).padding(horizontal = 28.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(temp(climate.left, climate.leftLo, climate.leftHi), color = MmiInk, fontFamily = MmiFont, fontWeight = FontWeight.Light, fontSize = 40.sp)
            Spacer(Modifier.width(32.dp))
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                // Fan level as bars.
                Row(verticalAlignment = Alignment.Bottom) {
                    repeat(7) { i ->
                        Box(
                            Modifier.padding(horizontal = 2.dp).size(width = 7.dp, height = (8 + i * 3).dp)
                                .background(if (i < climate.fan) MmiCyan else Color(0xFF2A2E34)),
                        )
                    }
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    listOfNotNull(
                        airflow.ifEmpty { null },
                        "A/C".takeIf { climate.ac }, "AUTO".takeIf { climate.auto }, "Recirc".takeIf { climate.recirculation },
                        "Defrost".takeIf { climate.frontDefrost },
                    ).joinToString("  ·  ").ifEmpty { if (climate.on) "Climate" else "Climate off" },
                    color = MmiDim, fontFamily = MmiFont, fontSize = 14.sp,
                )
            }
            Spacer(Modifier.width(32.dp))
            Text(temp(climate.right, climate.rightLo, climate.rightHi), color = MmiInk, fontFamily = MmiFont, fontWeight = FontWeight.Light, fontSize = 40.sp)
        }
    }
}

private const val IDLE_TO_MAP_MS = 120_000L

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
    val carProfile = RoadMemory.carState.collectAsStateWithLifecycle().value
    val carName = carProfile.label
    // Software update: new builds download in the background; one tap installs.
    val updater = com.openlauncher.app.util.AppUpdater
    val installedVersion = remember { updater.installedVersion(context) }
    val readyVersion by updater.ready.collectAsStateWithLifecycle()
    val updateFailure by updater.failure.collectAsStateWithLifecycle()
    var latestVersion by remember { mutableStateOf<Long?>(null) }
    var updateStatus by remember { mutableStateOf<String?>(null) }
    var updateBusy by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { latestVersion = updater.latestVersion() }
    val updateLabel = "Software update: " + when {
        updateStatus != null -> updateStatus
        updateFailure != null -> "didn't install, tap to retry"
        readyVersion != null -> "new version ready, tap to install"
        latestVersion == null -> "tap to check"
        latestVersion!! > installedVersion -> "new version, tap to update"
        else -> "up to date"
    }
    val runUpdate: () -> Unit = {
        if (!updateBusy) scope.launch {
            updateBusy = true
            try {
                if (readyVersion == null) {
                    updateStatus = "checking…"
                    val got = updater.prefetch(context) { updateStatus = "downloading $it%" }
                    latestVersion = updater.latestVersion()
                    if (got == null) {
                        updateStatus = if (latestVersion == null) "no internet, connect Wi-Fi and tap again" else "up to date"
                        return@launch
                    }
                }
                updateStatus = if (updater.installReady(context)) "installing…" else "allow MMI to install apps, then tap again"
            } finally {
                updateBusy = false
            }
        }
    }
    var silentUpdates by remember { mutableStateOf(updater.isDeviceOwner(context)) }
    var mmiCallBar by remember { mutableStateOf(runCatching { com.openlauncher.app.util.TwBridge.mmiCallBar() }.getOrDefault(true)) }
    var mmiClimatePopup by remember { mutableStateOf(runCatching { com.openlauncher.app.util.Climate.replaceVendorPopup() }.getOrDefault(true)) }
    var keepReverseMusic by remember { mutableStateOf(com.openlauncher.app.util.TwBridge.keepMusicInReverse()) }
    var uiSounds by remember { mutableStateOf(com.openlauncher.app.util.UiSound.enabled) }
    var popupsHidden by remember { mutableStateOf(com.openlauncher.app.service.MediaListenerService.popupsHidden(context)) }
    val rows: List<Pair<String, () -> Unit>> = listOfNotNull(
        updateLabel to runUpdate,
        // Device owner (set once over ADB) only exists for silent updates; this undoes it.
        if (silentUpdates) "Silent updates: on (tap to turn off)" to {
            runCatching {
                (context.getSystemService(Context.DEVICE_POLICY_SERVICE) as android.app.admin.DevicePolicyManager)
                    .clearDeviceOwnerApp(context.packageName)
            }
            silentUpdates = updater.isDeviceOwner(context)
        } else null,
        "Car: ${carName}" to {
            val next = com.openlauncher.app.map.CarProfile.entries[(RoadMemory.car.ordinal + 1) % com.openlauncher.app.map.CarProfile.entries.size]
            RoadMemory.setCar(next)
        },
        (if (uiSounds) "Touch sounds: on" else "Touch sounds: off") to {
            uiSounds = !uiSounds
            com.openlauncher.app.util.UiSound.setEnabled(uiSounds)
        },
        "Display" to { onOpen(MmiPage.DISPLAY) },
        "Sound" to { onOpen(MmiPage.SOUND) },
        "Bluetooth" to { onOpen(MmiPage.BLUETOOTH) },
        "Wi-Fi (for map downloads)" to {
            // Android 10+ shows Wi-Fi as a panel over MMI instead of leaving it.
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                context.launchFirst(Intent(Settings.Panel.ACTION_WIFI), Intent(Settings.ACTION_WIFI_SETTINGS))
            } else {
                context.launchFirst(Intent(Settings.ACTION_WIFI_SETTINGS))
            }
            Unit
        },
        "Navigation" to { onOpen(MmiPage.NAV_SETTINGS) },
        (if (metric) "Units: km/h, metres" else "Units: mph, feet") to { onSetMetric(!metric) },
        (if (mmiCallBar) "Phone calls: MMI call bar" else "Phone calls: car's own bubble") to {
            mmiCallBar = !mmiCallBar
            com.openlauncher.app.util.TwBridge.setMmiCallBar(mmiCallBar)
        },
        (if (mmiClimatePopup) "Climate pop-up: MMI style" else "Climate pop-up: car's own") to {
            mmiClimatePopup = !mmiClimatePopup
            com.openlauncher.app.util.Climate.setReplaceVendorPopup(mmiClimatePopup)
        },
        (if (keepReverseMusic) "Music while reversing: keeps playing" else "Music while reversing: muted, resumes after") to {
            keepReverseMusic = !keepReverseMusic
            com.openlauncher.app.util.TwBridge.setKeepMusicInReverse(keepReverseMusic)
        },
        (if (popupsHidden) "Android pop-ups: hidden (tap to show)" else "Android pop-ups: shown (tap to hide)") to {
            popupsHidden = !popupsHidden
            com.openlauncher.app.service.MediaListenerService.setPopupsHidden(context, popupsHidden)
        },
        (if (introSet) "Startup video: on (tap to remove)" else "Startup video: import from USB") to {
            if (introSet) {
                IntroVideo.remove(context)
                introSet = false
            } else {
                pickIntro.launch(arrayOf("video/*"))
            }
        },
        "Permissions" to { onOpen(MmiPage.PERMISSIONS) },
        "Date & time" to { context.launchFirst(Intent(Settings.ACTION_DATE_SETTINGS)); Unit },
        "Unit cleanup & speed" to { onOpen(MmiPage.CLEANUP) },
        "Storage & about" to { onOpen(MmiPage.STORAGE) },
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

private tailrec fun Context.findActivity(): android.app.Activity? = when (this) {
    is android.app.Activity -> this
    is android.content.ContextWrapper -> baseContext.findActivity()
    else -> null
}

/** Dash buttons that open an MMI page (Radio, Phone, Setup, Tone, Back, Media). */
private val PAGE_KEYS = setOf(16, 32, 90, 24, 18, 33)
