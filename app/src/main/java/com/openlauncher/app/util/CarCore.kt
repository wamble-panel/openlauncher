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
    private val IDS = shortArrayOf(KEY.toShort(), RADIO.toShort(), CANBOX.toShort(), RADAR.toShort(), RADAR_SIDE.toShort(), REVERSE.toShort())

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
            val opened = cls.getMethod("open", ShortArray::class.java).invoke(util, IDS) as Int
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

    private fun be16(b: ByteArray, i: Int) = ((b[i].toInt() and 0xFF) shl 8) or (b[i + 1].toInt() and 0xFF)
    private fun u(b: ByteArray, i: Int) = b[i].toInt() and 0xFF

    private fun update(change: (CarData) -> CarData) { _data.value = change(_data.value) }

    private fun onMessage(m: Message) {
        val b = m.obj as? ByteArray
        when (m.what) {
            CANBOX -> if (b != null && b.isNotEmpty()) canBox(m.arg1, b)
            RADAR -> if (b != null && b.size >= 20) {
                val rear = (10..13).map { u(b, it) }.filter { it > 0 }.minOrNull()
                val front = (15..18).map { u(b, it) }.filter { it > 0 }.minOrNull()
                update { it.copy(rearCm = rear, frontCm = front) }
            }
            RADIO -> radio(m)
            REVERSE -> TwBridge.onReverseFromCore(m.arg1 != 0)
        }
    }

    /** Raise GM CAN-box frames (0x0501, arg1 = frame id). */
    private fun canBox(id: Int, b: ByteArray) {
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
                update { it.copy(doors = doors) }
            }
            0x68 -> if (b.size >= 3) update {
                it.copy(speedKmh = u(b, 0).takeIf { s -> s != 255 }, rangeKm = be16(b, 1).takeIf { r -> r != 0xFFFF } ?: it.rangeKm)
            }
        }
    }

    /** Radio status (0x0401): arg1 1 = band, 2 = frequency (+ station name in obj). */
    private var am = false
    private fun radio(m: Message) {
        when (m.arg1) {
            1 -> am = m.arg2 == 2
            2 -> {
                val f = m.arg2
                if (f <= 0) return
                val freq = if (am) f.toFloat() else if (f > 2000) f / 100f else f / 10f
                TwBridge.onRadioFromCore(am, freq, (m.obj as? String)?.trim()?.takeIf { it.isNotEmpty() })
            }
        }
    }

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
