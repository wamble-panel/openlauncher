package com.openlauncher.app

import android.app.Application
import com.openlauncher.app.map.NavigationEngine
import com.openlauncher.app.map.RoadMemory
import com.openlauncher.app.util.BtConnect
import com.openlauncher.app.util.CarAudio
import com.openlauncher.app.util.CrashLog

class CarApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // The built-in AirPlay receiver runs in its own process (":airplay"): none of
        // MMI's start-up work belongs there.
        if (processName().endsWith(":airplay")) return
        CrashLog.install(this)
        // Needed for the very first frame (car picture, background) or to catch
        // car messages from the start.
        RoadMemory.init(this)
        com.openlauncher.app.util.TwBridge.init(this)
        com.openlauncher.app.util.CarProfile.init(this)
        // Which unit this is and what it can do: in the background, from the last check.
        com.openlauncher.app.util.UnitProfile.start(this)
        com.openlauncher.app.util.UiSound.init(this)
        // The rest (text-to-speech, routing data, equalizer, Bluetooth profiles,
        // updates) waits until the home screen is on screen: the main thread is
        // idle by then, so start-up after ignition shows MMI sooner.
        android.os.Looper.myQueue().addIdleHandler {
            NavigationEngine.init(this)
            CarAudio.init(this)
            com.openlauncher.app.util.SpeedVolume.init(this)
            BtConnect.init(this)
            com.openlauncher.app.util.Climate.init(this)
            // MMI's own link to the car library (switches itself off if not allowed).
            com.openlauncher.app.util.CarCore.start()
            com.openlauncher.app.util.CarCore.vendorRadioPresent = vendorEnabled("com.tw.radio")
            // Car Info Service (speed, rpm, lights…), the vendor command relay (amplifier)
            // and the CarPlay / Android Auto engine, so a phone can connect at once.
            com.openlauncher.app.util.CarInfoLink.start(this)
            com.openlauncher.app.util.UsbMusic.init(this)
            com.openlauncher.app.util.VendorCommand.start(this)
            com.openlauncher.app.util.PhoneLink.startEngine(this)
            // MMI AirPlay (iPhone screen and music over Wi-Fi), when installed.
            com.openlauncher.app.util.AirPlayLink.start(this)
            // New MMI: start its screens in the background so they open instantly.
            runCatching { FlutterEngines.warm(this) }
            // Only restarts MMI for an update while the car stands still.
            com.openlauncher.app.util.AppUpdater.startBackground(this) {
                (com.openlauncher.app.util.CarLocation.get(this).location.value?.speedMps ?: 0f) < 0.5f
            }
            false
        }
    }

    private fun processName(): String =
        if (android.os.Build.VERSION.SDK_INT >= 28) Application.getProcessName()
        else runCatching { java.io.File("/proc/self/cmdline").readText().trimEnd('\u0000') }.getOrDefault("")

    /** Whether a vendor app is still installed and switched on (MMI takes over its job when not). */
    fun vendorEnabled(pkg: String): Boolean =
        runCatching { packageManager.getApplicationInfo(pkg, 0).enabled }.getOrDefault(false)
}
