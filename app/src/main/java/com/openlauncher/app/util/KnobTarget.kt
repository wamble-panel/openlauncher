package com.openlauncher.app.util

import android.view.KeyEvent
import android.view.MotionEvent

/**
 * The car's turn knob on MMI pages. Knobs reach Android in different ways
 * (arrow keys, rotary scroll, channel keys), so the common ones all step the
 * radio while the Radio page is open; every key is also logged once per code
 * to mmi-twlog.txt so the exact knob codes show up in the next upload.
 */
object KnobTarget {
    @Volatile var radio = false
    private val logged = HashSet<Int>()

    // Knob events for the Apps page (the dash handler emits, the Apps page collects).
    val appsStep = kotlinx.coroutines.flow.MutableSharedFlow<Int>(extraBufferCapacity = 8)
    val appsOpen = kotlinx.coroutines.flow.MutableSharedFlow<Unit>(extraBufferCapacity = 4)

    private val UP = setOf(
        KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_CHANNEL_UP, KeyEvent.KEYCODE_PAGE_UP,
        KeyEvent.KEYCODE_NAVIGATE_NEXT, KeyEvent.KEYCODE_MEDIA_FAST_FORWARD, KeyEvent.KEYCODE_TAB,
    )
    private val DOWN = setOf(
        KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_CHANNEL_DOWN, KeyEvent.KEYCODE_PAGE_DOWN,
        KeyEvent.KEYCODE_NAVIGATE_PREVIOUS, KeyEvent.KEYCODE_MEDIA_REWIND,
    )

    /**
     * Knob on ordinary pages: moves the highlight through the page's buttons in
     * reading order (as Tab does) and presses the highlighted one. Real key events,
     * so the page switches to knob mode and shows the highlight; a touch hides it again.
     */
    fun focusStep(activity: android.app.Activity?, forward: Boolean) {
        val meta = if (forward) 0 else KeyEvent.META_SHIFT_ON
        send(activity, KeyEvent.KEYCODE_TAB, meta)
    }

    fun focusPress(activity: android.app.Activity?) = send(activity, KeyEvent.KEYCODE_ENTER, 0)

    private fun send(activity: android.app.Activity?, code: Int, meta: Int) {
        val view = activity?.window?.decorView ?: return
        val t = android.os.SystemClock.uptimeMillis()
        view.dispatchKeyEvent(KeyEvent(t, t, KeyEvent.ACTION_DOWN, code, 0, meta))
        view.dispatchKeyEvent(KeyEvent(t, t, KeyEvent.ACTION_UP, code, 0, meta))
    }

    /** Returns true when the key was used. */
    fun onKey(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN && logged.add(event.keyCode)) {
            TwBridge.log("KEY ${KeyEvent.keyCodeToString(event.keyCode)} scan=${event.scanCode} source=${event.source} device=${event.device?.name}")
        }
        if (!radio) return false
        val up = when (event.keyCode) {
            in UP -> !(event.keyCode == KeyEvent.KEYCODE_TAB && event.isShiftPressed)
            in DOWN -> false
            else -> return false
        }
        if (event.action == KeyEvent.ACTION_DOWN) TwBridge.radioStep(up)
        return true
    }

    /** Rotary encoders arrive as scroll motion. */
    fun onMotion(event: MotionEvent): Boolean {
        if (event.action != MotionEvent.ACTION_SCROLL) return false
        if (logged.add(-1)) TwBridge.log("SCROLL source=${event.source} device=${event.device?.name}")
        if (!radio) return false
        val v = event.getAxisValue(MotionEvent.AXIS_SCROLL).takeIf { it != 0f }
            ?: event.getAxisValue(MotionEvent.AXIS_VSCROLL).takeIf { it != 0f }
            ?: event.getAxisValue(MotionEvent.AXIS_HSCROLL)
        if (v == 0f) return false
        TwBridge.radioStep(v > 0)
        return true
    }

}
