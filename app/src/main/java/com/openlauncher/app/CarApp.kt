package com.openlauncher.app

import android.app.Application
import com.openlauncher.app.map.NavigationEngine
import com.openlauncher.app.map.RoadMemory
import com.openlauncher.app.util.BtConnect
import com.openlauncher.app.util.CarAudio
import com.openlauncher.app.util.CrashLog
import com.openlauncher.app.util.ThemePack

class CarApp : Application() {
    override fun onCreate() {
        super.onCreate()
        CrashLog.install(this)
        // Needed for the very first frame (car picture, background) or to catch
        // car messages from the start.
        RoadMemory.init(this)
        ThemePack.init(this)
        com.openlauncher.app.util.TwBridge.init(this)
        // The rest (text-to-speech, routing data, equalizer, Bluetooth profiles,
        // updates) waits until the home screen is on screen: the main thread is
        // idle by then, so start-up after ignition shows MMI sooner.
        android.os.Looper.myQueue().addIdleHandler {
            NavigationEngine.init(this)
            CarAudio.init(this)
            BtConnect.init(this)
            com.openlauncher.app.util.Climate.init(this)
            // Only restarts MMI for an update while the car stands still.
            com.openlauncher.app.util.AppUpdater.startBackground(this) {
                (com.openlauncher.app.util.CarLocation.get(this).location.value?.speedMps ?: 0f) < 0.5f
            }
            false
        }
    }
}
