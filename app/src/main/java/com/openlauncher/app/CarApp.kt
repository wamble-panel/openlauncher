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
        NavigationEngine.init(this)
        CarAudio.init(this)
        BtConnect.init(this)
        RoadMemory.init(this)
        ThemePack.init(this)
        com.openlauncher.app.util.TwBridge.init(this)
        // Only restarts MMI for an update while the car stands still.
        com.openlauncher.app.util.AppUpdater.startBackground(this) {
            (com.openlauncher.app.util.CarLocation.get(this).location.value?.speedMps ?: 0f) < 0.5f
        }
    }
}
