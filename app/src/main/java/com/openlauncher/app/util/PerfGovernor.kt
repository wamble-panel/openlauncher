package com.openlauncher.app.util

import android.view.Choreographer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Keeps MMI just under what the A133 can do: it watches every frame, and when
 * frames start arriving late it turns down the expensive extras (object
 * detection rate, map frame cap, decorative animation) before a stutter shows;
 * when things are calm again it turns them back up. Touch, page changes, music
 * and navigation always keep priority.
 *
 * level 0 = everything on, 1 = extras reduced, 2 = extras minimal.
 */
object PerfGovernor {
    private val _level = MutableStateFlow(0)
    val level: StateFlow<Int> = _level

    private const val WINDOW_NS = 2_000_000_000L
    private const val LATE_NS = 25_000_000L // a frame later than ~1.5 vsyncs at 60 Hz counts as late

    private var started = false
    private var last = 0L
    private var windowStart = 0L
    private var frames = 0
    private var late = 0
    private var badWindows = 0
    private var goodWindows = 0

    private val callback = object : Choreographer.FrameCallback {
        override fun doFrame(now: Long) {
            if (last != 0L) {
                frames++
                if (now - last > LATE_NS) late++
            }
            last = now
            if (windowStart == 0L) windowStart = now
            if (now - windowStart >= WINDOW_NS) evaluate(now)
            Choreographer.getInstance().postFrameCallback(this)
        }
    }

    private fun evaluate(now: Long) {
        val ratio = if (frames > 0) late.toFloat() / frames else 0f
        when {
            ratio > 0.15f -> { badWindows++; goodWindows = 0 }
            ratio < 0.03f -> { goodWindows++; badWindows = 0 }
            else -> { badWindows = 0; goodWindows = 0 }
        }
        // Step down quickly (2 bad windows = 4 s), step back up slowly (5 calm windows = 10 s).
        if (badWindows >= 2 && _level.value < 2) { _level.value++; badWindows = 0 }
        if (goodWindows >= 5 && _level.value > 0) { _level.value--; goodWindows = 0 }
        frames = 0; late = 0; windowStart = now
    }

    /** Starts watching frames (main thread). */
    fun start() {
        if (started) return
        started = true
        Choreographer.getInstance().postFrameCallback(callback)
    }

    /** Milliseconds between object-detection runs in the reverse view. */
    val detectionIntervalMs: Long get() = when (_level.value) { 0 -> 250L; 1 -> 500L; else -> 1000L }

    /** Map frame cap. */
    val mapMaxFps: Int get() = if (_level.value >= 2) 24 else 30
}
