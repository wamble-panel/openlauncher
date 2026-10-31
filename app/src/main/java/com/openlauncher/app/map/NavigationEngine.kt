package com.openlauncher.app.map

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.ToneGenerator
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import com.openlauncher.app.service.NavigationService
import com.openlauncher.app.util.CarLocation
import java.util.Locale
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sqrt

data class CameraAlert(val distanceM: Double, val limit: Int, val user: Boolean)

data class NavState(
    val destination: Place? = null,
    val route: Route? = null,
    val calculating: Boolean = false,
    val rerouting: Boolean = false,
    val message: String? = null,
    val next: Maneuver? = null,
    val following: Maneuver? = null,
    val distanceToNextM: Double = 0.0,
    val remainingM: Double = 0.0,
    val remainingS: Double = 0.0,
    val arrived: Boolean = false,
    val speedLimit: Int = 0,
    val speedKmh: Float = 0f,
    val overspeed: Boolean = false,
    val camera: CameraAlert? = null,
    val course: Float = 0f,
    val voiceOn: Boolean = true,
    val routingAvailable: Boolean = false,
)

private data class SpeedCamera(val lat: Double, val lon: Double, val limit: Int, val user: Boolean)

/**
 * Process-wide guidance: keeps following the route, speaking and warning while
 * the driver is on another MMI page. Fed with GPS fixes from the home screen.
 */
@OptIn(ExperimentalCoroutinesApi::class)
object NavigationEngine {
    private val _state = MutableStateFlow(NavState())
    val state: StateFlow<NavState> = _state

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val worker = Dispatchers.Default.limitedParallelism(1)

    private var context: Context? = null
    private var graph: RoutingGraph? = null
    private var planner: RoutePlanner? = null
    private var cameras: List<SpeedCamera> = emptyList()

    private var lastLat = Double.NaN
    private var lastLon = Double.NaN
    private var courseLat = Double.NaN
    private var courseLon = Double.NaN
    private var progressIndex = 0
    private var offRouteFixes = 0
    private var lastFreeSnap = 0L
    private var freeDriveLimit = 0
    private val spokenStage = HashMap<Int, Int>()
    private val announcedCameras = HashSet<Int>()
    private var arrivalSpoken = false
    private var awaitingFix = false

    private var tts: TextToSpeech? = null
    private var ttsReady = false

    /** Warnings the driver can switch off in Navigation settings. */
    @Volatile
    var overspeedBeep = true
        private set
    @Volatile
    var cameraAlerts = true
        private set

    /** Voice prompt health, shown on the System check page. */
    @Volatile
    var voiceStatus: String = "Not started"
        private set
    private var tone: ToneGenerator? = null

    fun init(ctx: Context) {
        if (context != null) return
        val app = ctx.applicationContext
        context = app
        val navPrefs = app.getSharedPreferences("mmi_nav", Context.MODE_PRIVATE)
        val voice = navPrefs.getBoolean("voice", true)
        overspeedBeep = navPrefs.getBoolean("overspeed_beep", true)
        cameraAlerts = navPrefs.getBoolean("camera_alerts", true)
        _state.update { it.copy(voiceOn = voice) }
        scope.launch(worker) { loadData() }
        // Same GPS listener as the home screen; the navigation service keeps it
        // running while another app is in front.
        scope.launch(worker) {
            CarLocation.get(app).location.filterNotNull().collect { update(it.latitude, it.longitude, it.speedMps) }
        }
        voiceStatus = "Starting…"
        tts = TextToSpeech(app) { status ->
            if (status != TextToSpeech.SUCCESS) {
                voiceStatus = "No text-to-speech engine — install Speech Services by Google"
            } else {
                val t = tts ?: return@TextToSpeech
                var result = t.setLanguage(Locale.US)
                if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) result = t.setLanguage(Locale.ENGLISH)
                voiceStatus = if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                    "Engine ${t.defaultEngine} has no English voice"
                } else "Ready (${t.defaultEngine})"
                t.setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                t.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(id: String?) = Unit
                    override fun onDone(id: String?) = releaseFocus()
                    @Deprecated("Deprecated in Java")
                    override fun onError(id: String?) = releaseFocus()
                })
                ttsReady = true
            }
        }
    }

    /** Nearest road to a point (for road memory), or null without routing data. */
    fun snapRoad(lat: Double, lon: Double, heading: Float?, maxMeters: Double = 25.0): Snap? =
        runCatching { graph?.snap(lat, lon, heading, maxMeters) }.getOrNull()

    /** The road network (for the map), once loaded. */
    val roads: RoutingGraph? get() = graph

    /** Re-open map files after an import. */
    fun reloadData() {
        scope.launch(worker) { loadData() }
    }

    private fun loadData() {
        val ctx = context ?: return
        graph = RoutingGraph.open(MapData.routing(ctx))
        planner = null
        val list = ArrayList<SpeedCamera>()
        runCatching {
            SQLiteDatabase.openDatabase(MapData.safety(ctx).path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
                db.rawQuery("SELECT lat, lon, maxspeed FROM cameras", null).use { c ->
                    while (c.moveToNext()) list += SpeedCamera(c.getDouble(0), c.getDouble(1), c.getInt(2), false)
                }
            }
        }
        list += userCameras(ctx)
        cameras = list
        _state.update { it.copy(routingAvailable = graph != null) }
    }

    private fun userCameras(ctx: Context): List<SpeedCamera> =
        ctx.getSharedPreferences("mmi_nav", Context.MODE_PRIVATE).getString("user_cameras", "")!!
            .split(';').mapNotNull { entry ->
                val p = entry.split(',')
                if (p.size < 2) null else runCatching { SpeedCamera(p[0].toDouble(), p[1].toDouble(), p.getOrNull(2)?.toInt() ?: 0, true) }.getOrNull()
            }

    /** Saves a camera at the current position. */
    fun markCamera(): Boolean {
        val ctx = context ?: return false
        if (lastLat.isNaN()) return false
        val prefs = ctx.getSharedPreferences("mmi_nav", Context.MODE_PRIVATE)
        val limit = _state.value.speedLimit
        val entry = "%.6f,%.6f,%d".format(Locale.US, lastLat, lastLon, limit)
        val existing = prefs.getString("user_cameras", "")!!
        prefs.edit().putString("user_cameras", if (existing.isEmpty()) entry else "$existing;$entry").apply()
        cameras = cameras + SpeedCamera(lastLat, lastLon, limit, true)
        return true
    }

    fun setOverspeedBeep(on: Boolean) {
        overspeedBeep = on
        context?.getSharedPreferences("mmi_nav", Context.MODE_PRIVATE)?.edit()?.putBoolean("overspeed_beep", on)?.apply()
    }

    fun setCameraAlerts(on: Boolean) {
        cameraAlerts = on
        context?.getSharedPreferences("mmi_nav", Context.MODE_PRIVATE)?.edit()?.putBoolean("camera_alerts", on)?.apply()
    }

    fun setVoice(on: Boolean) {
        context?.getSharedPreferences("mmi_nav", Context.MODE_PRIVATE)?.edit()?.putBoolean("voice", on)?.apply()
        if (!on) tts?.stop()
        _state.update { it.copy(voiceOn = on) }
    }

    fun stop() {
        context?.let { NavigationService.stop(it) }
        scope.launch(worker) {
            spokenStage.clear()
            arrivalSpoken = false
            awaitingFix = false
            progressIndex = 0
            _state.update {
                it.copy(destination = null, route = null, calculating = false, rerouting = false, message = null,
                    next = null, following = null, arrived = false, distanceToNextM = 0.0, remainingM = 0.0, remainingS = 0.0)
            }
        }
    }

    /** Plans a route from the last GPS fix. Without routing data it falls back to a direct line. */
    fun navigateTo(place: Place) {
        context?.let { NavigationService.start(it) }
        scope.launch(worker) { navigateNow(place) }
    }

    private fun navigateNow(place: Place) {
        spokenStage.clear()
        arrivalSpoken = false
        progressIndex = 0
        awaitingFix = false
        _state.update { it.copy(destination = place, route = null, calculating = true, message = null, arrived = false, next = null) }
        if (graph == null) loadData()
        val g = graph
        val message: String?
        var route: Route? = null
        when {
            g == null -> message = "Routing data not imported — showing direct line"
            lastLat.isNaN() -> {
                awaitingFix = true
                message = "Waiting for GPS to plan the route"
            }
            else -> {
                route = plan(g, lastLat, lastLon, _state.value.course.takeIf { _state.value.speedKmh > 5f }, place)
                message = if (route == null) "No road route found — showing direct line" else null
            }
        }
        _state.update { it.copy(route = route, calculating = false, message = message) }
        route?.maneuvers?.firstOrNull()?.let { if (it.type == ManeuverType.DEPART) speak(it.spokenText) }
    }

    private fun plan(g: RoutingGraph, lat: Double, lon: Double, heading: Float?, place: Place): Route? {
        val start = g.snap(lat, lon, heading, 400.0) ?: return null
        val end = g.snap(place.lat, place.lon, null, 3000.0) ?: return null
        val p = planner ?: RoutePlanner(g).also { planner = it }
        return runCatching { p.plan(start, end, heading) }.getOrNull()
    }

    private fun update(lat: Double, lon: Double, speedMps: Float) {
        lastLat = lat
        lastLon = lon
        if (awaitingFix) {
            awaitingFix = false
            val g = graph
            val dest = _state.value.destination
            if (g != null && dest != null) {
                _state.update { it.copy(calculating = true) }
                val route = plan(g, lat, lon, null, dest)
                _state.update {
                    it.copy(route = route, calculating = false,
                        message = if (route == null) "No road route found — showing direct line" else null)
                }
                route?.maneuvers?.firstOrNull()?.let { if (it.type == ManeuverType.DEPART) speak(it.spokenText) }
            }
        }
        var course = _state.value.course
        if (courseLat.isNaN() || distanceMeters(courseLat, courseLon, lat, lon) > 8.0) {
            if (!courseLat.isNaN()) course = bearing(courseLat, courseLon, lat, lon)
            courseLat = lat
            courseLon = lon
        }
        val speedKmh = speedMps * 3.6f
        val s = _state.value
        var next = s.next
        var following = s.following
        var distNext = s.distanceToNextM
        var remaining = s.remainingM
        var remainingS = s.remainingS
        var arrived = s.arrived
        var limit = 0
        val route = s.route

        if (route != null && !arrived) {
            val (index, along, offset) = project(route, lat, lon)
            progressIndex = index
            if (offset > 50.0) offRouteFixes++ else offRouteFixes = 0
            if (offRouteFixes >= 3 && !s.rerouting) {
                offRouteFixes = 0
                reroute(course.takeIf { speedKmh > 5f })
            }
            val mi = route.maneuvers.indexOfFirst { it.alongM > along + 5.0 }
            next = route.maneuvers.getOrNull(mi)
            following = route.maneuvers.getOrNull(mi + 1)
            distNext = (next?.alongM ?: route.lengthM) - along
            remaining = (route.lengthM - along).coerceAtLeast(0.0)
            remainingS = (route.durationS - route.timeAt(along)).coerceAtLeast(0.0)
            limit = route.pieceAtPoint(index)?.let { graph?.maxspeed(it.useg) } ?: 0
            if (next != null && mi >= 0) announce(mi, next, distNext, speedMps)
            if (remaining < 30.0) {
                arrived = true
                if (!arrivalSpoken) {
                    arrivalSpoken = true
                    speak("You have arrived at your destination")
                }
            }
        } else {
            val now = System.currentTimeMillis()
            if (now - lastFreeSnap > 2000) {
                lastFreeSnap = now
                val g = graph
                freeDriveLimit = g?.snap(lat, lon, course.takeIf { speedKmh > 5f }, 25.0)?.let { g.maxspeed(it.useg) } ?: 0
            }
            limit = freeDriveLimit
        }

        val camera = if (cameraAlerts) nearestCameraAhead(lat, lon, course, speedKmh) else null
        val overspeed = limit > 0 && speedKmh > limit + 4
        if (overspeed && !s.overspeed && overspeedBeep) beep()

        _state.update {
            it.copy(
            next = next, following = following, distanceToNextM = distNext, remainingM = remaining,
            remainingS = remainingS, arrived = arrived, speedLimit = limit, speedKmh = speedKmh,
            overspeed = overspeed, camera = camera, course = course,
            )
        }
    }

    private fun reroute(heading: Float?) {
        val g = graph ?: return
        val dest = _state.value.destination ?: return
        _state.update { it.copy(rerouting = true) }
        speak("Recalculating")
        val route = plan(g, lastLat, lastLon, heading, dest)
        spokenStage.clear()
        progressIndex = 0
        _state.update { it.copy(route = route ?: it.route, rerouting = false) }
    }

    /** Nearest point on the route around the last known progress: (point index, along metres, offset metres). */
    private fun project(route: Route, lat: Double, lon: Double): Triple<Int, Double, Double> {
        val n = route.lat.size
        val from = (progressIndex - 3).coerceAtLeast(0)
        val to = (progressIndex + 120).coerceAtMost(n - 2)
        val cosLat = cos(Math.toRadians(lat))
        var bestIdx = progressIndex.coerceIn(0, (n - 2).coerceAtLeast(0))
        var bestT = 0.0
        var bestD = Double.MAX_VALUE
        for (i in from..to) {
            val ax = (route.lon[i] - lon) * cosLat * 111320.0
            val ay = (route.lat[i] - lat) * 111320.0
            val bx = (route.lon[i + 1] - lon) * cosLat * 111320.0
            val by = (route.lat[i + 1] - lat) * 111320.0
            val dx = bx - ax
            val dy = by - ay
            val len2 = dx * dx + dy * dy
            val t = if (len2 > 0) ((-ax * dx - ay * dy) / len2).coerceIn(0.0, 1.0) else 0.0
            val px = ax + t * dx
            val py = ay + t * dy
            val d = sqrt(px * px + py * py)
            if (d < bestD) {
                bestD = d; bestIdx = i; bestT = t
            }
        }
        if (n < 2) return Triple(0, 0.0, 0.0)
        val along = route.cumDist[bestIdx] + (route.cumDist[bestIdx + 1] - route.cumDist[bestIdx]) * bestT
        return Triple(bestIdx, along, bestD)
    }

    private fun announce(index: Int, m: Maneuver, distance: Double, speedMps: Float) {
        if (m.type == ManeuverType.DEPART) return
        val stage = spokenStage[index] ?: 0
        val far = when {
            speedMps > 22f -> 1500.0
            speedMps > 14f -> 800.0
            else -> 400.0
        }
        val near = maxOf(120.0, speedMps * 9.0)
        val now = maxOf(25.0, speedMps * 3.0)
        when {
            stage < 3 && distance <= now -> { spokenStage[index] = 3; speak(m.spokenText) }
            stage < 2 && distance <= near -> { spokenStage[index] = 2; speak("In ${spokenDistance(distance)}, ${m.spokenText.replaceFirstChar { it.lowercase() }}") }
            stage < 1 && distance <= far && distance > near + 150 -> { spokenStage[index] = 1; speak("In ${spokenDistance(distance)}, ${m.spokenText.replaceFirstChar { it.lowercase() }}") }
        }
    }

    private fun spokenDistance(m: Double): String = when {
        m >= 950 -> {
            val km = (m / 100.0).roundToInt() / 10.0
            if (km == km.toInt().toDouble()) "${km.toInt()} kilometer" + (if (km.toInt() == 1) "" else "s") else "$km kilometers"
        }
        m >= 100 -> "${(m / 50.0).roundToInt() * 50} meters"
        else -> "${(m / 10.0).roundToInt() * 10} meters"
    }

    private fun nearestCameraAhead(lat: Double, lon: Double, course: Float, speedKmh: Float): CameraAlert? {
        if (speedKmh < 5f) return null
        var best: CameraAlert? = null
        cameras.forEachIndexed { i, c ->
            if (abs(c.lat - lat) > 0.01 || abs(c.lon - lon) > 0.012) {
                announcedCameras.remove(i)
                return@forEachIndexed
            }
            val d = distanceMeters(lat, lon, c.lat, c.lon)
            if (d > 1000) announcedCameras.remove(i)
            if (d > 800 || angleDiff(bearing(lat, lon, c.lat, c.lon), course) > 40f) return@forEachIndexed
            if (best == null || d < best!!.distanceM) best = CameraAlert(d, c.limit, c.user)
            if (d < 500 && announcedCameras.add(i)) {
                beep()
                speak(if (c.limit > 0) "Speed camera ahead. Limit ${c.limit}" else "Speed camera ahead")
            }
        }
        return best
    }

    private fun speak(text: String) {
        val t = tts ?: return
        if (!ttsReady || !_state.value.voiceOn) return
        requestFocus()
        t.speak(text, TextToSpeech.QUEUE_FLUSH, null, "mmi-nav")
    }

    private val focusListener = AudioManager.OnAudioFocusChangeListener { }

    @Suppress("DEPRECATION")
    private fun requestFocus() {
        val am = context?.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
        am.requestAudioFocus(focusListener, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
    }

    @Suppress("DEPRECATION")
    private fun releaseFocus() {
        val am = context?.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
        am.abandonAudioFocus(focusListener)
    }

    private fun beep() {
        runCatching {
            val t = tone ?: ToneGenerator(AudioManager.STREAM_MUSIC, 80).also { tone = it }
            t.startTone(ToneGenerator.TONE_PROP_BEEP2, 250)
        }
    }
}
