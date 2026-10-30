package com.openlauncher.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.openlauncher.app.ui.components.VolumeGestureBox
import com.openlauncher.app.ui.screen.MmiCyan
import com.openlauncher.app.ui.screen.MmiHomeScreen
import com.openlauncher.app.ui.screen.MmiInk
import com.openlauncher.app.ui.screen.MmiIntro
import com.openlauncher.app.ui.screen.MmiPageBg
import com.openlauncher.app.util.CarLocation
import com.openlauncher.app.util.IntroVideo
import com.openlauncher.app.viewmodel.CarViewModel
import kotlinx.coroutines.flow.MutableStateFlow

class MainActivity : ComponentActivity() {

    private val vm: CarViewModel by viewModels()

    /** Bumped when the Home button is pressed, so the shell returns to the ring menu. */
    private val homeSignal = MutableStateFlow(0)

    private val permissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        CarLocation.acquire(this, HOLDER)
    }

    private fun hideSystemBars() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowCompat.getInsetsController(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    override fun dispatchKeyEvent(event: android.view.KeyEvent): Boolean {
        com.openlauncher.app.util.CarLinkScan.onKey(event)
        return super.dispatchKeyEvent(event)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.action == Intent.ACTION_MAIN && intent.hasCategory(Intent.CATEGORY_HOME)) homeSignal.value++
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        hideSystemBars()
        requestMissingPermissions()

        setContent {
            val nowPlaying by vm.nowPlaying.collectAsStateWithLifecycle()
            val apps by vm.apps.collectAsStateWithLifecycle()
            val location by vm.location.collectAsStateWithLifecycle()
            val bearing by vm.compassBearing.collectAsStateWithLifecycle()
            val voltage by vm.voltage.collectAsStateWithLifecycle()
            val hardwareRadio by vm.hardwareRadio.collectAsStateWithLifecycle()
            val metric by vm.prefs.metric.collectAsStateWithLifecycle()
            val home by homeSignal.collectAsStateWithLifecycle()
            // Startup video once per boot (cold start of the app), then the MMI shell.
            var intro by remember { mutableStateOf(!introPlayed && IntroVideo.isSet(this)) }
            if (intro) {
                MmiIntro(IntroVideo.file(this)) {
                    introPlayed = true
                    intro = false
                }
                return@setContent
            }
            introPlayed = true

            VolumeGestureBox(
                accent = MmiCyan,
                panel = MmiPageBg,
                ink = MmiInk,
                modifier = Modifier.background(Color.Black),
            ) {
                MmiHomeScreen(
                    metric = metric,
                    onSetMetric = vm.prefs::setMetric,
                    nowPlaying = nowPlaying,
                    onPlayPause = vm::playPause,
                    onNext = vm::skipNext,
                    onPrev = vm::skipPrev,
                    hardwareRadio = hardwareRadio,
                    radioPackage = vm.prefs.radioPackage,
                    onLaunchRadio = vm::launchHardwareRadioApp,
                    onRadioSeekUp = vm::radioSeekUp,
                    onRadioSeekDown = vm::radioSeekDown,
                    onRadioFm = vm::radioCycleFm,
                    onRadioAm = vm::radioSwitchAm,
                    apps = apps,
                    location = location,
                    bearing = bearing,
                    voltage = voltage,
                    onLaunchPackage = vm::launchApp,
                    homeSignal = home,
                )
            }
        }
    }

    private fun requestMissingPermissions() {
        val wanted = com.openlauncher.app.ui.screen.runtimePermissions()
            .filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
        if (wanted.isNotEmpty()) permissions.launch(wanted.toTypedArray())
    }

    override fun onStart() {
        super.onStart()
        CarLocation.acquire(this, HOLDER)
    }

    override fun onResume() {
        super.onResume()
        vm.refreshMedia()
    }

    override fun onDestroy() {
        com.openlauncher.app.ui.screen.PersistentMap.destroy()
        super.onDestroy()
    }

    override fun onStop() {
        super.onStop()
        CarLocation.release(HOLDER)
    }

    private companion object {
        const val HOLDER = "home"
        var introPlayed = false
    }
}
