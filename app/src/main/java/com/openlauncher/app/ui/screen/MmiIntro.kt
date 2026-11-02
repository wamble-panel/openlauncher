package com.openlauncher.app.ui.screen

import android.widget.VideoView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.offset
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.delay
import java.io.File

/** Plays the startup video full screen on black, then hands over to the MMI shell. Tap to skip. */
@Composable
internal fun MmiIntro(video: File, onDone: () -> Unit) {
    val done by rememberUpdatedState(onDone)
    // Safety net: never keep the driver waiting on a stuck decoder.
    LaunchedEffect(Unit) {
        delay(12_000)
        done()
    }
    Box(Modifier.fillMaxSize().background(Color.Black).pressable { done() }, contentAlignment = Alignment.Center) {
        AndroidView(
            factory = { ctx ->
                VideoView(ctx).apply {
                    setOnCompletionListener { done() }
                    setOnErrorListener { _, _, _ -> done(); true }
                    setVideoPath(video.absolutePath)
                    start()
                }
            },
            onRelease = { it.stopPlayback() },
            modifier = Modifier.fillMaxSize(),
        )
    }
}

/**
 * Start-up screen from the design: your car on black, a loading line filling under it
 * and "MMI" below; then it fades away over the home screen (which is already drawn
 * underneath, so nothing pops in).
 */
@Composable
internal fun MmiSplash(onDone: () -> Unit) {
    val done by rememberUpdatedState(onDone)
    val progress = androidx.compose.runtime.remember { androidx.compose.animation.core.Animatable(0f) }
    val fade = androidx.compose.runtime.remember { androidx.compose.animation.core.Animatable(1f) }
    LaunchedEffect(Unit) {
        progress.animateTo(1f, androidx.compose.animation.core.tween(1200, easing = androidx.compose.animation.core.FastOutSlowInEasing))
        fade.animateTo(0f, androidx.compose.animation.core.tween(450))
        done()
    }
    androidx.compose.foundation.layout.BoxWithConstraints(
        Modifier.fillMaxSize().graphicsLayer { alpha = fade.value }.background(Color.Black),
    ) {
        // Positions as in the 1280 × 800 design, scaled to this screen.
        val s = maxWidth / 1280f
        androidx.compose.foundation.Image(
            androidx.compose.ui.res.painterResource(com.openlauncher.app.R.drawable.boot_car), null,
            modifier = Modifier.align(Alignment.TopCenter).offset(y = s * 150f).size(s * 784f, s * 356f),
        )
        androidx.compose.foundation.Canvas(Modifier.align(Alignment.TopCenter).offset(y = s * 548f).size(s * 516f, s * 5f)) {
            drawRect(Color(0xFF3C3C3C))
            drawRect(Color(0xFFF0F0F0), size = androidx.compose.ui.geometry.Size(size.width * progress.value, size.height))
        }
        androidx.compose.material3.Text(
            "MMI", color = Color(0xFF969696), fontFamily = MmiFont, fontSize = androidx.compose.ui.unit.TextUnit(26f, androidx.compose.ui.unit.TextUnitType.Sp),
            modifier = Modifier.align(Alignment.TopCenter).offset(y = s * 692f),
        )
    }
}
