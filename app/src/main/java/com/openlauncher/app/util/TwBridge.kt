package com.openlauncher.app.util

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Talks to the TS10 car apps (com.tw.*) the way their own launcher and voice
 * assistant do, with plain broadcasts any app may send and receive:
 * - Bluetooth audio only reaches the speakers while the car's source is
 *   Bluetooth; [acquireBluetoothAudio] asks com.tw.service / com.tw.bt for it.
 * - Bluetooth song info: GET_ID3_INFO once, then SEND_ID3_INFO on every change.
 * - Bluetooth music control: com.tw.launcher.btmsg (msg codes below).
 * - Calls: SEND_CALL_INFO (state, callName, callNumber), AIOS_ACCEPT/REJECT/DIAL.
 * Every message is also logged to reports/mmi-twlog.txt.
 */
object TwBridge {
    enum class CallState { NONE, INCOMING, OUTGOING, ACTIVE }

    data class Call(val state: CallState, val number: String?, val name: String?, val since: Long)
    data class BtTrack(val title: String, val artist: String, val playing: Boolean, val positionMs: Int = 0, val durationMs: Int = 0)

    // com.tw.launcher.btmsg "msg" codes (com.tw.bt).
    private const val BT_TOGGLE = 1
    private const val BT_NEXT = 3
    private const val BT_PREV = 4
    private const val BT_PLAY = 5 // plays only if paused
    private const val BT_PAUSE = 6 // pauses only if playing

    private const val ID3 = "com.unisound.intent.action.SEND_ID3_INFO"
    private const val CALL = "com.unisound.intent.action.SEND_CALL_INFO"
    // Sent by com.tw.service whenever reverse gear or a call changes; extras "reverse" and "call".
    private const val MUTE = "com.unisound.intent.action.DO_MUTE"
    private const val UNMUTE = "com.unisound.intent.action.DO_UNMUTE"

    private val ACTIONS = listOf(
        ID3, CALL, MUTE, UNMUTE, "com.tw.bt_phone_info", "com.tw.radio.state", "com.tw.radio.av", "com.tw.bt.av.play",
        "com.android.bt.connected", "com.android.bt.disconnected", "com.tw.launcher.theme", "com.tw.launcher.dvrinfo",
        "action.bt.AIOS_INCOMING_RINGING", "action.bt.AIOS_INCOMING_OFFHOOK", "action.bt.AIOS_INCOMING_IDLE",
        "action.bt.AIOS_OUTGOING_RINGING", "action.bt.AIOS_OUTGOING_OFFHOOK", "action.bt.AIOS_OUTGOING_IDLE",
        "action.hicar.onconnect", "action.hicar.ondisconnect",
    )

    private val _call = MutableStateFlow(Call(CallState.NONE, null, null, 0L))
    val call: StateFlow<Call> = _call
    private val _btTrack = MutableStateFlow<BtTrack?>(null)
    val btTrack: StateFlow<BtTrack?> = _btTrack
    private val _phoneConnected = MutableStateFlow<Boolean?>(null)
    val phoneConnected: StateFlow<Boolean?> = _phoneConnected
    private val _reversing = MutableStateFlow(false)
    /** True while reverse gear is engaged (the car service's own signal). */
    val reversing: StateFlow<Boolean> = _reversing
    private var playingBeforeReverse = false

    private val log = ArrayDeque<String>()
    private lateinit var app: Context
    private val main = Handler(Looper.getMainLooper())
    /** The song counts as playing while its position keeps moving (no play-state broadcast exists). */
    private var lastProgressAt = 0L
    private val stalled = Runnable { _btTrack.value?.let { if (it.playing) _btTrack.value = it.copy(playing = false) } }

    /** Whether this unit runs the TS10 car apps at all. */
    val available: Boolean get() = runCatching { app.packageManager.getPackageInfo("com.tw.bt", 0); true }.getOrDefault(false)

    fun init(context: Context) {
        app = context.applicationContext
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) = handle(intent)
        }
        runCatching {
            ContextCompat.registerReceiver(app, receiver, IntentFilter().apply { ACTIONS.forEach(::addAction) }, ContextCompat.RECEIVER_EXPORTED)
        }
        // Current state: is a phone connected, what's playing, is a call on.
        send(Intent("action.adapter.status.request"))
        send(Intent("com.unisound.intent.action.GET_ID3_INFO"))
        send(Intent("com.unisound.intent.action.GET_CALL_INFO"))
        send(Intent("com.unisound.intent.action.GET_MUTE"))
        if (available) radioLast().let { (am, f) -> publishRadio(am, f) }
        applyCallWindow()
    }

    // ── Vendor call bubble: com.tw.bt hides its call windows while it believes
    // HiCar is connected, so MMI sets that flag and shows its own call bar.
    // Side effect: the steering wheel's answer/reject keys go to the HiCar app then.
    fun mmiCallBar(): Boolean = prefs().getBoolean("mmi_call_bar", true)
    fun setMmiCallBar(on: Boolean) {
        prefs().edit().putBoolean("mmi_call_bar", on).apply()
        applyCallWindow()
    }

    private fun applyCallWindow() {
        if (!available) return
        send(Intent(if (mmiCallBar()) "action.hicar.onconnect" else "action.hicar.ondisconnect"))
    }

    private fun prefs() = app.getSharedPreferences("mmi_car", Context.MODE_PRIVATE)

    /** Keep music playing (unmuted) while reversing instead of the car's automatic mute. */
    fun keepMusicInReverse(): Boolean = prefs().getBoolean("keep_music_reverse", false)
    fun setKeepMusicInReverse(on: Boolean) = prefs().edit().putBoolean("keep_music_reverse", on).apply()

    private fun musicPlaying(): Boolean =
        com.openlauncher.app.service.MediaListenerService.nowPlaying.value?.isPlaying == true || _btTrack.value?.playing == true

    private fun onReverse(on: Boolean) {
        if (on == _reversing.value) return
        _reversing.value = on
        if (on) {
            playingBeforeReverse = musicPlaying()
            // Optional: undo the car's reverse mute so music carries on.
            if (keepMusicInReverse()) main.postDelayed({ send(Intent("com.unisound.unicar.action.unmute")) }, 300)
        } else if (playingBeforeReverse) {
            playingBeforeReverse = false
            // Out of reverse: carry on with whatever was playing.
            main.postDelayed({
                if (!musicPlaying()) {
                    val controller = com.openlauncher.app.service.MediaListenerService.nowPlaying.value?.controller
                    // Play-if-paused only: re-requesting the Bluetooth source would toggle play/pause again.
                    if (controller != null) controller.transportControls.play() else btMusic(BT_PLAY)
                }
            }, 1200)
        }
    }

    /**
     * Makes the car play Bluetooth audio (its source switches to Bluetooth). The
     * vendor launcher does this when its Bluetooth music page opens; without it
     * the phone's music never reaches the speakers. The switch itself toggles
     * play/pause, so a "play if paused" follows once it has settled.
     */
    fun acquireBluetoothAudio(play: Boolean = true) {
        send(Intent("net.easyconn.a2dp.acquire").putExtra("bluetoothName", "MMI"))
        if (play) main.postDelayed({ btMusic(BT_PLAY) }, 1500)
    }

    fun btPlay() { acquireBluetoothAudio(play = true) }
    fun btPause() = btMusic(BT_PAUSE)
    fun btToggle() = if (_btTrack.value?.playing == true) btPause() else btPlay()
    fun btNext() = btMusic(BT_NEXT)
    fun btPrev() = btMusic(BT_PREV)

    private fun btMusic(code: Int) = send(Intent("com.tw.launcher.btmsg").putExtra("msg", code))

    // ── Radio: com.tw.service tunes on com.unisound.unicar.broadcast.action (it also
    // switches the car's source to radio); next/prev act on the current source.
    // There is no broadcast of the tuned frequency, so MMI shows what it last tuned.
    private val _radio = MutableStateFlow<com.openlauncher.app.model.HardwareRadioState?>(null)
    val radio: StateFlow<com.openlauncher.app.model.HardwareRadioState?> = _radio

    private fun radioPrefs() = app.getSharedPreferences("mmi_radio", Context.MODE_PRIVATE)

    private fun publishRadio(am: Boolean, freq: Float, note: String? = null) {
        _radio.value = com.openlauncher.app.model.HardwareRadioState(
            band = if (am) "AM" else "FM",
            freq = if (am) freq.toInt().toString() else "%.1f".format(Locale.US, freq),
            stationName = note, canTune = true,
        )
    }

    fun radioLast(): Pair<Boolean, Float> {
        val am = radioPrefs().getBoolean("am", false)
        return am to radioPrefs().getFloat(if (am) "am_freq" else "fm_freq", if (am) 1170f else 98.5f)
    }

    /** Tunes [freq] (MHz on FM, kHz on AM) and makes radio the car's source. */
    fun radioTune(am: Boolean, freq: Float) {
        val f = if (am) freq.coerceIn(531f, 1602f) else freq.coerceIn(87.5f, 108f)
        radioPrefs().edit().putBoolean("am", am).putFloat(if (am) "am_freq" else "fm_freq", f).apply()
        publishRadio(am, f)
        send(
            Intent("com.unisound.unicar.broadcast.action")
                .putExtra("type", if (am) "am" else "fm")
                .putExtra("freq", if (am) f.toInt().toString() else "%.1f".format(Locale.US, f)),
        )
    }

    fun radioResume() = radioLast().let { (am, f) -> radioTune(am, f) }
    fun radioBand(am: Boolean) = radioTune(am, radioPrefs().getFloat(if (am) "am_freq" else "fm_freq", if (am) 1170f else 98.5f))

    /** Seek: the car finds the next station itself (the new frequency shows on the car's display). */
    fun radioSeek(up: Boolean) {
        send(Intent("com.unisound.unicar.broadcast.action").putExtra("type", if (up) "next" else "prev"))
        radioLast().let { (am, f) -> publishRadio(am, f, if (up) "Seeking up…" else "Seeking down…") }
    }

    fun radioPresets(): List<Float> = radioPrefs().getString("presets", "")!!.split(',').mapNotNull { it.toFloatOrNull() }
    fun radioSavePreset(slot: Int, freq: Float) {
        val list = radioPresets().toMutableList()
        while (list.size <= slot) list += 0f
        list[slot] = freq
        radioPrefs().edit().putString("presets", list.joinToString(",")).apply()
    }

    /** Asks the vendor Bluetooth app to answer, end or place a call. */
    fun answer() = send(Intent("action.intent.AIOS_ACCEPT"))
    fun hangUp() = send(Intent("action.intent.AIOS_REJECT"))
    fun dial(number: String) = send(Intent("action.intent.AIOS_DIAL").putExtra("number", number))

    private fun send(intent: Intent) {
        runCatching { app.sendBroadcast(intent) }
    }

    private fun describe(extras: Bundle?): String = runCatching {
        extras?.keySet()?.joinToString(", ") { k -> "$k=${extras.get(k)}".take(200) } ?: ""
    }.getOrDefault("?")

    private fun text(extras: Bundle?, key: String): String? =
        extras?.get(key)?.toString()?.trim()?.takeIf { it.isNotEmpty() && it != "null" }

    private fun handle(intent: Intent) {
        val action = intent.action ?: return
        val extras = intent.extras
        // Position updates arrive every second while playing: logged only on a song change.
        if (action != ID3 || text(extras, "musicTitle") != _btTrack.value?.title) record("$action  ${describe(extras)}")
        val now = System.currentTimeMillis()
        when (action) {
            ID3 -> {
                val title = text(extras, "musicTitle") ?: return
                val position = extras?.getInt("musicProgress", 0) ?: 0
                val previous = _btTrack.value
                val moving = previous != null && previous.title == title && position != previous.positionMs
                if (moving) lastProgressAt = now
                _btTrack.value = BtTrack(
                    title, text(extras, "musicArtist") ?: "",
                    playing = moving || (previous?.playing == true && now - lastProgressAt < 3000),
                    positionMs = position, durationMs = extras?.getInt("musicDuration", 0) ?: 0,
                )
                main.removeCallbacks(stalled)
                main.postDelayed(stalled, 3000)
            }
            CALL -> {
                val number = text(extras, "callNumber")
                val name = text(extras, "callName")
                _call.value = when (extras?.getInt("state", 0) ?: 0) {
                    1 -> Call(CallState.INCOMING, number, name, now)
                    2 -> _call.value.let { Call(CallState.ACTIVE, number ?: it.number, name ?: it.name, if (it.state == CallState.ACTIVE) it.since else now) }
                    3 -> Call(CallState.OUTGOING, number, name, now)
                    else -> Call(CallState.NONE, null, null, 0L)
                }
            }
            "action.bt.AIOS_INCOMING_RINGING" -> _call.value = Call(CallState.INCOMING, text(extras, "number"), text(extras, "name"), now)
            "action.bt.AIOS_OUTGOING_RINGING" -> if (_call.value.state == CallState.NONE) _call.value = Call(CallState.OUTGOING, null, null, now)
            "action.bt.AIOS_INCOMING_OFFHOOK", "action.bt.AIOS_OUTGOING_OFFHOOK" ->
                _call.value = _call.value.let { if (it.state == CallState.ACTIVE) it else it.copy(state = CallState.ACTIVE, since = now) }
            "action.bt.AIOS_INCOMING_IDLE", "action.bt.AIOS_OUTGOING_IDLE" -> _call.value = Call(CallState.NONE, null, null, 0L)
            "com.android.bt.connected" -> {
                val was = _phoneConnected.value
                _phoneConnected.value = true
                // Phone just connected: route its audio to the car, as the vendor launcher does.
                if (was != true) acquireBluetoothAudio(play = false)
                applyCallWindow()
                send(Intent("com.unisound.intent.action.GET_ID3_INFO"))
            }
            "com.android.bt.disconnected" -> { _phoneConnected.value = false; _btTrack.value = null }
            MUTE, UNMUTE -> extras?.let { if (it.containsKey("reverse")) onReverse(it.getInt("reverse", 0) != 0) }
        }
    }

    /** Adds a line to mmi-twlog.txt (used by the car-library probe too). */
    fun log(line: String) = record(line)

    private fun record(line: String) {
        val stamp = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
        synchronized(log) {
            log.addLast("$stamp  $line")
            while (log.size > 300) log.removeFirst()
            runCatching {
                File(File(app.filesDir, "reports").apply { mkdirs() }, "mmi-twlog.txt").writeText(log.joinToString("\n"))
            }
        }
    }

    fun recent(): List<String> = synchronized(log) { log.toList() }
}
