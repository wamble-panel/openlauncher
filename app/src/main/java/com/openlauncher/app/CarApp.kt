package com.openlauncher.app

import android.app.Application
import com.openlauncher.app.map.NavigationEngine
import com.openlauncher.app.util.BtConnect
import com.openlauncher.app.util.CarAudio
import com.openlauncher.app.util.CrashLog

class CarApp : Application() {
    override fun onCreate() {
        super.onCreate()
        CrashLog.install(this)
        NavigationEngine.init(this)
        CarAudio.init(this)
        BtConnect.init(this)
    }
}
