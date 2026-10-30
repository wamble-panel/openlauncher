package com.openlauncher.app.util

import android.os.Handler
import android.os.Looper
import android.os.Message
import kotlinx.coroutines.delay

/**
 * Probe: can MMI, as a normal app, use the unit's car library (android.tw.john.TWUtil)
 * directly? Voltage, doors, parking sensors, tyre pressure and range only arrive
 * through it (CAN-box frames on message 0x0501 = 1281; radar on 1287/1294). The
 * probe subscribes for a while and logs every frame to mmi-twlog.txt, so the
 * Astra's real frames can be decoded. It only listens; it never writes.
 */
object TwDirect {
    private val IDS = shortArrayOf(1281, 1287, 1294)

    suspend fun probe(seconds: Int = 30, onProgress: (String) -> Unit): String {
        val cls = runCatching { Class.forName("android.tw.john.TWUtil") }.getOrElse { return "Car library not found on this unit" }
        var frames = 0
        return runCatching {
            val tw = cls.getConstructor().newInstance()
            val opened = cls.getMethod("open", ShortArray::class.java).invoke(tw, IDS) as Int
            TwBridge.log("TWUtil probe: open -> $opened")
            if (opened != 0) return "Car library refused (open returned $opened)"
            val handler = object : Handler(Looper.getMainLooper()) {
                override fun handleMessage(m: Message) {
                    frames++
                    val bytes = (m.obj as? ByteArray)?.joinToString(" ") { "%02X".format(it) } ?: m.obj?.toString().orEmpty()
                    TwBridge.log("TW what=${m.what} arg1=${m.arg1} arg2=${m.arg2} obj=$bytes")
                }
            }
            cls.getMethod("start").invoke(tw)
            cls.getMethod("addHandler", String::class.java, Handler::class.java).invoke(tw, "mmi", handler)
            repeat(seconds) { i ->
                onProgress("Listening to the car library… ${seconds - i}s (frames: $frames)")
                delay(1000)
            }
            runCatching { cls.getMethod("removeHandler", String::class.java).invoke(tw, "mmi") }
            runCatching { cls.getMethod("stop").invoke(tw) }
            runCatching { cls.getMethod("close").invoke(tw) }
            "Car library works for MMI: $frames frames logged. Tap Send to developer."
        }.getOrElse { e ->
            val cause = (e as? java.lang.reflect.InvocationTargetException)?.targetException ?: e
            TwBridge.log("TWUtil probe failed: $cause")
            "Car library blocked for MMI: ${cause.javaClass.simpleName} ${cause.message.orEmpty()}".take(160)
        }
    }
}
