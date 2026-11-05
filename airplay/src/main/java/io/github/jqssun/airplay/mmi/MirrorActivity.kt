package io.github.jqssun.airplay.mmi

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.ServiceConnection
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.view.Gravity
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import io.github.jqssun.airplay.MmiLink
import io.github.jqssun.airplay.service.AirPlayService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * MMI's screen for the iPhone: full screen, black, the picture fitted to the panel.
 * While the car moves the picture is covered (sound keeps playing). Tap: a small bar
 * to go back to MMI. Closes by itself when the iPhone stops.
 */
class MirrorActivity : Activity() {
    private var service: AirPlayService? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var watch: Job? = null
    private lateinit var surfaceView: SurfaceView
    private lateinit var cover: TextView
    private lateinit var bar: TextView
    private var surface: Surface? = null
    private var aspect = 16f / 9f
    private var driving = false
    private var everShown = false

    private val conn = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            val s = (binder as? AirPlayService.LocalBinder)?.service ?: return
            service = s
            surface?.let { attach(it) }
            watch = scope.launch {
                combine(s.mirroringActive, s.videoPlaybackActive, s.videoAspect, s.videoPlaybackAspect) { m, v, a, va ->
                    Triple(m, v, if (v) va else a)
                }.collect { (m, v, a) ->
                    if (m || v) everShown = true
                    aspect = a
                    layoutVideo()
                    // The iPhone stopped: back to MMI.
                    if (everShown && !m && !v) finish()
                }
            }
        }

        override fun onServiceDisconnected(name: ComponentName) {
            service = null
        }
    }

    private val drivingReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            driving = i.getBooleanExtra("driving", false)
            cover.visibility = if (driving) View.VISIBLE else View.GONE
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        surfaceView = SurfaceView(this)
        surfaceView.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(h: SurfaceHolder) {
                surface = h.surface
                attach(h.surface)
            }
            override fun surfaceChanged(h: SurfaceHolder, f: Int, w: Int, hgt: Int) = Unit
            override fun surfaceDestroyed(h: SurfaceHolder) {
                service?.clearVideoSurface(h.surface)
                service?.clearVideoPlaybackSurface(h.surface)
                surface = null
            }
        })
        root.addView(surfaceView, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT, Gravity.CENTER))
        cover = TextView(this).apply {
            setBackgroundColor(Color.BLACK)
            setTextColor(Color.WHITE)
            textSize = 26f
            gravity = Gravity.CENTER
            text = "The iPhone's screen is hidden while driving.\nSound keeps playing."
            visibility = View.GONE
        }
        root.addView(cover, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        bar = TextView(this).apply {
            setBackgroundColor(0xCC000000.toInt())
            setTextColor(Color.WHITE)
            textSize = 22f
            gravity = Gravity.CENTER
            setPadding(40, 24, 40, 24)
            text = "←  Back to MMI"
            visibility = View.GONE
            setOnClickListener { goHome() }
        }
        root.addView(bar, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.START).apply { setMargins(24, 24, 0, 0) })
        root.setOnClickListener {
            bar.visibility = if (bar.visibility == View.VISIBLE) View.GONE else View.VISIBLE
            bar.removeCallbacks(hideBar)
            if (bar.visibility == View.VISIBLE) bar.postDelayed(hideBar, 4000)
        }
        root.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> layoutVideo() }
        setContentView(root)
        hideBars()
        bindService(Intent(this, AirPlayService::class.java), conn, Context.BIND_AUTO_CREATE)
        val filter = IntentFilter(MmiLink.ACTION_DRIVING)
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(drivingReceiver, filter, MmiLink.PERMISSION, null, Context.RECEIVER_NOT_EXPORTED)
        else registerReceiver(drivingReceiver, filter, MmiLink.PERMISSION, null)
        // Ask MMI's main process for the car's state now (it also sends every change).
        sendBroadcast(Intent("com.openlauncher.airplay.ASK_DRIVING").setPackage(packageName), MmiLink.PERMISSION)
    }

    private val hideBar = Runnable { bar.visibility = View.GONE }

    private fun attach(s: Surface) {
        val svc = service ?: return
        if (svc.videoPlaybackActive.value) svc.setVideoPlaybackSurface(s) else svc.setVideoSurface(s)
    }

    /** The picture as large as the panel allows, keeping its shape. */
    private fun layoutVideo() {
        val root = surfaceView.parent as? View ?: return
        val w = root.width
        val h = root.height
        if (w == 0 || h == 0) return
        val (vw, vh) = if (w.toFloat() / h > aspect) (h * aspect).toInt() to h else w to (w / aspect).toInt()
        val lp = surfaceView.layoutParams as FrameLayout.LayoutParams
        if (lp.width != vw || lp.height != vh) {
            lp.width = vw
            lp.height = vh
            surfaceView.layoutParams = lp
        }
        surface?.let { attach(it) }
    }

    private fun goHome() {
        startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    private fun hideBars() {
        if (Build.VERSION.SDK_INT >= 30) {
            window.insetsController?.let {
                it.hide(WindowInsets.Type.systemBars())
                it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideBars()
    }

    override fun onDestroy() {
        watch?.cancel()
        scope.cancel()
        runCatching { unregisterReceiver(drivingReceiver) }
        runCatching { unbindService(conn) }
        super.onDestroy()
    }
}
