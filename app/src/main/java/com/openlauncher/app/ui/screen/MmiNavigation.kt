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
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapLibreMapOptions
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

private enum class NavScreen { MAP, DESTINATION, SEARCH, POI, LIST, DETAILS, IMPORT }

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
 * One map for the whole session. Creating MapLibre and loading the style and
 * tiles is the slow part, so the view is kept (paused) when you leave
 * Navigation and is back instantly next time.
 */
internal object PersistentMap {
    private var view: MapView? = null
    var map: MapLibreMap? = null
        private set
    var style by mutableStateOf<Style?>(null)
        private set
    /** True once the first frame with the style is on screen. */
    var ready by mutableStateOf(false)
        private set
    var onUserGesture: (() -> Unit)? = null
    /** Bumped by [destroy] so the screen builds a fresh map. */
    var generation by mutableIntStateOf(0)
        private set

    fun obtain(context: android.content.Context): MapView {
        view?.let { return it }
        MapLibre.getInstance(context.applicationContext)
        // SurfaceView rendering (faster than TextureView on Mali/PowerVR head units), capped
        // at 30 fps so the map leaves CPU for audio, GPS and the rest of the UI.
        val v = MapView(context, MapLibreMapOptions.createFromAttributes(context, null).textureMode(false)).apply {
            onCreate(null)
            setMaximumFps(30)
        }
        view = v
        v.addOnDidFinishRenderingMapListener { if (style != null) ready = true }
        v.getMapAsync { m ->
            map = m
            m.uiSettings.isLogoEnabled = false
            m.uiSettings.isAttributionEnabled = false
            m.uiSettings.isCompassEnabled = false
            m.cameraPosition = CameraPosition.Builder().target(CAIRO).zoom(11.0).build()
            m.addOnCameraMoveStartedListener { reason ->
                if (reason == MapLibreMap.OnCameraMoveStartedListener.REASON_API_GESTURE) onUserGesture?.invoke()
            }
            val json = context.assets.open("map/mmi-style.json").bufferedReader().use { it.readText() }
                .replace("{{TILES}}", "mbtiles://" + MapData.tiles(context).absolutePath)
            m.setStyle(Style.Builder().fromJson(json)) { s ->
                s.addImage("vehicle", vehicleBitmap(context.resources.displayMetrics.density))
                s.addSource(GeoJsonSource("vehicle"))
                s.addLayer(
                    SymbolLayer("vehicle", "vehicle").withProperties(
                        PropertyFactory.iconImage("vehicle"),
                        PropertyFactory.iconAllowOverlap(true),
                        PropertyFactory.iconIgnorePlacement(true),
                        PropertyFactory.iconRotationAlignment(Property.ICON_ROTATION_ALIGNMENT_MAP),
                        PropertyFactory.iconPitchAlignment(Property.ICON_PITCH_ALIGNMENT_MAP),
                    )
                )
                style = s
                // Reveal even if the renderer never reports idle (slow tiles).
                v.postDelayed({ ready = true }, 1500)
            }
        }
        return v
    }

    /** Drops the map (new map files imported, or the activity is going away). */
    fun destroy() {
        runCatching { view?.onDestroy() }
        view = null
        map = null
        style = null
        ready = false
        onUserGesture = null
        generation++
    }
}

/**
 * MMI navigation: offline Egypt vector map (black and white, route in green),
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
        if (installed) key(PersistentMap.generation) {
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
                    PersistentMap.destroy()
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
    val holder = PersistentMap
    var northUp by remember { mutableStateOf(false) }
    var following by remember { mutableStateOf(true) }
    var toast by remember { mutableStateOf<String?>(null) }
    val course = nav.course
    val destination = nav.destination
    val route = nav.route
    val density = context.resources.displayMetrics.density

    LaunchedEffect(toast) {
        if (toast != null) {
            delay(2200)
            toast = null
        }
    }

    val mapView = remember { holder.obtain(context) }
    holder.onUserGesture = { following = false }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle, mapView) {
        // Adding the observer replays ON_START/ON_RESUME, so the map wakes up at once.
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> mapView.onStart()
                Lifecycle.Event.ON_RESUME -> mapView.onResume()
                Lifecycle.Event.ON_PAUSE -> mapView.onPause()
                Lifecycle.Event.ON_STOP -> mapView.onStop()
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            // Paused, not destroyed: style and tiles stay loaded for the next visit.
            mapView.onPause()
            mapView.onStop()
        }
    }

    // ── Smooth follow ────────────────────────────────────────────────────────
    // GPS gives a fix about twice a second. Instead of jumping, the camera glides
    // from the shown position to each new fix over the time between fixes, and
    // the car arrow is drawn at the camera's focus point so it never wobbles.
    val shown = remember { doubleArrayOf(Double.NaN, Double.NaN, 0.0) } // lat, lon, bearing
    val from = remember { doubleArrayOf(0.0, 0.0, 0.0) }
    val to = remember { doubleArrayOf(0.0, 0.0, 0.0) }
    val progress = remember { Animatable(1f) }
    var lastFixAt by remember { mutableLongStateOf(0L) }
    val zoomBand = when {
        nav.speedKmh > 90f -> 0
        nav.speedKmh > 50f -> 1
        else -> 2
    }
    val zoom = remember { Animatable(16f) }
    var zoomOffset by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(zoomBand) { zoom.animateTo(15f + zoomBand * 0.5f, tween(1500)) }

    LaunchedEffect(here, course) {
        val pos = here ?: return@LaunchedEffect
        val now = System.currentTimeMillis()
        val gap = if (lastFixAt == 0L) 0L else (now - lastFixAt).coerceIn(300L, 1500L)
        lastFixAt = now
        if (shown[0].isNaN()) {
            shown[0] = pos.latitude; shown[1] = pos.longitude; shown[2] = course.toDouble()
        }
        from[0] = shown[0]; from[1] = shown[1]; from[2] = shown[2]
        to[0] = pos.latitude; to[1] = pos.longitude
        // Turn the short way round.
        to[2] = from[2] + ((course - from[2] + 540.0) % 360.0 - 180.0)
        progress.snapTo(0f)
        progress.animateTo(1f, tween(gap.toInt().coerceAtLeast(1), easing = LinearEasing))
    }

    LaunchedEffect(holder.style, following) {
        val map = holder.map ?: return@LaunchedEffect
        val style = holder.style ?: return@LaunchedEffect
        (style.getLayer("vehicle") as? SymbolLayer)?.setProperties(
            PropertyFactory.visibility(if (following) Property.NONE else Property.VISIBLE)
        )
        if (!following) return@LaunchedEffect
        snapshotFlow { listOf(progress.value, zoom.value, zoomOffset, if (northUp) 1f else 0f) }.collect { (t, zBase, zOff, north) ->
            val z = (zBase + zOff).coerceIn(10f, 19f)
            if (from[0] == 0.0 && to[0] == 0.0) return@collect
            shown[0] = from[0] + (to[0] - from[0]) * t
            shown[1] = from[1] + (to[1] - from[1]) * t
            shown[2] = from[2] + (to[2] - from[2]) * t
            map.moveCamera(
                CameraUpdateFactory.newCameraPosition(
                    CameraPosition.Builder().target(LatLng(shown[0], shown[1]))
                        .zoom(z.toDouble()).tilt(0.0).bearing(if (north > 0f) 0.0 else (shown[2] % 360.0 + 360.0) % 360.0).build()
                )
            )
        }
    }

    // When the driver pans away, the arrow is a map symbol updated per fix.
    LaunchedEffect(holder.style, here, course, following) {
        if (following) return@LaunchedEffect
        val style = holder.style ?: return@LaunchedEffect
        val pos = here ?: return@LaunchedEffect
        style.getSourceAs<GeoJsonSource>("vehicle")?.setGeoJson(Point.fromLngLat(pos.longitude, pos.latitude))
        (style.getLayer("vehicle") as? SymbolLayer)?.setProperties(PropertyFactory.iconRotate(course))
    }

    // Road route from the offline router; a direct line when there is none yet.
    val directFrom = if (route == null) here else null
    LaunchedEffect(holder.style, route, destination, directFrom) {
        val style = holder.style ?: return@LaunchedEffect
        val destSource = style.getSourceAs<GeoJsonSource>("destination")
        val routeSource = style.getSourceAs<GeoJsonSource>("route")
        val dest = destination
        if (dest == null) {
            destSource?.setGeoJson(FeatureCollection.fromFeatures(emptyList()))
            routeSource?.setGeoJson(FeatureCollection.fromFeatures(emptyList()))
            return@LaunchedEffect
        }
        destSource?.setGeoJson(Point.fromLngLat(dest.lon, dest.lat))
        val points = when {
            route != null -> List(route.lat.size) { Point.fromLngLat(route.lon[it], route.lat[it]) }
            directFrom != null -> listOf(Point.fromLngLat(directFrom.longitude, directFrom.latitude), Point.fromLngLat(dest.lon, dest.lat))
            else -> null
        }
        if (points != null && points.size >= 2) routeSource?.setGeoJson(Feature.fromGeometry(LineString.fromLngLats(points)))
        else routeSource?.setGeoJson(FeatureCollection.fromFeatures(emptyList()))
    }

    // Fade the map in once its first frame is drawn, instead of showing tiles popping in.
    val reveal by animateFloatAsState(if (holder.ready) 0f else 1f, tween(450), label = "map_reveal")
    val vehicle = remember(density) { vehicleBitmap(density).asImageBitmap() }

    Box(Modifier.fillMaxSize()) {
        AndroidView(
            factory = { (mapView.parent as? android.view.ViewGroup)?.removeView(mapView); mapView },
            modifier = Modifier.fillMaxSize(),
        )
        if (following && here != null) {
            Image(
                vehicle, contentDescription = null,
                modifier = Modifier.align(Alignment.Center).size(44.dp).graphicsLayer { rotationZ = if (northUp) course else 0f },
            )
        }
        if (reveal > 0f) Box(Modifier.fillMaxSize().graphicsLayer { alpha = reveal }.background(MmiPageBg))

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
                    Modifier.size(48.dp).pressable { northUp = !northUp; following = true }.mmiPanel(),
                    contentAlignment = Alignment.Center,
                ) { CompassButton(if (northUp) 0f else -course, northUp) }
                Box(Modifier.size(48.dp).pressable { onOpenPage("settings") }.mmiPanel(), contentAlignment = Alignment.Center) {
                    MmiLineIcon(MmiLine.GEAR, MmiInk, Modifier.size(24.dp))
                }
            }
            Column(verticalArrangement = Arrangement.spacedBy(8.dp), horizontalAlignment = Alignment.End) {
                ChromeButton(MmiLine.PLUS, 42.dp, {
                    if (following) zoomOffset = (zoomOffset + 1f).coerceAtMost(3f)
                    else holder.map?.animateCamera(CameraUpdateFactory.zoomIn(), 250)
                })
                ChromeButton(MmiLine.MINUS, 42.dp, {
                    if (following) zoomOffset = (zoomOffset - 1f).coerceAtLeast(-4f)
                    else holder.map?.animateCamera(CameraUpdateFactory.zoomOut(), 250)
                })
                ChromeButton(MmiLine.LOCATE, 42.dp, { following = true }, highlighted = !following)
                ChromeButton(if (nav.voiceOn) MmiLine.SPEAKER else MmiLine.MUTE, 42.dp, { NavigationEngine.setVoice(!nav.voiceOn) })
                ChromeButton(MmiLine.CAMERA, 42.dp, {
                    toast = if (NavigationEngine.markCamera()) "Camera saved at this spot" else "No GPS fix yet"
                })
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
    Column(
        modifier.fillMaxHeight().width(64.dp)
            .background(Brush.horizontalGradient(listOf(Color(0xF00A0B0D), Color(0xD00A0B0D))))
            .padding(vertical = 10.dp),
        verticalArrangement = Arrangement.SpaceEvenly,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        RAIL.forEach { (key, icon) ->
            val active = key == "nav"
            Box(
                Modifier.size(52.dp).pressable { if (!active) onOpen(key) },
                contentAlignment = Alignment.Center,
            ) {
                MmiLineIcon(icon, if (active) MmiCyan else MmiInk, Modifier.size(if (active) 28.dp else 26.dp))
            }
        }
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

/** White ring with an upward arrow, like the MMI vehicle position marker. */
private fun vehicleBitmap(density: Float): Bitmap {
    val size = (44 * density).roundToInt()
    val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    val c = AndroidCanvas(bmp)
    val p = Paint(Paint.ANTI_ALIAS_FLAG)
    val r = size / 2f
    p.color = 0x55000000
    c.drawCircle(r, r, r * 0.98f, p)
    p.style = Paint.Style.STROKE
    p.strokeWidth = size * 0.07f
    p.color = 0xFFFFFFFF.toInt()
    c.drawCircle(r, r, r * 0.78f, p)
    p.style = Paint.Style.FILL
    p.color = 0xFF2AA8FF.toInt()
    val arrow = AndroidPath().apply {
        moveTo(r, size * 0.22f)
        lineTo(size * 0.70f, size * 0.68f)
        lineTo(r, size * 0.58f)
        lineTo(size * 0.30f, size * 0.68f)
        close()
    }
    c.drawPath(arrow, p)
    return bmp
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
            Spacer(Modifier.weight(1f))
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
                ChromePill("Start", onStart, MmiLine.FLAG, Modifier.width(200.dp))
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
                    "Map" to MapData.tiles(context),
                    "Search" to MapData.search(context),
                    "Turn-by-turn" to MapData.routing(context),
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
