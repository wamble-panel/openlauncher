package com.openlauncher.app.ui.components

import android.content.Context
import android.media.AudioManager
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlin.math.abs

/**
 * Wraps content with a right-edge vertical-drag listener that adjusts STREAM_MUSIC
 * and shows an animated level overlay. Drags that start outside the edge zone are
 * ignored so children keep normal touch behavior.
 */
@Composable
fun VolumeGestureBox(
    accent: Color,
    panel: Color,
    ink: Color,
    edgeDp: Int = 60,
    stepDp: Int = 18,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    val context = LocalContext.current
    val audio = remember(context) {
        context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    }
    val density = LocalDensity.current
    val edgePx = with(density) { edgeDp.dp.toPx() }
    val stepPx = with(density) { stepDp.dp.toPx() }

    var level by remember { mutableStateOf(audio.readLevel()) }
    var visible by remember { mutableStateOf(false) }
    val alpha by animateFloatAsState(
        targetValue = if (visible) 1f else 0f,
        animationSpec = tween(180),
        label = "volume_alpha",
    )

    LaunchedEffect(visible) {
        if (visible) {
            delay(900)
            visible = false
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                var tracking = false
                var startX = 0f
                var lastY = 0f
                detectVerticalDragGestures(
                    onDragStart = { offset ->
                        startX = offset.x
                        lastY = offset.y
                        tracking = startX >= size.width - edgePx
                    },
                    onDragEnd = { tracking = false },
                    onDragCancel = { tracking = false },
                ) { change, dragAmount ->
                    if (!tracking) return@detectVerticalDragGestures
                    val dy = change.position.y - lastY
                    if (abs(dy) >= stepPx) {
                        val direction = if (dy < 0) AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER
                        audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, direction, 0)
                        lastY = change.position.y
                        level = audio.readLevel()
                        visible = true
                        change.consume()
                    }
                }
            },
    ) {
        content()

        if (alpha > 0f) {
            Row(
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .padding(end = 12.dp)
                    .alpha(alpha),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .size(width = 72.dp, height = 240.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(panel),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        com.openlauncher.app.ui.screen.MmiLineIcon(
                            if (level.current == 0) com.openlauncher.app.ui.screen.MmiLine.MUTE else com.openlauncher.app.ui.screen.MmiLine.SPEAKER,
                            ink,
                            Modifier.size(22.dp),
                        )
                        Spacer(Modifier.height(10.dp))
                        Box(
                            modifier = Modifier
                                .size(width = 6.dp, height = 160.dp)
                                .clip(RoundedCornerShape(999.dp))
                                .background(ink.copy(alpha = 0.22f)),
                            contentAlignment = Alignment.BottomCenter,
                        ) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .fillMaxHeight(level.fraction)
                                    .clip(RoundedCornerShape(999.dp))
                                    .background(accent),
                            )
                        }
                        Spacer(Modifier.height(10.dp))
                        Text(
                            text = "${(level.fraction * 100).toInt()}",
                            color = ink,
                            fontSize = 14.sp,
                        )
                    }
                }
            }
        }
    }
}

private data class VolumeLevel(val current: Int, val max: Int) {
    val fraction: Float get() = if (max <= 0) 0f else current.toFloat() / max
}

private fun AudioManager.readLevel(): VolumeLevel {
    val cur = getStreamVolume(AudioManager.STREAM_MUSIC)
    val max = getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
    return VolumeLevel(cur, max)
}
