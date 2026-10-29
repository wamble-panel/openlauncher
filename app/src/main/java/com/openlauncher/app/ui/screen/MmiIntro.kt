package com.openlauncher.app.ui.screen

import android.widget.VideoView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
