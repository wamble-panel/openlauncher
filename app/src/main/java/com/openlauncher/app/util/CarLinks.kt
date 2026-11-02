package com.openlauncher.app.util

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Binder
import android.os.Bundle
import android.os.IBinder
import android.os.Parcel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Car data from the unit's Car Info Service (com.tw.carinfoservice), the same open
 * service the vendor's CarMate app uses: speed, rpm, battery, outside temperature,
 * steering angle, handbrake, headlights and indicators. Spoken to with plain Binder
 * transactions (no vendor code), see the protocol notes, section 24.
 */
object CarInfoLink {
    private const val SERVICE = "com.tw.carinfoservice.CarServiceAidl"
    private const val CALLBACK = "com.tw.carinfoservice.CarServiceCallBack"

    data class Info(
        val speedKmh: Int? = null,
        val rpm: Int? = null,
        val voltage: Float? = null,
        val outsideC: Float? = null,
        val steering: Int? = null,
        val handbrake: Boolean? = null,
        val headlights: Boolean? = null,
        /** -1 left, 1 right, 2 hazard, 0 off. */
        val indicator: Int = 0,
        val odometer: Float? = null,
        val range: Float? = null,
        val avgFuel: Float? = null,
        val instantFuel: Float? = null,
        /** FL, FR, RL, RR as the car reports them, with [tyreUnit]. */
        val tyres: List<Float?> = listOf(null, null, null, null),
        val tyreUnit: String? = null,
    )

    private val _info = MutableStateFlow(Info())
    val info: StateFlow<Info> = _info
    @Volatile var connected = false
        private set
    private var service: IBinder? = null
    private var bound = false

    // The service calls this for every value; it drops a callback that ever throws, so
    // every code is answered and nothing escapes.
    private val callback = object : Binder() {
        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            if (code == INTERFACE_TRANSACTION) {
                reply?.writeString(CALLBACK)
                return true
            }
            runCatching {
                data.enforceInterface(CALLBACK)
                when (code) {
                    1 -> data.readFloat().let { v -> set { it.copy(voltage = v) } }
                    2 -> data.readInt().let { v -> set { it.copy(headlights = v != 0) } }
                    11 -> data.readInt().let { v -> set { it.copy(handbrake = v != 0) } }
                    12 -> if (data.readInt() != 0) set { it.copy(indicator = 1) } else set { if (it.indicator == 1) it.copy(indicator = 0) else it }
                    13 -> if (data.readInt() != 0) set { it.copy(indicator = -1) } else set { if (it.indicator == -1) it.copy(indicator = 0) else it }
                    14 -> if (data.readInt() != 0) set { it.copy(indicator = 2) } else set { if (it.indicator == 2) it.copy(indicator = 0) else it }
                    15 -> data.readInt().let { v -> set { it.copy(speedKmh = v) } }
                    16 -> data.readInt().let { v -> set { it.copy(rpm = v) } }
                    18 -> data.readFloat().let { v -> set { it.copy(outsideC = v) } }
                    60 -> data.readInt().let { v -> set { it.copy(steering = v) } }
                    // Items for this car (CarServiceTheme): odometer, range, average and
                    // instant fuel use, tyre pressures. -1 means not reported.
                    4, 64 -> data.readFloat().takeIf { it >= 0 }.let { v -> set { it.copy(odometer = v) } }
                    5 -> data.readFloat().takeIf { it >= 0 }.let { v -> set { it.copy(range = v) } }
                    6 -> data.readFloat().takeIf { it >= 0 }.let { v -> set { it.copy(avgFuel = v) } }
                    7 -> data.readFloat().takeIf { it >= 0 }.let { v -> set { it.copy(instantFuel = v) } }
                    37, 38, 39, 40 -> {
                        val v = data.readFloat().takeIf { it >= 0 }
                        val unit = runCatching { data.readString() }.getOrNull()
                        set { it.copy(tyres = it.tyres.toMutableList().also { t -> t[code - 37] = v }, tyreUnit = unit ?: it.tyreUnit) }
                    }
                }
            }
            runCatching { reply?.writeNoException() }
            return true
        }
    }

    private fun set(change: (Info) -> Info) { _info.value = change(_info.value) }

    private val conn = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder) {
            service = binder
            connected = call(1) { it.writeStrongBinder(callback) }
            // Speed / rpm only stream while a page shows them (see stream()).
            if (streaming) call(47) else call(46)
            call(3)    // send every current value now
            TwBridge.log("CarInfoLink: " + if (connected) "connected" else "register refused")
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
            connected = false
        }
    }

    private fun call(code: Int, write: (Parcel) -> Unit = {}): Boolean {
        val b = service ?: return false
        val p = Parcel.obtain()
        val r = Parcel.obtain()
        return try {
            p.writeInterfaceToken(SERVICE)
            write(p)
            b.transact(code, p, r, 0)
            r.readException()
            true
        } catch (e: Exception) {
            false
        } finally {
            p.recycle(); r.recycle()
        }
    }

    @Volatile private var streaming = false

    /**
     * Speed and rpm many times a second only while a page shows them (Car information,
     * map): otherwise the car service, the binder and MMI would wake for nothing.
     */
    fun stream(on: Boolean) {
        if (streaming == on) return
        streaming = on
        call(if (on) 47 else 46)
    }

    fun start(context: Context) {
        if (bound) return
        bound = runCatching {
            context.applicationContext.bindService(
                Intent("com.tw.carinfoservice.CarService.Bind").setPackage("com.tw.carinfoservice"),
                conn, Context.BIND_AUTO_CREATE,
            )
        }.getOrDefault(false)
        if (!bound) TwBridge.log("CarInfoLink: Car Info Service not available")
    }
}

/**
 * The vendor's command service (com.tw.service.xt CommandService), open to all apps.
 * MMI uses its "twdata" relay for amplifier settings it cannot send itself (balance,
 * fader, subwoofer, loudness), see the protocol notes, sections 2.5 and 23.5.
 */
object VendorCommand {
    private const val DESCRIPTOR = "com.tw.service.xt.aidl.ITWCommandAidl"
    private const val EXTENDED_INTERFACE = 67
    private var service: IBinder? = null
    private var bound = false

    private val conn = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder) { service = binder }
        override fun onServiceDisconnected(name: ComponentName?) { service = null }
    }

    fun start(context: Context) {
        if (bound) return
        bound = runCatching {
            context.applicationContext.bindService(
                Intent("com.tw.service.xt.CommandService.Bind").setClassName("com.tw.service.xt", "com.tw.service.xt.CommandService"),
                conn, Context.BIND_AUTO_CREATE,
            )
        }.getOrDefault(false)
    }

    val available: Boolean get() = service != null

    fun extended(bundle: Bundle): Boolean {
        val b = service ?: return false
        val p = Parcel.obtain()
        val r = Parcel.obtain()
        return try {
            p.writeInterfaceToken(DESCRIPTOR)
            p.writeInt(1)
            bundle.writeToParcel(p, 0)
            b.transact(EXTENDED_INTERFACE, p, r, 0)
            r.readException()
            true
        } catch (e: Exception) {
            false
        } finally {
            p.recycle(); r.recycle()
        }
    }

    /** write(what, type, value) on the MCU; the value travels as the length of a zero array. */
    fun twdata(what: Int, type: Int, value: Int): Boolean = extended(Bundle().apply {
        putString("project", "twdata")
        putInt("what", what)
        putByte("type", type.toByte())
        putByteArray("data", ByteArray(value.coerceIn(0, 255)))
    })
}

/**
 * Balance, fader, subwoofer and loudness on the unit's amplifier (MCU message 257).
 * Index pairs depend on the sound chip: Rohm uses 42 / 43, every other chip 6 / 7
 * (range 0..18, 9 = centre). MMI keeps its own copy of the values, because the
 * amplifier cannot be read back without the car library.
 */
object Amplifier {
    private const val AUDIO = 257
    private fun prefs(c: Context) = c.getSharedPreferences("mmi_amp", Context.MODE_PRIVATE)

    /** "rohm", "other", or "auto" (until known: Rohm on TS10S firmware, else other). */
    fun chip(c: Context): String = prefs(c).getString("chip", null) ?: run {
        val v = runCatching {
            @Suppress("PrivateApi")
            Class.forName("android.os.SystemProperties").getMethod("get", String::class.java).invoke(null, "ro.systemtw.version") as String
        }.getOrDefault("")
        if (v.startsWith("TS10S") || v.contains("V13")) "rohm" else "other"
    }

    fun setChip(c: Context, chip: String) = prefs(c).edit().putString("chip", chip).apply()

    fun balance(c: Context) = prefs(c).getInt("balance", 9)
    fun fader(c: Context) = prefs(c).getInt("fader", 9)

    private fun send(index: Int, value: Int): Boolean =
        CarCore.write(AUDIO, index, value) || VendorCommand.twdata(AUDIO, index, value)

    /** x: 0 left … 18 right; y: 0 front … 18 rear. */
    fun setBalanceFader(c: Context, x: Int, y: Int): Boolean {
        val bx = x.coerceIn(0, 18)
        val fy = y.coerceIn(0, 18)
        prefs(c).edit().putInt("balance", bx).putInt("fader", fy).apply()
        val rohm = chip(c) == "rohm"
        val a = send(if (rohm) 43 else 7, bx)
        val b = send(if (rohm) 42 else 6, fy)
        return a && b
    }
}

/**
 * The unit's CarPlay / Android Auto engine (ZLink / Tlink5), driven the way the vendor
 * apps drive it (protocol notes, section 29): started at power-on, opened straight on
 * CarPlay or Android Auto, given the call and voice keys, and listened to for its
 * connection state. Its own home is never shown. It always runs full screen (its screens
 * cannot be resized), so Home brings MMI back while it stays connected.
 */
object PhoneLink {
    const val PKG = "com.zjinnova.zlink"
    private const val LAUNCHER = "com.zjinnova.android.zlink.features.launcher"

    data class Status(val connected: Boolean = false, val mode: String? = null, val inCall: Boolean = false)

    private val _status = MutableStateFlow(Status())
    val status: StateFlow<Status> = _status
    private var app: Context? = null

    fun installed(c: Context) = runCatching { c.packageManager.getPackageInfo(PKG, 0); true }.getOrDefault(false)

    /** Starts the background engine so a phone can connect before anything is opened. */
    fun startEngine(c: Context) {
        if (!installed(c)) return
        app = c.applicationContext
        runCatching {
            c.sendBroadcast(Intent("zjinnova.intent.action.START_ZLINK_SERVICE").setPackage(PKG).addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES))
        }
        runCatching {
            c.startForegroundService(Intent().setComponent(ComponentName(PKG, "com.zjinnova.android.zlink.features.service.DaemonService")))
        }
        runCatching { c.sendBroadcast(Intent("com.zjinnova.zlink.action.POWER_ON")) }
        listen(c.applicationContext)
    }

    // ZLink reports its state on its own action with an extra "status".
    private var listening = false
    private fun listen(c: Context) {
        if (listening) return
        listening = true
        val receiver = object : android.content.BroadcastReceiver() {
            override fun onReceive(ctx: Context, i: Intent) {
                val st = i.getStringExtra("status") ?: return
                val mode = i.getStringExtra("phoneMode")
                val before = _status.value
                _status.value = when (st) {
                    "CONNECTED" -> before.copy(connected = true, mode = mode ?: before.mode)
                    "DISCONNECT" -> Status()
                    "PHONE_CALL_ON" -> before.copy(inCall = true)
                    "PHONE_CALL_OFF" -> before.copy(inCall = false)
                    else -> before.copy(mode = mode ?: before.mode)
                }
                TwBridge.log("ZLINK status=$st mode=$mode")
                if (st == "CONNECTED" && !before.connected && autoOpen(ctx)) open(ctx)
            }
        }
        runCatching {
            androidx.core.content.ContextCompat.registerReceiver(
                c, receiver, android.content.IntentFilter().apply { addAction(PKG); addAction("com.suding.speedplay") },
                androidx.core.content.ContextCompat.RECEIVER_EXPORTED,
            )
        }
    }

    /** Settings › General: open CarPlay / Android Auto by itself when the phone connects. */
    fun autoOpen(c: Context) = c.getSharedPreferences("mmi_prefs", Context.MODE_PRIVATE).getBoolean("phonelink_auto", true)
    fun setAutoOpen(c: Context, on: Boolean) = c.getSharedPreferences("mmi_prefs", Context.MODE_PRIVATE).edit().putBoolean("phonelink_auto", on).apply()

    private fun enabled(c: Context, cls: String): Boolean {
        val cn = ComponentName(PKG, "$LAUNCHER.$cls")
        return runCatching {
            when (c.packageManager.getComponentEnabledSetting(cn)) {
                android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_ENABLED -> true
                android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DEFAULT -> c.packageManager.getActivityInfo(cn, 0).enabled
                else -> false
            }
        }.getOrDefault(false)
    }

    /** Opens CarPlay or Android Auto directly (by the connected phone), else ZLink's start. */
    fun open(c: Context): Boolean {
        val mode = _status.value.mode.orEmpty()
        val order = when {
            mode.startsWith("carplay") || mode.startsWith("airplay") -> listOf("CarPlayActivity", "CarPlayAutoActivity")
            mode.startsWith("auto") -> listOf("AutoActivity", "CarPlayAutoActivity")
            else -> listOf("CarPlayAutoActivity", "CarPlayActivity", "AutoActivity")
        }
        if (_status.value.connected) {
            for (cls in order) {
                if (!enabled(c, cls)) continue
                val ok = runCatching {
                    c.startActivity(Intent().setComponent(ComponentName(PKG, "$LAUNCHER.$cls")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    true
                }.getOrDefault(false)
                if (ok) return true
            }
        }
        val i = c.packageManager.getLaunchIntentForPackage(PKG) ?: return false
        return runCatching { c.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); true }.getOrDefault(false)
    }

    /** A function for the phone: 5 answer, 6 hang up / reject, 1500 voice assistant. */
    fun function(c: Context, code: Int) {
        val launch = c.packageManager.getLaunchIntentForPackage(PKG)?.component ?: return
        val i = Intent(PKG).setPackage(PKG)
            .putExtra("command", "REQ_SPEC_FUNC_CMD")
            .putExtra("specFuncCode", code)
        if (launch.className == "com.suding.speedplay.ui.MainActivity") {
            i.component = ComponentName(PKG, "com.texustek.speedplay.broadcast.VendorBroadcastReceiver")
        }
        runCatching { c.sendBroadcast(i) }
    }
    const val ANSWER = 5
    const val HANG_UP = 6
    const val VOICE = 1500
}
