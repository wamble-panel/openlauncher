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
import com.openlauncher.app.map.PlaceSearch
import com.openlauncher.app.model.HardwareRadioState
import com.openlauncher.app.service.NavGuidance
import com.openlauncher.app.map.NavigationEngine
import com.openlauncher.app.service.NavigationTracker
import com.openlauncher.app.util.BtConnect
import com.openlauncher.app.util.CarPicture
import com.openlauncher.app.util.ThemePack
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
    NAV("Navigation"), SYSTEM("System check", true),
    PERMISSIONS("Permissions", true), CLEANUP("Unit cleanup & speed", true), TUNER("System tuning", true), STORAGE("Storage & about", true), DISPLAY("Display", true), SOUND("Sound", true), BLUETOOTH("Bluetooth devices", true), NAV_SETTINGS("Navigation settings", true),
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
    // Flows, not values: GPS and compass update often, and collecting them only
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

    // Idle: with no touch, no music playing and no phone connected for a while,
    // the screen fades over to the map, like a parked MMI.
    var lastTouch by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var idleFade by remember { mutableStateOf(false) }
    val playing = nowPlaying?.isPlaying == true
    // Opening Music selects Bluetooth as the car's audio source, like the vendor
    // launcher's Bluetooth page; otherwise the phone's music never reaches the speakers.
    LaunchedEffect(page) {
        if (page == MmiPage.MUSIC && tw.available && nowPlaying?.controller == null) tw.acquireBluetoothAudio(play = true)
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
        val cardWidth = maxWidth * 0.24f
        // Theme pack: the real MMI floor with its ring behind the home page, icons placed on that ring.
        val theme by ThemePack.theme.collectAsStateWithLifecycle()
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
                    if (targetState == MmiPage.NAV && idleFade) {
                        fadeIn(tween(1100, delayMillis = 200)) togetherWith fadeOut(tween(800))
                    } else {
                        (fadeIn(tween(260, delayMillis = 60)) +
                            scaleIn(tween(320, easing = FastOutSlowInEasing), initialScale = 0.94f)) togetherWith
                            (fadeOut(tween(180)) + scaleOut(tween(220), targetScale = 1.04f))
                    }
                },
                modifier = Modifier.weight(1f).fillMaxWidth(),
                label = "mmi_page",
            ) { target ->
                when (target) {
                    MmiPage.HOME -> BoxWithConstraints(Modifier.fillMaxSize()) {
                        val themeBackground = theme?.background
                        val themeRing = theme?.ring
                        // The artwork is stretched over the home area (a gentle squeeze for the floor),
                        // so its ring maps straight from the pack's fractions and stays left of the card.
                        val ring = if (themeBackground != null && themeRing != null) {
                            OrbitRing(maxWidth * themeRing[0], maxHeight * themeRing[1], maxWidth * themeRing[2], maxHeight * themeRing[3])
                        } else null
                        if (themeBackground != null) {
                            Image(themeBackground, contentDescription = null, contentScale = ContentScale.FillBounds, modifier = Modifier.fillMaxSize())
                        }
                        Row(Modifier.fillMaxSize()) {
                        Orbit(
                            modifier = Modifier.weight(1f).fillMaxHeight(),
                            ring = ring,
                            icons = theme?.icons.orEmpty(),
                            selected = selected,
                            onSelect = { key ->
                                selected = key
                                when (key) {
                                    "dashboard" -> page = MmiPage.DASHBOARD
                                    "video" -> context.openVideo(apps, onLaunchPackage)
                                    "settings" -> page = MmiPage.SETTINGS
                                    "music" -> page = MmiPage.MUSIC
                                    "radio" -> page = MmiPage.RADIO
                                    "phone" -> page = MmiPage.BT_HUB
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
                                LiveDriveCard(location, bearing, voltage, metric, Modifier.fillMaxSize())
                            }
                        }
                    }
                    }
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
                    MmiPage.TUNER -> MmiTunerPage()
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
                    MmiPage.SYSTEM -> MmiSystemCheckPage(apps, location.collectAsStateWithLifecycle().value)
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
        HomeAppPrompt()
        PerfReadout(Modifier.align(Alignment.BottomEnd).padding(end = 6.dp, bottom = 6.dp))
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

/**
 * Performance readout (Settings > Unit cleanup & speed): frames per second, share of
 * slow frames (>33 ms) and free memory, so tuning uses real numbers from the unit.
 * Only runs while switched on — measuring keeps the screen drawing every frame.
 */
@Composable
private fun PerfReadout(modifier: Modifier) {
    val context = LocalContext.current
    val on by CarPrefs.get(context).perfOverlay.collectAsStateWithLifecycle()
    if (!on) return
    var text by remember { mutableStateOf("…") }
    LaunchedEffect(Unit) {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
        var last = 0L
        var frames = 0
        var slow = 0
        var windowStart = 0L
        while (true) {
            withFrameNanos { t ->
                if (last != 0L) {
                    frames++
                    if (t - last > 33_000_000L) slow++
                }
                last = t
                if (windowStart == 0L) windowStart = t
                if (t - windowStart >= 2_000_000_000L) {
                    val secs = (t - windowStart) / 1e9
                    val mem = android.app.ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
                    text = "%.0f fps · %d%% slow · %d MB free".format(frames / secs, if (frames > 0) slow * 100 / frames else 0, mem.availMem shr 20)
                    frames = 0; slow = 0; windowStart = t
                }
            }
        }
    }
    Text(
        text, color = Color(0xFF4CD964), fontFamily = MmiFont, fontSize = 12.sp,
        modifier = modifier.background(Color(0xC0000000)).padding(horizontal = 8.dp, vertical = 3.dp),
    )
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

// ── Orbit menu ───────────────────────────────────────────────────────────────

/**
 * Light trails under the car, like the MMI start screen: cyan streaks run along the
 * floor from under the car back past its tail, with bright pulses flowing backwards
 * as if it were driving. Only this small canvas redraws for the animation.
 */
@Composable
private fun DriveTrails(carX: Dp, carY: Dp, carW: Dp, carH: Dp, intro: Float) {
    // Pulses flow only while driving; parked, the trails stand still so the home screen isn't redrawn every frame.
    val context = LocalContext.current
    val moving = (com.openlauncher.app.util.CarLocation.get(context).location.collectAsStateWithLifecycle().value?.speedMps ?: 0f) > 0f
    val phase = if (moving) {
        val flow = rememberInfiniteTransition(label = "trails")
        flow.animateFloat(0f, 1f, infiniteRepeatable(tween(2400, easing = androidx.compose.animation.core.LinearEasing)), label = "trail_phase").value
    } else 0.35f
    Canvas(Modifier.fillMaxSize()) {
        if (intro <= 0.01f) return@Canvas
        val cw = carW.toPx()
        // Ground line under the wheels; the car faces front-right, so "behind" is left and a little up.
        val groundY = carY.toPx() + carH.toPx() * 0.9f
        val frontX = carX.toPx() + cw * 0.95f
        val length = cw * 1.9f * intro
        val dx = -1f
        val dy = -0.10f
        val norm = kotlin.math.sqrt(dx * dx + dy * dy)
        val ux = dx / norm
        val uy = dy / norm
        val lanes = listOf(-0.050f to 0.55f, -0.018f to 0.9f, 0.016f to 1f, 0.048f to 0.6f)
        lanes.forEachIndexed { i, (offset, strength) ->
            val start = Offset(frontX, groundY + size.height * offset)
            val end = Offset(start.x + ux * length, start.y + uy * length + size.height * offset * 0.6f)
            val trail = Brush.linearGradient(
                0f to Color.Transparent,
                0.12f to MmiCyan.copy(alpha = 0.85f * strength),
                0.45f to MmiCyan.copy(alpha = 0.45f * strength),
                1f to Color.Transparent,
                start = start, end = end,
            )
            // Soft glow, then the crisp streak.
            drawLine(trail, start, end, 9.dp.toPx() * strength, StrokeCap.Round, alpha = 0.25f)
            drawLine(trail, start, end, 1.6.dp.toPx(), StrokeCap.Round)
            // A bright pulse flowing backwards along the streak.
            val t = (phase + i * 0.27f) % 1f
            val p0 = Offset(start.x + (end.x - start.x) * t, start.y + (end.y - start.y) * t)
            val seg = 0.16f
            val p1 = Offset(p0.x + (end.x - start.x) * seg, p0.y + (end.y - start.y) * seg)
            drawLine(
                Brush.linearGradient(listOf(Color(0xFFE6FAFF).copy(alpha = (1f - t) * strength), Color.Transparent), p0, p1),
                p0, p1, 2.4.dp.toPx(), StrokeCap.Round,
            )
        }
        // Cyan floor glow right under the car.
        val gx = carX.toPx() + cw * 0.5f
        drawOval(
            Brush.radialGradient(listOf(MmiCyan.copy(alpha = 0.30f * intro), Color.Transparent), Offset(gx, groundY), cw * 0.55f),
            topLeft = Offset(gx - cw * 0.55f, groundY - size.height * 0.035f), size = Size(cw * 1.1f, size.height * 0.07f),
        )
    }
}

/** Ring centre line in the Orbit's own coordinates (from a theme pack background). */
private data class OrbitRing(val cx: Dp, val cy: Dp, val rx: Dp, val ry: Dp)

private fun MmiGlyph.themeKey(): String = when (this) {
    MmiGlyph.GAUGE -> "dashboard"
    MmiGlyph.REEL -> "video"
    MmiGlyph.GEARS -> "settings"
    MmiGlyph.NOTE -> "music"
    MmiGlyph.HANDSET -> "phone"
    MmiGlyph.FLAG -> "navigation"
    MmiGlyph.APPS -> "apps"
    MmiGlyph.RADIO -> "radio"
}

/** Ring positions with theme artwork (APPS takes 124°). */
private val THEMED_ANGLES = mapOf(
    "dashboard" to -112f, "video" to -78f, "settings" to -44f, "music" to -10f,
    "radio" to 24f, "phone" to 58f, "nav" to 92f,
)

private data class OrbitItem(val key: String, val label: String, val glyph: MmiGlyph, val angle: Float)

private val ORBIT_ITEMS = listOf(
    OrbitItem("dashboard", "Dashboard", MmiGlyph.GAUGE, -110f),
    OrbitItem("video", "Video", MmiGlyph.REEL, -74f),
    OrbitItem("settings", "Settings", MmiGlyph.GEARS, -38f),
    OrbitItem("music", "Music", MmiGlyph.NOTE, -2f),
    OrbitItem("radio", "Radio", MmiGlyph.RADIO, 34f),
    OrbitItem("phone", "Bluetooth", MmiGlyph.HANDSET, 70f),
    OrbitItem("nav", "Navigation", MmiGlyph.FLAG, 108f),
)

@Composable
private fun Orbit(
    modifier: Modifier,
    selected: String,
    onSelect: (String) -> Unit,
    ring: OrbitRing? = null,
    icons: Map<String, androidx.compose.ui.graphics.ImageBitmap> = emptyMap(),
) {
    // One-shot entrance: the ring draws in, the car slides in, items pop in one by one.
    val intro = remember { Animatable(0f) }
    LaunchedEffect(Unit) { intro.animateTo(1f, tween(900, easing = FastOutSlowInEasing)) }

    BoxWithConstraints(modifier) {
        val w = maxWidth
        val h = maxHeight
        val cx = ring?.cx ?: (w * 0.42f)
        val cy = ring?.cy ?: (h * 0.50f)
        val rx = ring?.rx ?: (w * 0.38f)
        val ry = ring?.ry ?: (h * 0.44f)
        val iconSize = h * 0.13f
        val labelSize = with(LocalDensity.current) { (h * 0.05f).toSp() }
        // With theme artwork the car sits inside that ring's disc; otherwise the drawn layout.
        // The car stands in the middle of the ring's disc (drawn or theme artwork).
        val carW = rx * 1.05f
        // The owner's own photo of the selected car (imported in Settings), else the built-in one.
        val carProfile by RoadMemory.carState.collectAsStateWithLifecycle()
        val pictureVersion by CarPicture.version.collectAsStateWithLifecycle()
        val pictureContext = LocalContext.current
        val ownPicture by produceState<androidx.compose.ui.graphics.ImageBitmap?>(null, carProfile, pictureVersion) {
            value = CarPicture.load(pictureContext, carProfile)?.asImageBitmap()
        }
        val astra = carProfile == com.openlauncher.app.map.CarProfile.ASTRA_J
        val carH = carW * (ownPicture?.let { it.height.toFloat() / it.width } ?: if (astra) 540f / 900f else 473f / 900f)
        val carX = cx - carW / 2
        val carY = cy - carH * 0.55f

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

            if (ring == null) {
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

            }

            val gy = carY.toPx() + carH.toPx() * 0.93f
            val gx = carX.toPx() + carW.toPx() * 0.47f
            drawOval(
                Color(0xB0000000),
                topLeft = Offset(gx - carW.toPx() * 0.34f, gy - size.height * 0.04f), size = Size(carW.toPx() * 0.68f, size.height * 0.045f),
            )
        }

        DriveTrails(carX, carY, carW, carH, intro.value)

        val slide = with(LocalDensity.current) { 60.dp.toPx() }
        val carModifier = Modifier.offset(x = carX, y = carY).size(carW, carH).graphicsLayer {
            alpha = intro.value
            translationX = (1f - intro.value) * -slide
        }
        val picture = ownPicture
        if (picture != null) {
            Image(picture, contentDescription = "Your car", contentScale = ContentScale.Fit, modifier = carModifier)
        } else {
            Image(
                painterResource(if (astra) R.drawable.mmi_car_astra else R.drawable.mmi_car),
                contentDescription = "Your car", contentScale = ContentScale.Fit, modifier = carModifier,
            )
        }

        ORBIT_ITEMS.forEachIndexed { index, item ->
            // The artwork's ring runs off the left edge, so APPS joins the ring and the
            // lower entries spread out a little to keep clear of it.
            val angle = if (ring != null) THEMED_ANGLES[item.key] ?: item.angle else item.angle
            val rad = Math.toRadians(angle.toDouble())
            OrbitEntry(
                label = item.label, glyph = item.glyph, image = icons[item.glyph.themeKey()],
                x = cx + rx * cos(rad).toFloat() - iconSize / 2,
                y = cy + ry * sin(rad).toFloat() - iconSize / 2,
                iconSize = iconSize, labelSize = labelSize,
                selected = selected == item.key,
                // Lower-right entries sit where the ring climbs; drop their label below it.
                labelDrop = if (angle in 50f..90f) iconSize * 0.42f else 0.dp,
                appear = { ((intro.value - 0.25f - index * 0.07f) / 0.3f).coerceIn(0f, 1f) },
                onClick = { onSelect(item.key) },
            )
        }
        val appsRad = Math.toRadians(124.0)
        OrbitEntry(
            label = "APPS", glyph = MmiGlyph.APPS, image = icons["apps"],
            x = if (ring != null) cx + rx * cos(appsRad).toFloat() - iconSize / 2 else w * 0.02f,
            y = if (ring != null) cy + ry * sin(appsRad).toFloat() - iconSize / 2 else h * 0.72f,
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
    image: androidx.compose.ui.graphics.ImageBitmap? = null,
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
            if (image != null) {
                Image(image, contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
            } else {
                MmiIcon(glyph, Modifier.fillMaxSize())
            }
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
private fun LiveDriveCard(
    location: StateFlow<LocationData?>, bearing: StateFlow<Float>, voltage: StateFlow<Float?>, metric: Boolean, modifier: Modifier,
) {
    val loc by location.collectAsStateWithLifecycle()
    val heading by bearing.collectAsStateWithLifecycle()
    val volts by voltage.collectAsStateWithLifecycle()
    DriveCard(loc, heading, volts, metric, modifier)
}

@Composable
private fun LiveDashboard(location: StateFlow<LocationData?>, bearing: StateFlow<Float>, voltage: StateFlow<Float?>, metric: Boolean) {
    val loc by location.collectAsStateWithLifecycle()
    val heading by bearing.collectAsStateWithLifecycle()
    val volts by voltage.collectAsStateWithLifecycle()
    MmiDashboardPage(loc, heading, volts, metric)
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
    val carProfile = RoadMemory.carState.collectAsStateWithLifecycle().value
    val carName = carProfile.label
    val pictureVersion by CarPicture.version.collectAsStateWithLifecycle()
    val hasPicture = remember(carProfile, pictureVersion) { CarPicture.has(context, carProfile) }
    val themeState = ThemePack.theme.collectAsStateWithLifecycle().value
    val themeOn = themeState != null
    val themeImported = remember(themeState) { ThemePack.isImported(context) }
    val pickTheme = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch { ThemePack.import(context, uri) }
    }
    val pickPicture = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch { CarPicture.import(context, uri, carProfile) }
    }
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
    var mmiClimatePopup by remember { mutableStateOf(runCatching { com.openlauncher.app.util.Climate.replaceVendorPopup() }.getOrDefault(true)) }
    var keepReverseMusic by remember { mutableStateOf(com.openlauncher.app.util.TwBridge.keepMusicInReverse()) }
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
        (if (themeOn) "Look: MMI artwork (tap for drawn)" else "Look: drawn (tap for MMI artwork)") to {
            ThemePack.setEnabled(context, !themeOn)
        },
        (if (themeImported) "Theme pack: yours (tap to remove)" else "Theme pack: import from USB") to {
            if (themeImported) ThemePack.remove(context) else pickTheme.launch(arrayOf("application/zip", "application/octet-stream", "*/*"))
        },
        (if (hasPicture) "Car picture: yours (tap to remove)" else "Car picture: import from USB") to {
            if (hasPicture) CarPicture.remove(context, carProfile) else pickPicture.launch(arrayOf("image/*"))
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
        "System tuning" to { onOpen(MmiPage.TUNER) },
        "Storage & about" to { onOpen(MmiPage.STORAGE) },
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
