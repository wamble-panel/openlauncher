package com.openlauncher.app.map

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Environment
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import com.openlauncher.app.service.NavigationService
import com.openlauncher.app.util.CarLocation
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

enum class HazardType(val label: String) { BUMP("Speed bump"), POTHOLE("Pothole") }

data class HazardAlert(val type: HazardType, val distanceM: Double)

enum class CarProfile(val label: String, val wheelbaseM: Double) {
    ASTRA_J("Opel Astra J", 2.685),
    SUNNY("Nissan Sunny", 2.600),
    OTHER("Other car", 2.650),
}

data class RoadStats(
    val sensor: Boolean = false,
    val rateHz: Double = 0.0,
    val noiseFloor: Double = 0.0,
    val spots: Int = 0,
    val confirmed: Int = 0,
    val jolts: Int = 0,
    val axleMatches: Int = 0,
)

/**
 * Road memory: learns potholes and speed bumps from the unit's accelerometer and
 * GPS, and warns ~4 s before known ones. Pipeline per sensor sample:
 *  1. gravity estimate (2 s low-pass) → vertical component, whatever the mounting angle;
 *  2. 1–20 Hz band-pass (two biquads) → suspension motion only;
 *  3. adaptive noise floor (10 s) → a jolt must stand ~6× above this road's roughness;
 *  4. two-axle check: a real road feature hits again after wheelbase ÷ speed;
 *  5. bump vs pothole from duration, speed and braking before it.
 * Jolts are map-matched to the road, merged per direction, confirmed by repeat
 * hits and removed after clean passes. Memory is a few KB; storage ~19 B/spot.
 */
@OptIn(ExperimentalCoroutinesApi::class)
object RoadMemory {
    private const val WARN_SCORE = 3.0f
    private const val MERGE_M = 15.0
    private const val GPS_LAG_S = 0.5
    private const val CELL = 0.002 // ~200 m grid for lookups
    private const val FILE_MAGIC = 0x524D454D // "RMEM"

    private class Spot(
        var lat: Double, var lon: Double, val bearing: Float, var type: HazardType,
        var score: Float, var hits: Int, var passes: Int, var lastDay: Int, var weight: Double,
    ) {
        var lastHitAt = 0L
    }

    private class Fix(val at: Long, val lat: Double, val lon: Double, val speed: Float, val accuracy: Float, val course: Float)

    internal val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    internal val worker = Dispatchers.Default.limitedParallelism(1)
    private var app: Context? = null

    private val spots = ArrayList<Spot>()
    private val grid = HashMap<Long, MutableList<Spot>>()
    private val fixes = ArrayDeque<Fix>()
    private val nearest = HashMap<Spot, Double>()
    private val announced = HashSet<Spot>()
    private var dirty = false
    private var lastSave = 0L
    private var lastTouched: Spot? = null

    private val _alert = MutableStateFlow<HazardAlert?>(null)
    val alert: StateFlow<HazardAlert?> = _alert
    private val _stats = MutableStateFlow(RoadStats())
    val stats: StateFlow<RoadStats> = _stats
    /** Bumped whenever the set of confirmed spots changes (map layer refresh). */
    private val _version = MutableStateFlow(0)
    val version: StateFlow<Int> = _version

    var car: CarProfile = CarProfile.SUNNY
        private set
    var warnings: Boolean = true
        private set

    @Volatile internal var speedMps = 0f
    @Volatile internal var lastTouchNs = 0L

    // ── Setup and settings ───────────────────────────────────────────────────

    fun init(context: Context) {
        if (app != null) return
        val ctx = context.applicationContext
        app = ctx
        val prefs = ctx.getSharedPreferences("mmi_road", Context.MODE_PRIVATE)
        car = runCatching { CarProfile.valueOf(prefs.getString("car", CarProfile.SUNNY.name)!!) }.getOrDefault(CarProfile.SUNNY)
        warnings = prefs.getBoolean("warnings", true)
        val sm = ctx.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        _stats.value = _stats.value.copy(sensor = sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) != null)
        scope.launch(worker) {
            load(File(ctx.filesDir, "road-memory.bin"))
            publishStats()
            CarLocation.get(ctx).location.filterNotNull().collect {
                onFix(Fix(SystemClock.elapsedRealtimeNanos(), it.latitude, it.longitude, it.speedMps, it.accuracy, NavigationEngine.state.value.course))
            }
        }
        scope.launch(worker) {
            while (true) {
                delay(180_000)
                if (dirty) save()
            }
        }
    }

    fun setCar(profile: CarProfile) {
        car = profile
        app?.getSharedPreferences("mmi_road", Context.MODE_PRIVATE)?.edit()?.putString("car", profile.name)?.apply()
    }

    fun setWarnings(on: Boolean) {
        warnings = on
        if (!on) _alert.value = null
        app?.getSharedPreferences("mmi_road", Context.MODE_PRIVATE)?.edit()?.putBoolean("warnings", on)?.apply()
    }

    /** Screen taps shake the unit; jolts right after a touch are ignored. */
    fun onTouch() {
        lastTouchNs = SystemClock.elapsedRealtimeNanos()
    }

    /** Driver marks a hazard at the current position: confirmed at once. */
    fun markHere(type: HazardType): Boolean {
        if (lastFixAt == 0L) return false
        scope.launch(worker) {
            val f = fixes.lastOrNull() ?: return@launch
            addHit(f.lat, f.lon, f.course, type, WARN_SCORE + 1f, f.accuracy, SystemClock.elapsedRealtimeNanos())
            save()
        }
        return true
    }

    @Volatile private var lastFixAt = 0L

    /** "That was one": the last detected jolt is real — confirm it. */
    fun confirmLast(): Boolean {
        val s = lastTouched ?: return false
        scope.launch(worker) {
            s.score = max(s.score, WARN_SCORE + 1f)
            dirty = true
            publishStats()
            _version.value++
        }
        return true
    }

    // ── Sensor pipeline (runs on its own thread) ─────────────────────────────

    private var sensorThread: HandlerThread? = null
    private var listening = false
    private val detector = Detector()

    private fun startSensor() {
        val ctx = app ?: return
        if (listening) return
        val sm = ctx.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val accel = sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) ?: return
        val thread = sensorThread ?: HandlerThread("road-memory").also { it.start(); sensorThread = it }
        detector.reset()
        // ~50 Hz, delivered in small batches to save wake-ups on the slow CPU.
        listening = sm.registerListener(detector, accel, 20_000, 200_000, Handler(thread.looper))
    }

    private fun stopSensor() {
        val ctx = app ?: return
        if (!listening) return
        (ctx.getSystemService(Context.SENSOR_SERVICE) as SensorManager).unregisterListener(detector)
        listening = false
    }

    internal class Candidate(val at: Long, val peak: Float, val relative: Float, val axle: Boolean, val durationS: Double, val downFirst: Boolean)

    /** Second-order Butterworth section (RBJ cookbook), direct form I. */
    private class Biquad {
        private var b0 = 1.0; private var b1 = 0.0; private var b2 = 0.0; private var a1 = 0.0; private var a2 = 0.0
        private var x1 = 0.0; private var x2 = 0.0; private var y1 = 0.0; private var y2 = 0.0

        fun design(highPass: Boolean, f0: Double, fs: Double) {
            val w = 2 * PI * f0 / fs
            val alpha = sin(w) / (2 * 0.7071)
            val c = cos(w)
            val a0 = 1 + alpha
            if (highPass) {
                b0 = (1 + c) / 2 / a0; b1 = -(1 + c) / a0; b2 = (1 + c) / 2 / a0
            } else {
                b0 = (1 - c) / 2 / a0; b1 = (1 - c) / a0; b2 = (1 - c) / 2 / a0
            }
            a1 = -2 * c / a0; a2 = (1 - alpha) / a0
        }

        fun process(x: Double): Double {
            val y = b0 * x + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2
            x2 = x1; x1 = x; y2 = y1; y1 = y
            return y
        }

        fun clear() { x1 = 0.0; x2 = 0.0; y1 = 0.0; y2 = 0.0 }
    }

    private class Detector : SensorEventListener {
        private val hp = Biquad()
        private val lp = Biquad()
        private var fs = 0.0
        private var rateStart = 0L
        private var rateCount = 0
        private var lastT = 0L
        private var gx = 0.0; private var gy = 0.0; private var gz = 0.0
        private var warm = 0
        var floor = 0.15
            private set
        private val env = FloatArray(256)
        private val times = LongArray(256)
        private var head = 0

        private var pending = false
        private var t0 = 0L
        private var peak1 = 0f
        private var lastAbove = 0L
        private var downFirst = false
        private var cooldownUntil = 0L

        fun reset() {
            fs = 0.0; rateStart = 0L; rateCount = 0; lastT = 0L; warm = 0; pending = false
            hp.clear(); lp.clear()
        }

        val rate: Double get() = fs

        private fun designFor(rate: Double) {
            fs = rate
            hp.design(true, 1.0, rate)
            lp.design(false, min(20.0, rate * 0.42), rate)
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

        override fun onSensorChanged(e: SensorEvent) {
            val t = e.timestamp
            // Measure the real sample rate; cheap units often deliver far less than asked.
            if (rateStart == 0L) rateStart = t
            rateCount++
            val span = (t - rateStart) / 1e9
            if (span >= 2.0) {
                val r = rateCount / span
                if (fs == 0.0 || abs(r - fs) / fs > 0.15) designFor(r)
                rateStart = t; rateCount = 0
            }
            if (fs == 0.0) { lastT = t; gx = e.values[0].toDouble(); gy = e.values[1].toDouble(); gz = e.values[2].toDouble(); return }
            val dt = ((t - lastT) / 1e9).coerceIn(0.001, 0.2)
            lastT = t
            val x = e.values[0].toDouble(); val y = e.values[1].toDouble(); val z = e.values[2].toDouble()

            // 1. Gravity direction (slow average) → vertical acceleration.
            val ag = 1 - exp(-dt / 2.0)
            gx += (x - gx) * ag; gy += (y - gy) * ag; gz += (z - gz) * ag
            val gn = sqrt(gx * gx + gy * gy + gz * gz).coerceAtLeast(1.0)
            val vertical = (x * gx + y * gy + z * gz) / gn - gn

            // 2. Band-pass: suspension motion only.
            val band = lp.process(hp.process(vertical))
            val en = abs(band).toFloat()
            head = (head + 1) and 255
            env[head] = en; times[head] = t
            if (warm < (fs * 3).toInt()) { warm++; return }

            val v = RoadMemory.speedMps
            if (v < 2.8f) { pending = false; return } // < 10 km/h: parking, doors, people

            // 3. Adaptive floor: tracks this road's normal roughness (spikes clipped).
            if (!pending) {
                val af = 1 - exp(-dt / 10.0)
                floor += (min(en.toDouble(), floor * 3) - floor) * af
                floor = floor.coerceIn(0.05, 3.0)
            }
            val tau = RoadMemory.car.wheelbaseM / max(v.toDouble(), 2.8) // front→rear axle delay (s)

            if (!pending) {
                val threshold = max(6 * floor, 1.0)
                if (en > threshold && t > cooldownUntil && t - RoadMemory.lastTouchNs > 400_000_000L) {
                    pending = true; t0 = t; peak1 = en; lastAbove = t; downFirst = band < 0
                }
                return
            }
            val since = (t - t0) / 1e9
            if (since < 0.1 && en > peak1) peak1 = en
            if (since < min(0.6, tau * 0.7) && en > peak1 * 0.5f) lastAbove = t
            if (since >= tau * 1.3 + 0.12) {
                evaluate(tau)
                pending = false
                cooldownUntil = t + 300_000_000L
            }
        }

        private fun evaluate(tau: Double) {
            // 4. Two-axle check: second peak at wheelbase ÷ speed (±25 %), with a dip between.
            var peak2 = 0f
            var between = Float.MAX_VALUE
            val from2 = t0 + (tau * 0.75 * 1e9).toLong()
            val to2 = t0 + (tau * 1.3 * 1e9).toLong()
            val gapStart = t0 + 120_000_000L
            for (i in 0 until 256) {
                val ti = times[i]
                if (ti in from2..to2) peak2 = max(peak2, env[i])
                if (ti in gapStart until from2) between = min(between, env[i])
            }
            val dipOk = from2 - gapStart < 50_000_000L || between < 0.6f * min(peak1, peak2)
            val axle = peak2 > 0.35f * peak1 && peak2 > 3 * floor && dipOk
            val c = Candidate(t0, peak1, (peak1 / floor).toFloat(), axle, (lastAbove - t0) / 1e9, downFirst)
            RoadMemory.scope.launch(RoadMemory.worker) { RoadMemory.onCandidate(c) }
        }
    }

    // ── GPS side (worker) ────────────────────────────────────────────────────

    private var movingSince = 0L
    private var stoppedSince = 0L
    private var driveHeld = false
    private var jolts = 0
    private var axleMatches = 0

    private fun onFix(f: Fix) {
        fixes.addLast(f)
        lastFixAt = f.at
        while (fixes.size > 24) fixes.removeFirst()
        speedMps = f.speed
        val ctx = app ?: return

        // Sensor and background service only while driving.
        if (f.speed > 2.8f) {
            stoppedSince = 0L
            if (movingSince == 0L) movingSince = f.at
            startSensor()
            if (!driveHeld && f.speed > 4.2f) {
                driveHeld = true
                NavigationService.acquire(ctx, "drive")
            }
        } else if (f.speed < 1.4f) {
            if (stoppedSince == 0L) stoppedSince = f.at
            val stoppedFor = (f.at - stoppedSince) / 1e9
            if (stoppedFor > 60) { stopSensor(); movingSince = 0L }
            if (stoppedFor > 300 && driveHeld) {
                driveHeld = false
                NavigationService.release(ctx, "drive")
                if (dirty) save()
            }
        }
        checkPassesAndWarn(f)
        _stats.value = _stats.value.copy(rateHz = detector.rate, noiseFloor = detector.floor)
    }

    internal fun onCandidate(c: Candidate) {
        // Position at the jolt: last fix moved on along the course, GPS lag compensated.
        val f = fixes.lastOrNull { it.at <= c.at } ?: fixes.firstOrNull() ?: return
        val speedAt = f.speed
        val dtS = (c.at - f.at) / 1e9 + GPS_LAG_S
        val dist = speedAt * dtS
        val rad = Math.toRadians(f.course.toDouble())
        val lat = f.lat + dist * cos(rad) / 111_320.0
        val lon = f.lon + dist * sin(rad) / (111_320.0 * cos(Math.toRadians(f.lat)))

        // 5. Bump or pothole: drivers brake for bumps; bumps are slow and long.
        val windowStart = c.at - 6_000_000_000L
        val maxBefore = fixes.filter { it.at in windowStart..c.at }.maxOfOrNull { it.speed } ?: speedAt
        val braked = (maxBefore - speedAt) * 3.6f >= 8f
        val kmh = speedAt * 3.6f
        val type = when {
            kmh < 40f && (braked || c.durationS > 0.25) -> HazardType.BUMP
            c.durationS < 0.2 || c.downFirst -> HazardType.POTHOLE
            else -> HazardType.BUMP
        }
        var confidence = 1f
        if (c.axle) confidence += 1f
        if (type == HazardType.BUMP && braked) confidence += 0.5f
        if (c.axle && c.relative > 12f) confidence += 0.5f
        if (!c.axle && c.relative < 9f) confidence = 0.5f

        jolts++
        if (c.axle) axleMatches++
        addHit(lat, lon, f.course, type, confidence, f.accuracy, c.at)
    }

    private fun cell(lat: Double, lon: Double): Long =
        (Math.floorDiv((lat / CELL).toLong(), 1L) shl 32) xor ((lon / CELL).toLong() and 0xffffffffL)

    private fun near(lat: Double, lon: Double): List<Spot> {
        val out = ArrayList<Spot>()
        val r = (lat / CELL).toLong(); val c = (lon / CELL).toLong()
        for (dr in -1L..1L) for (dc in -1L..1L) {
            grid[((r + dr) shl 32) xor ((c + dc) and 0xffffffffL)]?.let { out += it }
        }
        return out
    }

    private fun index(s: Spot) {
        grid.getOrPut(cell(s.lat, s.lon)) { ArrayList() }.add(s)
    }

    private fun unindex(s: Spot) {
        grid[cell(s.lat, s.lon)]?.remove(s)
    }

    private fun today() = (System.currentTimeMillis() / 86_400_000L).toInt()

    private fun addHit(rawLat: Double, rawLon: Double, course: Float, type: HazardType, confidence: Float, accuracy: Float, at: Long) {
        // Map-match: snapping to the road removes the sideways GPS error.
        var lat = rawLat; var lon = rawLon; var bearing = course
        NavigationEngine.snapRoad(rawLat, rawLon, course)?.let { s ->
            lat = s.lat; lon = s.lon
            bearing = if (angleDiff(s.bearing, course) > 90f) (s.bearing + 180f) % 360f else s.bearing
        }
        val w = 1.0 / (accuracy.coerceIn(3f, 50f).toDouble().let { it * it })
        val existing = near(lat, lon).minByOrNull { distanceMeters(lat, lon, it.lat, it.lon) }
            ?.takeIf { distanceMeters(lat, lon, it.lat, it.lon) < MERGE_M && angleDiff(it.bearing, bearing) < 40f }
        val wasConfirmed = existing?.let { it.score >= WARN_SCORE } ?: false
        val spot = if (existing != null) {
            // Weighted average: each pass tightens the position.
            unindex(existing)
            val total = existing.weight + w
            existing.lat = (existing.lat * existing.weight + lat * w) / total
            existing.lon = (existing.lon * existing.weight + lon * w) / total
            existing.weight = total
            existing.score = min(existing.score + confidence, 10f)
            existing.hits = min(existing.hits + 1, 255)
            existing.lastDay = today()
            if (type == HazardType.BUMP && confidence > 1.5f) existing.type = HazardType.BUMP
            index(existing)
            existing
        } else {
            Spot(lat, lon, bearing, type, confidence, 1, 0, today(), w).also { spots += it; index(it) }
        }
        spot.lastHitAt = at
        lastTouched = spot
        dirty = true
        publishStats()
        if (!wasConfirmed && spot.score >= WARN_SCORE) _version.value++
    }

    private fun checkPassesAndWarn(f: Fix) {
        val nearby = near(f.lat, f.lon)
        var best: Pair<Spot, Double>? = null
        val lookahead = (f.speed * 4.0).coerceIn(30.0, 150.0) + 10.0
        for (s in nearby) {
            val d = distanceMeters(f.lat, f.lon, s.lat, s.lon)
            val aligned = angleDiff(s.bearing, f.course) < 45f
            if (!aligned) continue
            // Clean-pass tracking: came within 12 m, now leaving, and no jolt here meanwhile.
            val prev = nearest[s]
            if (d < 12.0) nearest[s] = min(prev ?: d, d)
            else if (prev != null && d > 20.0) {
                nearest.remove(s)
                announced.remove(s)
                val recentHit = f.at - s.lastHitAt < 8_000_000_000L
                if (!recentHit && f.speed > 4.2f) {
                    s.passes = min(s.passes + 1, 255)
                    val wasConfirmed = s.score >= WARN_SCORE
                    s.score -= 0.8f
                    dirty = true
                    if (s.score <= 0f && s.passes >= 3) {
                        unindex(s); spots.remove(s)
                    }
                    publishStats()
                    if (wasConfirmed && s.score < WARN_SCORE) _version.value++
                }
            }
            if (s.score < WARN_SCORE || d > lookahead) continue
            val toSpot = bearing(f.lat, f.lon, s.lat, s.lon)
            if (angleDiff(toSpot, f.course) > 35f) continue
            if (best == null || d < best.second) best = s to d
        }
        val alert = best?.takeIf { warnings && f.speed > 2.8f }
        _alert.value = alert?.let { HazardAlert(it.first.type, it.second) }
        if (alert != null && announced.add(alert.first)) chime()
    }

    private var tone: ToneGenerator? = null
    private fun chime() {
        runCatching {
            val t = tone ?: ToneGenerator(AudioManager.STREAM_MUSIC, 70).also { tone = it }
            t.startTone(ToneGenerator.TONE_PROP_ACK, 180)
        }
    }

    private fun publishStats() {
        confirmedSnapshot = spots.filter { it.score >= WARN_SCORE }.map { Triple(it.lat, it.lon, it.type == HazardType.BUMP) }
        _stats.value = _stats.value.copy(
            spots = spots.size,
            confirmed = spots.count { it.score >= WARN_SCORE },
            jolts = jolts,
            axleMatches = axleMatches,
        )
    }

    @Volatile private var confirmedSnapshot: List<Triple<Double, Double, Boolean>> = emptyList()

    /** Confirmed spots for the map layer: (lat, lon, isBump). */
    fun confirmedSpots(): List<Triple<Double, Double, Boolean>> = confirmedSnapshot

    // ── Storage, export, import ──────────────────────────────────────────────

    private fun save() {
        val ctx = app ?: return
        runCatching {
            val file = File(ctx.filesDir, "road-memory.bin")
            val tmp = File(ctx.filesDir, "road-memory.tmp")
            write(tmp, spots)
            tmp.renameTo(file)
            dirty = false
            lastSave = System.currentTimeMillis()
        }
    }

    private fun write(file: File, list: List<Spot>) {
        DataOutputStream(file.outputStream().buffered()).use { out ->
            out.writeInt(FILE_MAGIC); out.writeInt(1); out.writeInt(list.size)
            for (s in list) {
                out.writeInt((s.lat * 1e6).toInt()); out.writeInt((s.lon * 1e6).toInt())
                out.writeShort((s.bearing * 10).toInt()); out.writeByte(s.type.ordinal)
                out.writeByte(s.hits); out.writeByte(s.passes)
                out.writeFloat(s.score); out.writeShort(s.lastDay - 19_000)
            }
        }
    }

    private fun read(file: File): List<Spot> {
        if (!file.exists()) return emptyList()
        return runCatching {
            DataInputStream(file.inputStream().buffered()).use { inp ->
                if (inp.readInt() != FILE_MAGIC) return emptyList()
                inp.readInt()
                val n = inp.readInt()
                List(n) {
                    val lat = inp.readInt() / 1e6; val lon = inp.readInt() / 1e6
                    val bearing = inp.readShort() / 10f
                    val type = HazardType.entries.getOrElse(inp.readByte().toInt()) { HazardType.POTHOLE }
                    val hits = inp.readUnsignedByte(); val passes = inp.readUnsignedByte()
                    val score = inp.readFloat(); val day = inp.readShort() + 19_000
                    Spot(lat, lon, bearing, type, score, hits, passes, day, hits.coerceAtLeast(1) / 100.0)
                }
            }
        }.getOrDefault(emptyList())
    }

    private fun load(file: File) {
        val now = today()
        // Spots not hit for ~4 months are dropped (the road was likely repaired).
        read(file).filter { now - it.lastDay < 120 }.forEach { spots += it; index(it) }
        publishStats()
        _version.value++
    }

    private fun exportFile(): File =
        File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "mmi-road-memory.bin")

    /** Writes the memory to Download/mmi-road-memory.bin for the other car. */
    suspend fun export(): String? = kotlinx.coroutines.withContext(worker) {
        runCatching {
            val f = exportFile()
            f.parentFile?.mkdirs()
            write(f, spots)
            f.absolutePath
        }.getOrNull()
    }

    /** Merges memory files found on USB or in Download (from the other car). Returns spots merged. */
    suspend fun import(): Int = kotlinx.coroutines.withContext(worker) {
        if (app == null) return@withContext 0
        val roots = LinkedHashSet<File>()
        runCatching { roots += Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS) }
        runCatching { File("/storage").listFiles()?.filter { it.isDirectory && it.canRead() }?.let { roots += it } }
        val files = roots.flatMap { r -> runCatching { r.listFiles()?.toList() }.getOrNull().orEmpty() }
            .filter { it.isFile && it.name.startsWith("mmi-road-memory") && it.name.endsWith(".bin") && it != exportFile() }
        var merged = 0
        for (f in files) for (s in read(f)) {
            val match = near(s.lat, s.lon).firstOrNull {
                distanceMeters(s.lat, s.lon, it.lat, it.lon) < MERGE_M && angleDiff(it.bearing, s.bearing) < 40f
            }
            if (match != null) {
                match.score = max(match.score, s.score)
                match.hits = min(match.hits + s.hits, 255)
                match.lastDay = max(match.lastDay, s.lastDay)
            } else {
                spots += s; index(s)
            }
            merged++
        }
        if (merged > 0) {
            dirty = true
            save()
            publishStats()
            _version.value++
        }
        merged
    }
}
