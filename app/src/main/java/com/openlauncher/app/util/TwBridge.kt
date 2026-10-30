package com.openlauncher.app.util

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Listens to the TS10 car apps (com.tw.*): they announce Bluetooth music, calls
 * and the radio to the vendor launcher with these broadcasts. MMI stands in for
 * that launcher. Every message is also logged to reports/mmi-twlog.txt (offered
 * by the MMI file picker), so the exact contents can be read back.
 */
object TwBridge {
    enum class CallState { NONE, INCOMING, OUTGOING, ACTIVE }

    data class Call(val state: CallState, val number: String?, val name: String?, val since: Long)
    data class BtTrack(val title: String, val artist: String, val playing: Boolean)

    private val ACTIONS = listOf(
        "com.tw.launcher.btmsg", "com.tw.bt_phone_info", "com.tw.radio.state", "com.tw.radio.av", "com.tw.bt.av.play",
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

    private val log = ArrayDeque<String>()
    private lateinit var app: Context

    fun init(context: Context) {
        app = context.applicationContext
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) = handle(intent)
        }
        runCatching {
            ContextCompat.registerReceiver(app, receiver, IntentFilter().apply { ACTIONS.forEach(::addAction) }, ContextCompat.RECEIVER_EXPORTED)
        }
    }

    /** Asks the vendor Bluetooth app to answer or end the call (its voice-assistant commands). */
    fun answer() = send("action.intent.AIOS_ACCEPT")
    fun hangUp() = send("action.intent.AIOS_REJECT")

    private fun send(action: String) {
        runCatching { app.sendBroadcast(Intent(action).setPackage("com.tw.bt")) }
        runCatching { app.sendBroadcast(Intent(action)) }
    }

    private fun describe(extras: Bundle?): String = runCatching {
        extras?.keySet()?.joinToString(", ") { k -> "$k=${extras.get(k)}".take(200) } ?: ""
    }.getOrDefault("?")

    /** First extra whose key contains any of [hints], as text. */
    private fun pick(extras: Bundle?, vararg hints: String): String? = runCatching {
        extras?.keySet()?.firstOrNull { k -> hints.any { k.contains(it, ignoreCase = true) } }
            ?.let { extras.get(it)?.toString() }?.trim()?.takeIf { it.isNotEmpty() && it != "null" }
    }.getOrNull()

    private fun handle(intent: Intent) {
        val action = intent.action ?: return
        val extras = intent.extras
        record("$action  ${describe(extras)}")
        val number = pick(extras, "number", "phone", "num", "tel")
        val name = pick(extras, "name", "contact")
        val now = System.currentTimeMillis()
        when (action) {
            "action.bt.AIOS_INCOMING_RINGING" -> _call.value = Call(CallState.INCOMING, number, name, now)
            "action.bt.AIOS_OUTGOING_RINGING" -> _call.value = Call(CallState.OUTGOING, number, name, now)
            "action.bt.AIOS_INCOMING_OFFHOOK", "action.bt.AIOS_OUTGOING_OFFHOOK" ->
                _call.value = _call.value.let { Call(CallState.ACTIVE, number ?: it.number, name ?: it.name, now) }
            "action.bt.AIOS_INCOMING_IDLE", "action.bt.AIOS_OUTGOING_IDLE" -> _call.value = Call(CallState.NONE, null, null, 0L)
            "com.android.bt.connected" -> _phoneConnected.value = true
            "com.android.bt.disconnected" -> { _phoneConnected.value = false; _btTrack.value = null }
            "com.tw.launcher.btmsg", "com.tw.bt.av.play" -> {
                val title = pick(extras, "title", "song", "music")
                if (title != null) {
                    val playing = pick(extras, "play", "state", "status")?.let { it == "true" || it == "1" || it.contains("play", true) } ?: true
                    _btTrack.value = BtTrack(title, pick(extras, "artist", "singer") ?: "", playing)
                }
            }
        }
    }

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
