package com.openlauncher.app.ui.screen

import android.graphics.Bitmap
import android.graphics.Canvas as AndroidCanvas
import android.graphics.Paint
import android.graphics.Path as AndroidPath
import android.net.Uri
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.lifecycle.compose.LifecycleEventEffect
import com.openlauncher.app.map.MapInstaller
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Image
import androidx.compose.foundation.Canvas
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.tween
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.openlauncher.app.map.Destinations
import com.openlauncher.app.map.ManeuverType
import com.openlauncher.app.map.MapData
import com.openlauncher.app.map.NavState
import com.openlauncher.app.map.NavigationEngine
import com.openlauncher.app.map.Place
import com.openlauncher.app.map.PlaceSearch
import com.openlauncher.app.map.distanceMeters
import com.openlauncher.app.map.formatDistance
import com.openlauncher.app.util.LocationData
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import com.openlauncher.app.map.HazardType
import com.openlauncher.app.map.RoadMemory
import com.openlauncher.app.map.RetroMapView
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

private enum class NavScreen { MAP, DESTINATION, SEARCH, POI, LIST, DETAILS, IMPORT }

/** A position on the map. */
internal data class LatLng(val latitude: Double, val longitude: Double)

private val CAIRO = LatLng(30.0444, 31.2357)
private val RouteGreen = Color(0xFF4CD964)

private data class PoiCategory(val label: String, val icon: MmiLine, val kinds: List<String>)

private val POI_CATEGORIES = listOf(
    PoiCategory("Gas station", MmiLine.FUEL, listOf("amenity:fuel")),
    PoiCategory("Parking", MmiLine.PIN, listOf("amenity:parking")),
    PoiCategory("Restaurant", MmiLine.PIN, listOf("amenity:restaurant", "amenity:fast_food")),
    PoiCategory("Café", MmiLine.PIN, listOf("amenity:cafe")),
    PoiCategory("Hospital", MmiLine.PLUS, listOf("amenity:hospital", "amenity:clinic")),
    PoiCategory("Pharmacy", MmiLine.PLUS, listOf("amenity:pharmacy")),
    PoiCategory("ATM / Bank", MmiLine.CARD, listOf("amenity:atm", "amenity:bank")),
    PoiCategory("Mosque", MmiLine.PIN, listOf("amenity:place_of_worship")),
)

/**
 * MMI navigation: fast flat offline map (roads and route, drawn by OpenGL),
 * Select destination grid, intelligent search with an on-screen EN/AR keyboard,
 * points of interest, favourites and recent destinations.
 */
@Composable
internal fun MmiNavigationPage(location: LocationData?, metric: Boolean, onOpenPage: (String) -> Unit) {
    val context = LocalContext.current
    var installed by remember { mutableStateOf(MapData.isInstalled(context)) }
    var screen by remember { mutableStateOf(if (installed) NavScreen.MAP else NavScreen.IMPORT) }
    val search = remember(installed) { PlaceSearch(MapData.search(context)) }
    val destinations = remember { Destinations(context) }
    val nav by NavigationEngine.state.collectAsStateWithLifecycle()
    var detail by remember { mutableStateOf<Place?>(null) }
    var listTitle by remember { mutableStateOf("") }
    var listItems by remember { mutableStateOf<List<Place>>(emptyList()) }
    var listReturn by remember { mutableStateOf(NavScreen.DESTINATION) }
    val scope = rememberCoroutineScope()
    var mapGeneration by remember { mutableIntStateOf(0) }
    // The old map-tile file is no longer used (the map is drawn from the roads): free its space.
    LaunchedEffect(Unit) { kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { runCatching { MapData.tiles(context).delete() } } }

    BackHandler(enabled = screen != NavScreen.MAP && (screen != NavScreen.IMPORT || installed)) {
        screen = when (screen) {
            NavScreen.DETAILS -> listReturn
            NavScreen.LIST -> if (listReturn == NavScreen.POI) NavScreen.POI else NavScreen.DESTINATION
            NavScreen.POI, NavScreen.SEARCH, NavScreen.IMPORT -> NavScreen.DESTINATION
            else -> NavScreen.MAP
        }
    }

    val here = location?.let { LatLng(it.latitude, it.longitude) }
    val openList: (String, List<Place>, NavScreen) -> Unit = { title, items, from ->
        listTitle = title
        listItems = items
        listReturn = from
        screen = NavScreen.LIST
    }
    val openDetails: (Place, NavScreen) -> Unit = { place, from ->
        detail = place
        listReturn = from
        screen = NavScreen.DETAILS
    }

    Box(Modifier.fillMaxSize()) {
        if (installed) key(mapGeneration) {
            NavMap(
                here = here,
                nav = nav,
                metric = metric,
                altitude = location?.altitude,
                onOpenDestination = { screen = NavScreen.DESTINATION },
                onSearch = { screen = NavScreen.SEARCH },
                onOpenPage = onOpenPage,
                onEnd = { NavigationEngine.stop() },
            )
        }
        AnimatedContent(
            targetState = screen,
            transitionSpec = { fadeIn(tween(200)) togetherWith fadeOut(tween(150)) },
            label = "nav_screen",
        ) { s ->
            when (s) {
                NavScreen.MAP -> Spacer(Modifier.fillMaxSize())
                NavScreen.IMPORT -> ImportPanel(
                    onBack = if (installed) ({ screen = NavScreen.DESTINATION }) else null,
                ) {
                    installed = true
                    mapGeneration++
                    NavigationEngine.reloadData()
                    screen = NavScreen.MAP
                }
                NavScreen.DESTINATION -> NavPanel("Select destination", onBack = { screen = NavScreen.MAP }) {
                    DestinationGrid(
                        onSearch = { screen = NavScreen.SEARCH },
                        onRecents = { openList("Last destinations", destinations.recents(), NavScreen.DESTINATION) },
                        onFavorites = { openList("Favorites", destinations.favorites(), NavScreen.DESTINATION) },
                        onPoi = { screen = NavScreen.POI },
                        onHome = {
                            destinations.favorites().firstOrNull { it.title.equals("home", true) }
                                ?.let { openDetails(it, NavScreen.DESTINATION) }
                                ?: run { screen = NavScreen.SEARCH }
                        },
                        onMap = { screen = NavScreen.MAP },
                        onMapData = { screen = NavScreen.IMPORT },
                    )
                }
                NavScreen.POI -> NavPanel("Points of interest", onBack = { screen = NavScreen.DESTINATION }) {
                    PoiGrid { category ->
                        scope.launch {
                            val at = here ?: CAIRO
                            openList(category.label, search.nearby(category.kinds, at.latitude, at.longitude), NavScreen.POI)
                        }
                    }
                }
                NavScreen.LIST -> NavPanel(listTitle, onBack = { screen = listReturn }) {
                    PlaceList(listItems, here, metric) { openDetails(it, NavScreen.LIST) }
                }
                NavScreen.SEARCH -> SearchPanel(
                    search = search, here = here, metric = metric,
                    onBack = { screen = NavScreen.DESTINATION },
                    onPick = { openDetails(it, NavScreen.SEARCH) },
                    onCategory = { category ->
                        scope.launch {
                            val at = here ?: CAIRO
                            openList(category.label, search.nearby(category.kinds, at.latitude, at.longitude), NavScreen.SEARCH)
                        }
                    },
                )
                NavScreen.DETAILS -> {
                    val place = detail
                    if (place != null) {
                        NavPanel("Details", onBack = { screen = listReturn }) {
                            DetailsPanel(
                                place = place, here = here, metric = metric, destinations = destinations,
                                onStart = {
                                    destinations.addRecent(place)
                                    NavigationEngine.navigateTo(place)
                                    screen = NavScreen.MAP
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}

// ── Map ──────────────────────────────────────────────────────────────────────

@Composable
private fun NavMap(
    here: LatLng?,
    nav: NavState,
    metric: Boolean,
    altitude: Double?,
    onOpenDestination: () -> Unit,
    onSearch: () -> Unit,
    onOpenPage: (String) -> Unit,
    onEnd: () -> Unit,
) {
    val context = LocalContext.current
    // North-up or heading-up is remembered across visits and restarts.
    val navPrefs = remember { context.getSharedPreferences("mmi_nav", android.content.Context.MODE_PRIVATE) }
    var northUp by remember { mutableStateOf(navPrefs.getBoolean("north_up", false)) }
    var markMenu by remember { mutableStateOf(false) }
    var following by remember { mutableStateOf(true) }
    var toast by remember { mutableStateOf<String?>(null) }
    val course = nav.course
    val destination = nav.destination
    val route = nav.route

    LaunchedEffect(toast) {
        if (toast != null) {
            delay(2200)
            toast = null
        }
    }

    // The map: flat roads drawn by OpenGL on its own thread (see RetroMapView).
    val map = remember { RetroMapView(context) }
    map.onPan = { following = false }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle, map) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> map.onResume()
                Lifecycle.Event.ON_PAUSE -> map.onPause()
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            map.onPause()
        }
    }

    // Closer in town, further out at speed; the zoom buttons step from there.
    val zoomBand = when {
        nav.speedKmh > 90f -> 0
        nav.speedKmh > 50f -> 1
        else -> 2
    }
    var zoomOffset by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(zoomBand, zoomOffset) {
        map.spanMeters = listOf(1600.0, 1100.0, 750.0)[zoomBand] * Math.pow(1.6, -zoomOffset.toDouble())
        map.wake()
    }
    LaunchedEffect(following, northUp) {
        map.northUp = northUp
        if (following) map.recenter() else map.following = false
    }
    // Starting guidance always brings the camera back onto the car.
    LaunchedEffect(route != null, destination) { if (route != null) following = true }
    LaunchedEffect(here, course, nav.speedKmh) {
        val pos = here ?: return@LaunchedEffect
        map.setFix(pos.latitude, pos.longitude, nav.speedKmh / 3.6, course)
    }
    // Road route from the offline router; a direct line when there is none yet.
    val directFrom = if (route == null) here else null
    LaunchedEffect(route, destination, directFrom) {
        map.setRoute(route, destination, directFrom?.let { doubleArrayOf(it.latitude, it.longitude) })
    }

    Box(Modifier.fillMaxSize()) {
        AndroidView(factory = { map }, modifier = Modifier.fillMaxSize())

        // Left rail, as in the Audi MMI: quick jumps without leaving the map.
        NavRail(Modifier.align(Alignment.CenterStart), onOpenPage)

        Column(
            Modifier.align(Alignment.TopStart).padding(start = 76.dp, top = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Box(Modifier.size(48.dp).pressable(onOpenDestination).mmiPanel(), contentAlignment = Alignment.Center) {
                    MmiLineIcon(MmiLine.GRID, MmiInk, Modifier.size(24.dp))
                }
                Row(
                    Modifier.height(48.dp).widthIn(min = 170.dp).pressable(onSearch).mmiPanel().padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    MmiLineIcon(MmiLine.SEARCH, MmiInk, Modifier.size(22.dp))
                    Spacer(Modifier.width(10.dp))
                    Text("Search", color = MmiInk, fontFamily = MmiFont, fontSize = 18.sp)
                }
            }
            if (destination != null) {
                if (route != null || nav.calculating) {
                    ManeuverPanel(nav, metric)
                } else {
                    GuidanceBanner(destination, here, course, metric)
                }
                val status = when {
                    nav.rerouting -> "Recalculating…"
                    nav.calculating -> "Calculating route…"
                    else -> nav.message
                }
                if (status != null) {
                    Text(
                        status, color = MmiInk, fontFamily = MmiFont, fontSize = 15.sp,
                        modifier = Modifier.width(380.dp).mmiPanel().padding(horizontal = 14.dp, vertical = 8.dp),
                    )
                }
            }
        }
        if (destination != null) {
            TripPanel(nav, here, metric, Modifier.align(Alignment.BottomStart).padding(start = 76.dp, bottom = 12.dp), onEnd)
        }

        nav.camera?.let { cam ->
            Row(
                Modifier.align(Alignment.BottomCenter).padding(bottom = 80.dp)
                    .clip(RoundedCornerShape(4.dp)).background(Color(0xE6B71C1C)).padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                MmiLineIcon(MmiLine.CAMERA, Color.White, Modifier.size(28.dp))
                Spacer(Modifier.width(12.dp))
                Text(
                    (if (cam.user) "Marked camera" else "Speed camera") + "  " + formatDistance(cam.distanceM, metric),
                    color = Color.White, fontFamily = MmiFont, fontSize = 20.sp,
                )
                if (cam.limit > 0) {
                    Spacer(Modifier.width(12.dp))
                    SpeedLimitSign(cam.limit, Modifier.size(44.dp))
                }
            }
        }

        // Top right: clock, compass (heading-up / north-up) and settings.
        Column(
            Modifier.align(Alignment.TopEnd).padding(end = 12.dp, top = 12.dp),
            horizontalAlignment = Alignment.End,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                NavClock()
                Spacer(Modifier.width(4.dp))
                Box(
                    Modifier.size(48.dp).pressable {
                        northUp = !northUp
                        navPrefs.edit().putBoolean("north_up", northUp).apply()
                        following = true
                    }.mmiPanel(),
                    contentAlignment = Alignment.Center,
                ) { CompassButton(if (northUp) 0f else -course, northUp) }
                Box(Modifier.size(48.dp).pressable { onOpenPage("settings") }.mmiPanel(), contentAlignment = Alignment.Center) {
                    MmiLineIcon(MmiLine.GEAR, MmiInk, Modifier.size(24.dp))
                }
            }
            Column(verticalArrangement = Arrangement.spacedBy(8.dp), horizontalAlignment = Alignment.End) {
                ChromeButton(MmiLine.PLUS, 40.dp, { zoomOffset = (zoomOffset + 1f).coerceAtMost(3f) })
                ChromeButton(MmiLine.MINUS, 40.dp, { zoomOffset = (zoomOffset - 1f).coerceAtLeast(-4f) })
                ChromeButton(MmiLine.LOCATE, 40.dp, { following = true }, highlighted = !following)
                ChromeButton(MmiLine.MENU, 40.dp, { markMenu = !markMenu }, highlighted = markMenu)
            }
        }

        // Mark a bump / pothole here, or confirm the last detected jolt.
        if (markMenu) {
            Column(
                Modifier.align(Alignment.CenterEnd).padding(end = 72.dp).width(230.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                val done = { msg: String -> toast = msg; markMenu = false }
                ChromePill(if (nav.voiceOn) "Voice: on" else "Voice: off", {
                    NavigationEngine.setVoice(!nav.voiceOn)
                }, if (nav.voiceOn) MmiLine.SPEAKER else MmiLine.MUTE, Modifier.fillMaxWidth())
                ChromePill("Speed camera here", {
                    done(if (NavigationEngine.markCamera()) "Camera saved at this spot" else "No GPS fix yet")
                }, MmiLine.CAMERA, Modifier.fillMaxWidth())
                ChromePill("Speed bump here", {
                    done(if (RoadMemory.markHere(HazardType.BUMP)) "Speed bump saved" else "No GPS fix yet")
                }, MmiLine.BUMP, Modifier.fillMaxWidth())
                ChromePill("Pothole here", {
                    done(if (RoadMemory.markHere(HazardType.POTHOLE)) "Pothole saved" else "No GPS fix yet")
                }, MmiLine.BUMP, Modifier.fillMaxWidth())
                ChromePill("That jolt was real", {
                    done(if (RoadMemory.confirmLast()) "Confirmed — thanks" else "No jolt detected yet")
                }, MmiLine.STAR, Modifier.fillMaxWidth())
            }
        }

        // Bottom right info box, like the Audi one: limit, speed, altitude.
        if (here != null) {
            Column(
                Modifier.align(Alignment.BottomEnd).padding(end = 12.dp, bottom = 12.dp).width(150.dp).mmiPanel().padding(10.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (nav.speedLimit > 0) {
                        SpeedLimitSign(nav.speedLimit, Modifier.size(46.dp))
                        Spacer(Modifier.width(10.dp))
                    }
                    val speed = if (metric) nav.speedKmh else nav.speedKmh / 1.609f
                    Text(
                        "${speed.roundToInt()}", color = if (nav.overspeed) Color(0xFFFF5252) else MmiInk,
                        fontFamily = MmiFont, fontWeight = FontWeight.Light, fontSize = 28.sp,
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(if (metric) "km/h" else "mph", color = MmiDim, fontFamily = MmiFont, fontSize = 12.sp)
                }
                if (altitude != null) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        MmiLineIcon(MmiLine.MOUNTAIN, MmiDim, Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(
                            if (metric) "${altitude.roundToInt()} m" else "${(altitude * 3.281).roundToInt()} ft",
                            color = MmiInk, fontFamily = MmiFont, fontSize = 15.sp,
                        )
                    }
                }
            }
        }

        if (here == null) {
            Text(
                "Waiting for GPS…", color = MmiInk, fontFamily = MmiFont, fontSize = 15.sp,
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 16.dp).mmiPanel().padding(horizontal = 14.dp, vertical = 8.dp),
            )
        }
        toast?.let {
            Text(
                it, color = MmiInk, fontFamily = MmiFont, fontSize = 16.sp,
                modifier = Modifier.align(Alignment.Center).mmiPanel().padding(horizontal = 18.dp, vertical = 10.dp),
            )
        }
    }
}

private val RAIL = listOf(
    "home" to MmiLine.HOME,
    "radio" to MmiLine.ANTENNA,
    "music" to MmiLine.NOTE,
    "phone" to MmiLine.CALL,
    "nav" to MmiLine.NAV_ARROW,
)

@Composable
private fun NavRail(modifier: Modifier, onOpen: (String) -> Unit) {
    Row(modifier.fillMaxHeight()) {
        Column(
            Modifier.fillMaxHeight().width(64.dp)
                .background(Brush.horizontalGradient(listOf(Color(0xFA03050A), Color(0xEE070A10))))
                .padding(vertical = 8.dp),
            verticalArrangement = Arrangement.SpaceEvenly,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            RAIL.forEach { (key, icon) ->
                val active = key == "nav"
                Box(
                    Modifier.fillMaxWidth().height(52.dp).pressable { if (!active) onOpen(key) },
                    contentAlignment = Alignment.Center,
                ) {
                    if (active) {
                        // Audi-style marker: a lit bar on the edge of the active entry.
                        Box(Modifier.align(Alignment.CenterStart).width(3.dp).height(30.dp).background(MmiCyan))
                    }
                    MmiLineIcon(icon, if (active) MmiCyan else MmiInk, Modifier.size(if (active) 28.dp else 24.dp))
                }
            }
        }
        Box(Modifier.fillMaxHeight().width(1.dp).background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xFF3A4250), Color.Transparent))))
    }
}

@Composable
private fun CompassButton(rotation: Float, northUp: Boolean) {
    val turn by animateFloatAsState(rotation, tween(400), label = "compass_btn")
    Box(Modifier.size(34.dp).graphicsLayer { rotationZ = turn }, contentAlignment = Alignment.TopCenter) {
        Canvas(Modifier.fillMaxSize()) {
            drawCircle(if (northUp) MmiCyan else MmiInk, size.minDimension / 2f, style = androidx.compose.ui.graphics.drawscope.Stroke(1.5.dp.toPx()))
        }
        Text("N", color = if (northUp) MmiCyan else MmiInk, fontFamily = MmiFont, fontSize = 13.sp, modifier = Modifier.padding(top = 3.dp))
    }
}

@Composable
private fun NavClock() {
    val context = LocalContext.current
    val clock24 by com.openlauncher.app.data.CarPrefs.get(context).clock24.collectAsStateWithLifecycle()
    val now by produceState(System.currentTimeMillis()) {
        while (true) {
            value = System.currentTimeMillis()
            delay(60_000 - value % 60_000)
        }
    }
    val fmt = remember(clock24) { java.text.SimpleDateFormat(if (clock24) "HH:mm" else "h:mm a", java.util.Locale.getDefault()) }
    Text(
        fmt.format(java.util.Date(now)), color = MmiInk, fontFamily = MmiFont, fontSize = 18.sp,
        modifier = Modifier.mmiPanel().padding(horizontal = 12.dp, vertical = 12.dp),
    )
}

@Composable
private fun ManeuverPanel(nav: NavState, metric: Boolean) {
    val next = nav.next
    Column(Modifier.width(380.dp).mmiPanel().padding(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            val type = when {
                nav.arrived -> ManeuverType.ARRIVE
                next != null -> next.type
                else -> ManeuverType.STRAIGHT
            }
            ManeuverGlyph(type, next?.exit ?: 0, MmiInk, Modifier.size(72.dp))
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                when {
                    nav.arrived -> {
                        Text("Arrived", color = MmiInk, fontFamily = MmiFont, fontWeight = FontWeight.Light, fontSize = 32.sp)
                        Text(nav.destination?.title ?: "", color = MmiDim, fontFamily = MmiFont, fontSize = 16.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                    next != null -> {
                        Text(formatDistance(nav.distanceToNextM, metric), color = MmiInk, fontFamily = MmiFont, fontWeight = FontWeight.Light, fontSize = 32.sp)
                        Text(next.text, color = MmiInk, fontFamily = MmiFont, fontSize = 17.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                    else -> Text("Calculating…", color = MmiDim, fontFamily = MmiFont, fontSize = 20.sp)
                }
            }
        }
        val following = nav.following
        if (!nav.arrived && next != null && following != null && following.alongM - next.alongM < 300.0) {
            Box(Modifier.fillMaxWidth().padding(vertical = 8.dp).height(1.dp).background(Color(0xFF2A2E34)))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Then", color = MmiDim, fontFamily = MmiFont, fontSize = 15.sp)
                Spacer(Modifier.width(10.dp))
                ManeuverGlyph(following.type, following.exit, MmiDim, Modifier.size(30.dp))
                Spacer(Modifier.width(10.dp))
                Text(following.road.display, color = MmiDim, fontFamily = MmiFont, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun TripPanel(nav: NavState, here: LatLng?, metric: Boolean, modifier: Modifier, onEnd: () -> Unit) {
    val dest = nav.destination ?: return
    val remaining = if (nav.route != null) nav.remainingM
    else here?.let { distanceMeters(it.latitude, it.longitude, dest.lat, dest.lon) }
    val seconds = if (nav.route != null) nav.remainingS else null
    Row(modifier.height(56.dp).mmiPanel().padding(start = 16.dp, end = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        MmiLineIcon(MmiLine.FLAG, MmiInk, Modifier.size(20.dp))
        Spacer(Modifier.width(10.dp))
        Text(remaining?.let { formatDistance(it, metric) } ?: "--", color = MmiInk, fontFamily = MmiFont, fontSize = 18.sp)
        if (seconds != null && !nav.arrived) {
            Text("  ·  ${formatDuration(seconds)}  ·  ${formatEta(seconds)}", color = MmiInk, fontFamily = MmiFont, fontSize = 18.sp)
        }
        Spacer(Modifier.width(12.dp))
        Box(Modifier.size(44.dp).pressable(onEnd), contentAlignment = Alignment.Center) {
            MmiLineIcon(MmiLine.CLOSE, MmiInk, Modifier.size(18.dp))
        }
    }
}

@Composable
private fun GuidanceBanner(target: Place, here: LatLng?, course: Float, metric: Boolean) {
    val distance = here?.let { distanceMeters(it.latitude, it.longitude, target.lat, target.lon) }
    val relative = here?.let { (bearingDegrees(it, LatLng(target.lat, target.lon)) - course + 360f) % 360f }
    Row(
        Modifier.width(380.dp).mmiPanel().padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(56.dp), contentAlignment = Alignment.Center) {
            MmiLineIcon(
                MmiLine.ARROW, RouteGreen,
                Modifier.size(40.dp).graphicsLayer { rotationZ = (relative ?: 0f) - 90f },
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(distance?.let { formatDistance(it, metric) } ?: "--", color = MmiInk, fontFamily = MmiFont, fontWeight = FontWeight.Light, fontSize = 28.sp)
            Text(target.title, color = MmiDim, fontFamily = MmiFont, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

private fun bearingDegrees(from: LatLng, to: LatLng): Float {
    val lat1 = Math.toRadians(from.latitude)
    val lat2 = Math.toRadians(to.latitude)
    val dLon = Math.toRadians(to.longitude - from.longitude)
    val y = sin(dLon) * cos(lat2)
    val x = cos(lat1) * sin(lat2) - sin(lat1) * cos(lat2) * cos(dLon)
    return ((Math.toDegrees(atan2(y, x)) + 360.0) % 360.0).toFloat()
}

// ── Panels ───────────────────────────────────────────────────────────────────

@Composable
private fun NavPanel(subtitle: String, onBack: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier.fillMaxSize().background(MmiPageBg.copy(alpha = 0.97f)).padding(horizontal = 28.dp, vertical = 10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(width = 64.dp, height = 44.dp).pressable(onBack).mmiPanel(), contentAlignment = Alignment.Center) {
                MmiLineIcon(MmiLine.CHEVRON_LEFT, MmiInk, Modifier.size(20.dp))
            }
            Spacer(Modifier.width(24.dp))
            Text("Navigation", color = MmiCyan, fontFamily = MmiFont, fontSize = 22.sp)
            Text("  ·  $subtitle", color = MmiInk, fontFamily = MmiFont, fontSize = 22.sp)
        }
        Box(Modifier.fillMaxWidth().padding(vertical = 10.dp).height(1.dp).background(Brush.horizontalGradient(listOf(Color(0xFF5A6068), Color.Transparent))))
        content()
    }
}

@Composable
private fun NavTile(label: String, icon: MmiLine, modifier: Modifier, onClick: () -> Unit) {
    Column(
        modifier.height(118.dp).pressable(onClick)
            .clip(RoundedCornerShape(3.dp))
            .background(Brush.verticalGradient(listOf(Color(0xFF111316), Color(0xFF16191D))))
            .border(1.dp, Brush.verticalGradient(listOf(Color(0xFF2A2E34), Color(0xFFB8BEC6))), RoundedCornerShape(3.dp))
            .padding(10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        MmiLineIcon(icon, MmiInk, Modifier.size(36.dp))
        Spacer(Modifier.height(10.dp))
        Text(label, color = MmiInk, fontFamily = MmiFont, fontSize = 16.sp, maxLines = 2)
    }
}

@Composable
private fun DestinationGrid(
    onSearch: () -> Unit,
    onRecents: () -> Unit,
    onFavorites: () -> Unit,
    onPoi: () -> Unit,
    onHome: () -> Unit,
    onMap: () -> Unit,
    onMapData: () -> Unit,
) {
    val tiles = listOf(
        Triple("Intelligent search", MmiLine.SEARCH, onSearch),
        Triple("Enter address", MmiLine.CARD, onSearch),
        Triple("Last destinations", MmiLine.HISTORY, onRecents),
        Triple("Favorites", MmiLine.STAR, onFavorites),
        Triple("Points of interest", MmiLine.FUEL, onPoi),
        Triple("Home", MmiLine.HOME, onHome),
        Triple("Map", MmiLine.GLOBE, onMap),
        Triple("Map data", MmiLine.OPEN, onMapData),
    )
    Column(Modifier.fillMaxWidth().padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        tiles.chunked(4).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                row.forEach { (label, icon, action) -> NavTile(label, icon, Modifier.weight(1f), action) }
                repeat(4 - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
private fun PoiGrid(onPick: (PoiCategory) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        POI_CATEGORIES.chunked(4).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                row.forEach { cat -> NavTile(cat.label, cat.icon, Modifier.weight(1f)) { onPick(cat) } }
            }
        }
    }
}

@Composable
private fun PlaceList(items: List<Place>, here: LatLng?, metric: Boolean, onPick: (Place) -> Unit) {
    if (items.isEmpty()) {
        Text("Nothing here yet.", color = MmiDim, fontFamily = MmiFont, fontSize = 18.sp, modifier = Modifier.padding(top = 24.dp))
        return
    }
    LazyColumn(Modifier.fillMaxSize()) {
        items(items, key = { it.id }) { place -> PlaceRow(place, here, metric) { onPick(place) } }
    }
}

@Composable
private fun PlaceRow(place: Place, here: LatLng?, metric: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().height(64.dp).pressable(onClick).padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MmiLineIcon(if (place.kind.startsWith("street")) MmiLine.FLAG else MmiLine.PIN, MmiDim, Modifier.size(22.dp))
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(place.title, color = MmiInk, fontFamily = MmiFont, fontSize = 18.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(place.subtitle, color = MmiDim, fontFamily = MmiFont, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (here != null) {
            Text(
                formatDistance(distanceMeters(here.latitude, here.longitude, place.lat, place.lon), metric),
                color = MmiDim, fontFamily = MmiFont, fontSize = 15.sp,
            )
        }
    }
    Box(Modifier.fillMaxWidth().height(1.dp).background(Color(0xFF1E2125)))
}

@Composable
private fun DetailsPanel(
    place: Place,
    here: LatLng?,
    metric: Boolean,
    destinations: Destinations,
    onStart: () -> Unit,
) {
    var favorite by remember(place.id) { mutableStateOf(destinations.isFavorite(place)) }
    val distance = here?.let { distanceMeters(it.latitude, it.longitude, place.lat, place.lon) }
    Row(Modifier.fillMaxSize().padding(top = 8.dp)) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    place.title, color = MmiInk, fontFamily = MmiFont, fontWeight = FontWeight.Medium, fontSize = 28.sp,
                    maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false),
                )
                Spacer(Modifier.width(16.dp))
                Box(
                    Modifier.size(44.dp).pressable { destinations.toggleFavorite(place); favorite = !favorite },
                    contentAlignment = Alignment.Center,
                ) {
                    MmiLineIcon(if (favorite) MmiLine.STAR_FILLED else MmiLine.STAR, if (favorite) MmiCyan else MmiInk, Modifier.size(30.dp))
                }
            }
            Spacer(Modifier.height(6.dp))
            Text(place.subtitle, color = MmiDim, fontFamily = MmiFont, fontSize = 18.sp)
            Text("%.5f, %.5f".format(place.lat, place.lon), color = MmiDim.copy(alpha = 0.7f), fontFamily = MmiFont, fontSize = 14.sp)
            // Distance and Start sit high on the page, within easy reach from the driver's seat.
            Spacer(Modifier.height(20.dp))
            if (distance != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    MmiLineIcon(MmiLine.FLAG, MmiInk, Modifier.size(20.dp))
                    Spacer(Modifier.width(10.dp))
                    val minutes = (distance / 1000.0 / 40.0 * 60.0).roundToInt().coerceAtLeast(1)
                    Text("${formatDistance(distance, metric)} / ≈ $minutes min", color = MmiInk, fontFamily = MmiFont, fontSize = 18.sp)
                }
                Spacer(Modifier.height(14.dp))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                ChromePill("Start", onStart, MmiLine.FLAG, Modifier.width(320.dp))
            }
        }
    }
}

// ── Search with on-screen keyboard ───────────────────────────────────────────

@Composable
private fun SearchPanel(
    search: PlaceSearch,
    here: LatLng?,
    metric: Boolean,
    onBack: () -> Unit,
    onPick: (Place) -> Unit,
    onCategory: (PoiCategory) -> Unit,
) {
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<Place>>(emptyList()) }
    var keyboard by remember { mutableStateOf(true) }
    LaunchedEffect(query) {
        if (query.isBlank()) { results = emptyList(); return@LaunchedEffect }
        delay(160)
        results = search.search(query, here?.latitude, here?.longitude)
    }
    Column(Modifier.fillMaxSize().background(MmiPageBg.copy(alpha = 0.97f)).padding(horizontal = 24.dp, vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(width = 64.dp, height = 48.dp).pressable(onBack).mmiPanel(), contentAlignment = Alignment.Center) {
                MmiLineIcon(MmiLine.CHEVRON_LEFT, MmiInk, Modifier.size(20.dp))
            }
            Spacer(Modifier.width(14.dp))
            Row(
                Modifier.weight(1f).height(48.dp).pressable { keyboard = true }.mmiPanel().padding(horizontal = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    query.ifEmpty { "Where to?" }, color = if (query.isEmpty()) MmiDim else MmiInk,
                    fontFamily = MmiFont, fontSize = 20.sp, maxLines = 1, modifier = Modifier.weight(1f),
                )
                if (query.isNotEmpty()) {
                    Box(Modifier.size(32.dp).pressable { query = "" }, contentAlignment = Alignment.Center) {
                        MmiLineIcon(MmiLine.CLOSE, MmiDim, Modifier.size(16.dp))
                    }
                }
            }
        }
        Row(Modifier.padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            listOf(POI_CATEGORIES[1], POI_CATEGORIES[2], POI_CATEGORIES[0]).forEach { cat ->
                ChromePill(cat.label, { onCategory(cat) }, cat.icon)
            }
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (query.isBlank()) {
                Text(
                    "Search streets, areas and places in Egypt — Arabic or English.\ne.g. \"Tahrir Square\", \"مستشفى\", \"Zamalek\"",
                    color = MmiDim, fontFamily = MmiFont, fontSize = 16.sp, modifier = Modifier.padding(top = 8.dp),
                )
            } else {
                PlaceList(results, here, metric, onPick)
                // Online fallback: hand the search to Google Maps when on hotspot.
                val context = LocalContext.current
                Text(
                    if (results.isEmpty()) "Not found offline — search \"$query\" in Google Maps" else "Search in Google Maps",
                    color = MmiCyan, fontFamily = MmiFont, fontSize = 15.sp,
                    modifier = Modifier.align(Alignment.BottomEnd).padding(8.dp).pressable {
                        val uri = Uri.parse("geo:${here?.latitude ?: 0.0},${here?.longitude ?: 0.0}?q=" + Uri.encode(query))
                        context.launchFirst(
                            Intent(Intent.ACTION_VIEW, uri).setPackage("com.google.android.apps.maps"),
                            Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/maps/search/" + Uri.encode(query))),
                        )
                    }.mmiPanel().padding(horizontal = 12.dp, vertical = 8.dp),
                )
            }
        }
        if (keyboard) {
            MmiKeyboard(
                onKey = { query += it },
                onBackspace = { query = query.dropLast(1) },
                onClose = { keyboard = false },
                // Go: open the best match, or hide the keyboard to show the whole list.
                onGo = { results.firstOrNull()?.let(onPick) ?: run { keyboard = false } },
                modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
            )
        }
    }
}

private val KEYS_EN = listOf(
    "1234567890".map { it.toString() },
    "qwertyuiop".map { it.toString() },
    "asdfghjkl".map { it.toString() },
    "zxcvbnm".map { it.toString() },
)
private val KEYS_AR = listOf(
    "١٢٣٤٥٦٧٨٩٠".map { it.toString() },
    "ضصثقفغعهخحج".map { it.toString() },
    "شسيبلاتنمكط".map { it.toString() },
    listOf("ئ", "ء", "ؤ", "ر", "لا", "ى", "ة", "و", "ز", "ظ", "د"),
)

@Composable
private fun MmiKeyboard(onKey: (String) -> Unit, onBackspace: () -> Unit, onClose: () -> Unit, onGo: () -> Unit, modifier: Modifier) {
    var arabic by remember { mutableStateOf(false) }
    val rows = if (arabic) KEYS_AR else KEYS_EN
    Column(
        modifier.clip(RoundedCornerShape(4.dp)).background(Color(0xFF07080A)).padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        rows.forEachIndexed { index, row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (index == rows.lastIndex) Key(Modifier.weight(1.4f), onClick = onClose) { MmiLineIcon(MmiLine.CLOSE, MmiDim, Modifier.size(18.dp)) }
                row.forEach { k -> Key(Modifier.weight(1f), onClick = { onKey(k) }) { KeyText(k) } }
                if (index == rows.lastIndex) Key(Modifier.weight(1.4f), onClick = onBackspace) { MmiLineIcon(MmiLine.BACKSPACE, MmiInk, Modifier.size(22.dp)) }
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Key(Modifier.weight(1.4f), onClick = { arabic = !arabic }) { KeyText(if (arabic) "EN" else "ع") }
            Key(Modifier.weight(1f), onClick = { onKey("-") }) { KeyText("-") }
            Key(Modifier.weight(5f), onClick = { onKey(" ") }) { KeyText(if (arabic) "مسافة" else "space") }
            Box(
                Modifier.weight(2f).height(42.dp).pressable(onGo)
                    .clip(RoundedCornerShape(3.dp))
                    .background(Brush.verticalGradient(listOf(Color(0xFF1C8FB8), Color(0xFF136585)))),
                contentAlignment = Alignment.Center,
            ) { Text(if (arabic) "بحث" else "GO", color = Color.White, fontFamily = MmiFont, fontSize = 18.sp) }
        }
    }
}

@Composable
private fun Key(modifier: Modifier, onClick: () -> Unit, content: @Composable BoxScope.() -> Unit) {
    Box(
        modifier.height(42.dp).pressable(onClick)
            .clip(RoundedCornerShape(3.dp))
            .background(Color(0xFF15171A))
            .border(1.dp, Color(0xFF2A2E34), RoundedCornerShape(3.dp)),
        contentAlignment = Alignment.Center,
        content = content,
    )
}

@Composable
private fun KeyText(text: String) {
    Text(text.uppercase(), color = MmiInk, fontFamily = MmiFont, fontSize = 18.sp)
}

// ── First-run import ─────────────────────────────────────────────────────────

@Composable
private fun ImportPanel(onBack: (() -> Unit)?, onDone: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val install by MapInstaller.state.collectAsStateWithLifecycle()
    var pickProgress by remember { mutableStateOf<Float?>(null) }
    var pickError by remember { mutableStateOf<String?>(null) }
    var refresh by remember { mutableIntStateOf(0) }
    var access by remember { mutableStateOf(MapInstaller.hasFileAccess(context)) }
    var found by remember { mutableStateOf<Map<String, java.io.File>>(emptyMap()) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { access = MapInstaller.hasFileAccess(context) }
    // Watch USB sticks, SD cards and the Download folder while this screen is open.
    LaunchedEffect(access) {
        while (access) {
            found = withContext(Dispatchers.IO) { MapInstaller.findLocal(context) }
            delay(3000)
        }
    }
    LaunchedEffect(install.finished) {
        refresh++
        if (install.finished && MapData.isInstalled(context)) onDone()
    }
    val askRead = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        access = MapInstaller.hasFileAccess(context)
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris: List<Uri> ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        scope.launch {
            pickProgress = 0f
            pickError = null
            val ok = MapData.import(context, uris) { pickProgress = it }
            pickProgress = null
            refresh++
            NavigationEngine.reloadData()
            if (ok && MapData.isInstalled(context)) onDone()
            else pickError = "Select at least egypt.mbtiles and egypt-search.db."
        }
    }

    Column(Modifier.fillMaxSize().background(MmiPageBg).padding(horizontal = 48.dp, vertical = 14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (onBack != null) {
                Box(Modifier.size(width = 64.dp, height = 44.dp).pressable(onBack).mmiPanel(), contentAlignment = Alignment.Center) {
                    MmiLineIcon(MmiLine.CHEVRON_LEFT, MmiInk, Modifier.size(20.dp))
                }
                Spacer(Modifier.width(20.dp))
            }
            Text("Offline maps for Egypt", color = MmiInk, fontFamily = MmiFont, fontWeight = FontWeight.Light, fontSize = 30.sp)
        }
        Spacer(Modifier.height(8.dp))
        key(refresh) {
            Row(horizontalArrangement = Arrangement.spacedBy(28.dp)) {
                listOf(
                    "Roads & turn-by-turn" to MapData.routing(context),
                    "Search" to MapData.search(context),
                    "Speed limits & cameras" to MapData.safety(context),
                ).forEach { (label, file) ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(10.dp).clip(RoundedCornerShape(5.dp)).background(if (file.exists()) RouteGreen else Color(0xFF5A6068)))
                        Spacer(Modifier.width(8.dp))
                        Text(label, color = if (file.exists()) MmiInk else MmiDim, fontFamily = MmiFont, fontSize = 15.sp)
                    }
                }
            }
        }
        Spacer(Modifier.height(16.dp))

        val busy = install.running || pickProgress != null
        if (busy) {
            val fraction = pickProgress ?: install.fraction
            Column(Modifier.fillMaxWidth().weight(1f), verticalArrangement = Arrangement.Center) {
                Text(
                    if (pickProgress != null) "Copying…" else "${install.source} ${install.file}…",
                    color = MmiInk, fontFamily = MmiFont, fontSize = 20.sp,
                )
                Spacer(Modifier.height(12.dp))
                Box(Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)).background(Color(0xFF2A2E34))) {
                    Box(Modifier.fillMaxHeight().fillMaxWidth(fraction).background(RouteGreen))
                }
                Spacer(Modifier.height(8.dp))
                Row {
                    Text(
                        if (install.totalBytes > 0 && pickProgress == null) "${install.doneBytes shr 20} of ${install.totalBytes shr 20} MB" else "${(fraction * 100).roundToInt()}%",
                        color = MmiDim, fontFamily = MmiFont, fontSize = 16.sp, modifier = Modifier.weight(1f),
                    )
                    if (install.running) ChromePill("Cancel", { MapInstaller.cancel() }, MmiLine.CLOSE)
                }
            }
        } else {
            Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                Column(Modifier.weight(1f).fillMaxHeight().mmiPanel().padding(18.dp)) {
                    Text("Download", color = MmiCyan, fontFamily = MmiFont, fontSize = 14.sp, letterSpacing = 2.sp)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Turn on your phone's hotspot and connect this screen to it, then tap Download. About 470 MB, once — after that everything works without internet.",
                        color = MmiInk, fontFamily = MmiFont, fontSize = 17.sp,
                    )
                    Spacer(Modifier.weight(1f))
                    ChromePill("Download maps", { MapInstaller.download(context) }, MmiLine.GLOBE, Modifier.fillMaxWidth())
                }
                Column(Modifier.weight(1f).fillMaxHeight().mmiPanel().padding(18.dp)) {
                    Text("From USB or phone", color = MmiCyan, fontFamily = MmiFont, fontSize = 14.sp, letterSpacing = 2.sp)
                    Spacer(Modifier.height(8.dp))
                    when {
                        !access -> {
                            Text(
                                "Allow file access once, then plug in a USB stick with the map files or send them to this screen (Send Files To TV). They are picked up automatically.",
                                color = MmiInk, fontFamily = MmiFont, fontSize = 17.sp,
                            )
                            Spacer(Modifier.weight(1f))
                            ChromePill("Allow file access", {
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                                    context.launchFirst(
                                        Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:${context.packageName}")),
                                        Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION),
                                    )
                                } else {
                                    askRead.launch(android.Manifest.permission.READ_EXTERNAL_STORAGE)
                                }
                            }, MmiLine.OPEN, Modifier.fillMaxWidth())
                        }
                        found.isEmpty() -> {
                            Text(
                                "Plug in a USB stick with the map files, or send them to this screen with Send Files To TV. They appear here automatically.",
                                color = MmiInk, fontFamily = MmiFont, fontSize = 17.sp,
                            )
                            Spacer(Modifier.height(10.dp))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                SearchingIndicator()
                                Spacer(Modifier.width(10.dp))
                                Text("Looking for map files…", color = MmiDim, fontFamily = MmiFont, fontSize = 15.sp)
                            }
                        }
                        else -> {
                            Text("Found ${found.size} of 4 map files:", color = MmiInk, fontFamily = MmiFont, fontSize = 17.sp)
                            found.forEach { (name, file) ->
                                Text("$name · ${file.length() shr 20} MB", color = MmiDim, fontFamily = MmiFont, fontSize = 15.sp, maxLines = 1)
                            }
                            Spacer(Modifier.weight(1f))
                            ChromePill("Install", { MapInstaller.installLocal(context, found) }, MmiLine.OPEN, Modifier.fillMaxWidth())
                        }
                    }
                }
            }
            Text(
                "Or choose the files yourself…",
                color = MmiDim, fontFamily = MmiFont, fontSize = 15.sp,
                modifier = Modifier.padding(top = 10.dp).pressable { picker.launch(arrayOf("*/*")) },
            )
        }
        (install.error ?: pickError)?.let {
            Spacer(Modifier.height(8.dp))
            Text(it, color = Color(0xFFE57373), fontFamily = MmiFont, fontSize = 15.sp)
        }
    }
}

@Composable
private fun SearchingIndicator() {
    val t = rememberInfiniteTransition(label = "looking")
    val a by t.animateFloat(0.3f, 1f, infiniteRepeatable(tween(700), RepeatMode.Reverse), label = "looking_alpha")
    Box(Modifier.size(10.dp).graphicsLayer { alpha = a }.clip(RoundedCornerShape(5.dp)).background(MmiCyan))
}

