package com.openlauncher.app.util

import android.os.Handler
import android.os.Looper
import android.os.Message
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * MMI's own link to the car, without the vendor apps: it listens to the unit's
 * car library (android.tw.john.TWUtil, part of the system, not an app) and
 * decodes the MCU / CAN-box messages itself. Listen-only for now; it switches
 * itself off if the library refuses a normal app (then the vendor apps stay the
 * source, as before). Message layouts come from the vendor apps' code
 * (see the protocol notes): 0x0501 CAN-box frames, 0x0507 radar, 0x0401 radio.
 */
object CarCore {
    data class CarData(
        val voltage: Float? = null,
        val coolantC: Int? = null,
        val oilLifePct: Int? = null,
        val rangeKm: Int? = null,
        val avgConsumption: Float? = null,
        /** FL, FR, RL, RR in kPa. */
        val tyresKpa: List<Int>? = null,
        /** Bits: 1 FL, 2 FR, 4 RL, 8 RR, 16 trunk, 32 hood. */
        val doors: Int = 0,
        /** Nearest obstacle in cm, rear and front (null = none). */
        val rearCm: Int? = null,
        val frontCm: Int? = null,
        val speedKmh: Int? = null,
    )

    private const val KEY = 513
    private const val RADIO = 1025
    private const val CANBOX = 1281
    private const val RADAR = 1287
    private const val RADAR_SIDE = 1294
    private const val REVERSE = 40732
    private const val FOREGROUND = 40448 // 0x9E00: which vendor screen is in front
    private const val SOURCE = 40465 // 0x9E11: audio source requests
    private const val RADIO_PRESET = 1026 // 0x0402: preset entries
    private const val RADIO_RDS = 1028    // 0x0404: RDS flags, PTY
    private const val RADIO_TEXT = 1029   // 0x0405: radio text
    private const val RADIO_CMD = 40449   // 0x9E01: next / previous / seek from the car's buttons
    private val IDS = shortArrayOf(
        KEY.toShort(), RADIO.toShort(), CANBOX.toShort(), RADAR.toShort(), RADAR_SIDE.toShort(), REVERSE.toShort(),
        FOREGROUND.toShort(), SOURCE.toShort(), 515.toShort(), 769.toShort(),
    )
    // The radio's own messages, as the vendor radio app opens them, so MMI is the whole radio.
    private val RADIO_IDS = shortArrayOf(RADIO_PRESET.toShort(), RADIO_RDS.toShort(), RADIO_TEXT.toShort(), RADIO_CMD.toShort())

    /** What the tuner reports: the real frequency, RDS name and text, seek / stereo state, its presets. */
    data class RadioState(
        val am: Boolean = false,
        val freq: Float = 0f,
        val ps: String? = null,
        val text: String? = null,
        val pty: Int = 0,
        val seeking: Boolean = false,
        val stereo: Boolean = false,
        val local: Boolean = false,
        /** The tuner's own preset memory: index to (frequency, RDS name). */
        val presets: Map<Int, Pair<Float, String?>> = emptyMap(),
    )
    private val _radio = MutableStateFlow(RadioState())
    val radio: StateFlow<RadioState> = _radio

    /** Last button from the car (dash panel, steering wheel, knobs): press type and code. */
    data class CarKey(val code: Int, val press: Int, val at: Long)
    private val _lastKey = MutableStateFlow<CarKey?>(null)
    val lastKey: StateFlow<CarKey?> = _lastKey
    // Every press in order: a StateFlow drops the in-between steps of a fast knob turn.
    private val _keys = kotlinx.coroutines.flow.MutableSharedFlow<CarKey>(extraBufferCapacity = 32)
    val keys: kotlinx.coroutines.flow.SharedFlow<CarKey> = _keys
    private var keysLogged = 0

    private val _data = MutableStateFlow(CarData())
    val data: StateFlow<CarData> = _data
    private val _active = MutableStateFlow(false)
    /** True once the car library has accepted MMI. */
    val active: StateFlow<Boolean> = _active
    var status = "Not started"
        private set

    private var tw: Any? = null

    fun start() {
        if (tw != null) return
        runCatching {
            val cls = Class.forName("android.tw.john.TWUtil")
            val util = cls.getConstructor().newInstance()
            // With the radio ids first; if the library refuses those, the original set.
            var opened = cls.getMethod("open", ShortArray::class.java).invoke(util, IDS + RADIO_IDS) as Int
            if (opened != 0) opened = cls.getMethod("open", ShortArray::class.java).invoke(util, IDS) as Int
            if (opened != 0) { status = "Car library refused (open $opened)"; return }
            val handler = object : Handler(Looper.getMainLooper()) {
                override fun handleMessage(m: Message) = runCatching { onMessage(m) }.let { }
            }
            cls.getMethod("start").invoke(util)
            cls.getMethod("addHandler", String::class.java, Handler::class.java).invoke(util, "mmi-core", handler)
            tw = util
            _active.value = true
            status = "Connected"
            TwBridge.log("CarCore: connected to the car library")
        }.onFailure { e ->
            val cause = (e as? java.lang.reflect.InvocationTargetException)?.targetException ?: e
            status = "Blocked: ${cause.javaClass.simpleName}"
            TwBridge.log("CarCore: car library not usable ($cause)")
        }
    }

    /**
     * Sends a command to the car (MCU) through the library, only when the library
     * accepted MMI. Used for the radio and the reverse-mute setting, with the same
     * commands the vendor apps send. Returns false when not available.
     */
    fun write(what: Int, arg1: Int, arg2: Int): Boolean {
        val util = tw ?: return false
        return runCatching {
            util.javaClass.getMethod("write", Int::class.java, Int::class.java, Int::class.java).invoke(util, what, arg1, arg2)
            true
        }.getOrDefault(false)
    }

    // Radio straight to the MCU, without opening the vendor radio app.
    private const val SOURCE_REQUEST = 40465 // 0x9E11
    private const val RADIO_SOURCE = 1

    fun radioSource(): Boolean = write(SOURCE_REQUEST, 192, RADIO_SOURCE)
    fun radioTune(am: Boolean, freq: Float): Boolean =
        if (am) write(1026, 130, freq.toInt()) else write(1026, 128, Math.round(freq * 100))
    fun radioSeek(up: Boolean): Boolean = write(RADIO, 1, if (up) 1 else 0)

    /** The MCU's reverse attenuation: 0 = keep music while reversing, 1 = mute. */
    fun setReverseMute(mute: Boolean): Boolean = write(260, 2, if (mute) 1 else 0)

    private fun be16(b: ByteArray, i: Int) = ((b[i].toInt() and 0xFF) shl 8) or (b[i + 1].toInt() and 0xFF)
    private fun u(b: ByteArray, i: Int) = b[i].toInt() and 0xFF

    private fun update(change: (CarData) -> CarData) { _data.value = change(_data.value) }

    // The first few frames of each kind go to mmi-twlog.txt, to check the Astra's exact layouts.
    private val seen = HashMap<Long, Int>()

    private fun onMessage(m: Message) {
        val b = m.obj as? ByteArray
        val kind = (m.what.toLong() shl 32) or (m.arg1.toLong() and 0xFFFFFFFFL)
        if (m.what == KEY) {
            // Every button press is logged (capped), so each panel button's code can be mapped.
            val key = CarKey(m.arg2, m.arg1, System.currentTimeMillis())
            _lastKey.value = key
            _keys.tryEmit(key)
            if (keysLogged++ < 400) TwBridge.log("CARKEY code=${m.arg2} press=${m.arg1}")
        }
        val n = seen.getOrDefault(kind, 0)
        if (m.what != KEY && n < 3 && seen.size < 200) {
            seen[kind] = n + 1
            TwBridge.log("CAR what=${m.what} arg1=${m.arg1} arg2=${m.arg2} obj=" + (b?.joinToString(" ") { "%02X".format(it) } ?: m.obj?.toString().orEmpty()))
        }
        when (m.what) {
            CANBOX -> if (b != null && b.isNotEmpty()) canBox(m.arg1, b)
            RADAR -> if (b != null && b.size >= 20) {
                val rear = (10..13).map { u(b, it) }.filter { it > 0 }.minOrNull()
                val front = (15..18).map { u(b, it) }.filter { it > 0 }.minOrNull()
                update { it.copy(rearCm = rear, frontCm = front) }
            }
            RADIO -> radio(m)
            RADIO_PRESET -> {
                val idx = m.arg1 and 0xFF
                val f = m.arg2
                if (f > 0 && idx < 30) {
                    val freq = freqOf(f)
                    _radio.value = _radio.value.let { it.copy(presets = it.presets + (idx to (freq to (m.obj as? String)?.trim()?.takeIf { s -> s.isNotEmpty() }))) }
                }
            }
            RADIO_TEXT -> (m.obj as? String)?.trim()?.let { t -> _radio.value = _radio.value.copy(text = t.takeIf { it.isNotEmpty() }) }
            RADIO_RDS -> _radio.value = _radio.value.copy(pty = m.arg2)
            RADIO_CMD -> radioCommand(m.arg1)
            REVERSE -> TwBridge.onReverseFromCore(m.arg1 != 0)
        }
    }

    /** Raise GM CAN-box frames (0x0501, arg1 = frame id). */
    private fun canBox(id: Int, b: ByteArray) {
        // Only on the cars these decoders were made for (CarProfile): on any other car the
        // same frame numbers mean something else, and the Car Info Service has its data.
        if (id == 0x24) { if (!CarProfile.raiseDoors) return } else if (!CarProfile.gmFrames) return
        when (id) {
            0x4A -> when (u(b, 0)) {
                1 -> if (b.size >= 6) update { it.copy(tyresKpa = (2..5).map { i -> u(b, i) * 4 }) }
                2 -> if (b.size >= 5) {
                    val range = be16(b, 1)
                    update { it.copy(rangeKm = range.takeIf { r -> r != 0xFFFF && r != 255 }, avgConsumption = be16(b, 3) * 0.1f) }
                }
                4 -> if (b.size >= 6) update {
                    it.copy(coolantC = u(b, 1) - 40, oilLifePct = u(b, 2), voltage = be16(b, 4) * 0.1f)
                }
            }
            0x24 -> {
                // Door bits: b0 bit6 FL, bit7 FR, bit4 RL, bit5 RR, bit3 trunk, bit2 hood.
                val v = u(b, 0)
                var doors = 0
                if (v and 0x40 != 0) doors = doors or 1
                if (v and 0x80 != 0) doors = doors or 2
                if (v and 0x10 != 0) doors = doors or 4
                if (v and 0x20 != 0) doors = doors or 8
                if (v and 0x08 != 0) doors = doors or 16
                if (v and 0x04 != 0) doors = doors or 32
                update { it.copy(doors = CarProfile.mapDoors(doors)) }
            }
            // Doors on this box (Astra J, Raise): frame 0x14 (also 0x38 on other GM boxes),
            // b0 bit7 FL, bit6 FR, bit5 RL, bit4 RR, bit3 trunk, as the vendor service reads it.
            0x14, 0x38 -> if (b.isNotEmpty()) {
                val v = u(b, 0)
                var doors = 0
                if (v and 0x80 != 0) doors = doors or 1
                if (v and 0x40 != 0) doors = doors or 2
                if (v and 0x20 != 0) doors = doors or 4
                if (v and 0x10 != 0) doors = doors or 8
                if (v and 0x08 != 0) doors = doors or 16
                update { it.copy(doors = CarProfile.mapDoors(doors)) }
            }
            0x68 -> if (b.size >= 3) update {
                it.copy(speedKmh = u(b, 0).takeIf { s -> s != 255 }, rangeKm = be16(b, 1).takeIf { r -> r != 0xFFFF } ?: it.rangeKm)
            }
        }
    }

    /** Radio status (0x0401): arg1 1 = band, 2 = frequency (+ station name in obj). */
    private var am = false
    private fun freqOf(f: Int): Float = if (am) f.toFloat() else if (f > 2000) f / 100f else f / 10f

    private fun radio(m: Message) {
        when (m.arg1) {
            0 -> {
                // Status bits (as the vendor radio reads them): 7 seeking, 2 stereo, 3 local.
                val b = m.arg2
                _radio.value = _radio.value.copy(seeking = b and 0x80 != 0, stereo = b and 0x04 != 0, local = b and 0x08 != 0)
            }
            1 -> {
                am = m.arg2 == 2
                _radio.value = _radio.value.copy(am = am)
            }
            2 -> {
                val f = m.arg2
                if (f <= 0) return
                val freq = freqOf(f)
                val ps = (m.obj as? String)?.trim()?.takeIf { it.isNotEmpty() }
                // A new station: its old radio text no longer applies.
                val changed = kotlin.math.abs(freq - _radio.value.freq) > 0.01f
                _radio.value = _radio.value.copy(am = am, freq = freq, ps = ps, text = if (changed) null else _radio.value.text)
                TwBridge.onRadioFromCore(am, freq, ps)
            }
            3 -> _radio.value = _radio.value.copy(pty = m.arg2)
        }
    }

    /**
     * The car's own radio buttons (sent by the car service to whoever is the radio):
     * 3 next favourite, 4 previous favourite, 9 seek down, 10 seek up. Only answered
     * when the vendor radio app is gone, so the two never both act.
     */
    private fun radioCommand(cmd: Int) {
        if (vendorRadioPresent) return
        when (cmd) {
            // The car service turns knob steps into next / previous station too. While MMI's
            // screens are in front MMI already uses the knob (highlight, or tuning on Radio),
            // so a step that just came from the knob must not also change the station.
            3, 4 -> if (!knobJustTurned()) TwBridge.radioNextFavourite(cmd == 3)
            9 -> TwBridge.radioSeek(false)
            10 -> TwBridge.radioSeek(true)
        }
    }

    /** Knob codes (big knob 5 / 6, side 19 / 21) as MMI's screens use them. */
    private val KNOB = setOf(5, 6, 19, 21)

    /** MMI's screens (new or classic) are in front. Set by the activities. */
    @Volatile var screensInFront = false

    private fun knobJustTurned(): Boolean {
        val k = _lastKey.value ?: return false
        return screensInFront && k.code in KNOB && System.currentTimeMillis() - k.at < 800
    }

    /** True while the vendor radio app is installed and enabled. */
    @Volatile var vendorRadioPresent = true

    // More of the tuner, as the vendor radio app sends it.
    fun radioScan(): Boolean = write(RADIO, 2, 1)     // scan the band and store the strong stations
    fun radioStop(): Boolean = write(RADIO, 0, 0)
    fun radioSetBand(am: Boolean): Boolean = write(RADIO, 5, if (am) 2 else 0)
    fun radioToggleLocal(): Boolean = write(RADIO, 4, 0)

    fun doorsText(doors: Int): String? {
        if (doors == 0) return null
        val names = listOfNotNull(
            "Front left".takeIf { doors and 1 != 0 }, "Front right".takeIf { doors and 2 != 0 },
            "Rear left".takeIf { doors and 4 != 0 }, "Rear right".takeIf { doors and 8 != 0 },
            "Boot".takeIf { doors and 16 != 0 }, "Bonnet".takeIf { doors and 32 != 0 },
        )
        return names.joinToString(", ") + if (names.size == 1) " open" else " open"
    }
}
