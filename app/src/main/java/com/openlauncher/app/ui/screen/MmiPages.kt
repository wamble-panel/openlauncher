package com.openlauncher.app.ui.screen

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.media.MediaMetadata
import android.net.Uri
import android.provider.ContactsContract
import android.provider.Settings
import android.graphics.BitmapFactory
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.graphics.drawable.toBitmap
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openlauncher.app.model.AppInfo
import com.openlauncher.app.model.NowPlayingState
import com.openlauncher.app.util.LocationData
import com.openlauncher.app.model.HardwareRadioState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

// ── Shared MMI building blocks ───────────────────────────────────────────────

internal val MmiInk = Color(0xFFF0F2F4)
internal val MmiDim = Color(0xFF9AA0A8)
internal val MmiCyan = Color(0xFF3EC6F0)
internal val MmiFont = FontFamily.SansSerif
internal val MmiPageBg = Color(0xFF0B0C0E)

internal val ChromeRim = listOf(Color(0xFFF2F4F7), Color(0xFF8C939C), Color(0xFFDDE1E6), Color(0xFF4F555D))

/**
 * Click that feels like touching glass: a soft press-in, a light sheen that spreads
 * from the finger and fades, and a short glass tap sound. Everything animates in the
 * draw phase, so a press never recomposes the screen.
 */
@Composable
internal fun Modifier.pressable(onClick: () -> Unit): Modifier {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    // Knob highlight: only ever shows after a knob step (touch never focuses).
    val focused by source.collectIsFocusedAsState()
    val scale by animateFloatAsState(if (pressed) 0.965f else if (focused) 1.03f else 1f, tween(if (pressed) 70 else 160), label = "press")
    val sheen = remember { androidx.compose.animation.core.Animatable(0f) }
    var at by remember { mutableStateOf(Offset.Unspecified) }
    LaunchedEffect(source) {
        source.interactions.collect { i ->
            when (i) {
                is androidx.compose.foundation.interaction.PressInteraction.Press -> {
                    at = i.pressPosition
                    launch { sheen.animateTo(1f, tween(70)) }
                }
                is androidx.compose.foundation.interaction.PressInteraction.Release,
                is androidx.compose.foundation.interaction.PressInteraction.Cancel -> launch { sheen.animateTo(0f, tween(320)) }
            }
        }
    }
    return this
        .graphicsLayer { scaleX = scale; scaleY = scale }
        .drawWithContent {
            if (focused) drawRoundRect(MmiCyan.copy(alpha = 0.2f), cornerRadius = androidx.compose.ui.geometry.CornerRadius(6.dp.toPx()))
            drawContent()
            val g = sheen.value
            if (g > 0.01f) {
                val c = if (at.isSpecified) at else center
                drawRect(
                    Brush.radialGradient(
                        listOf(Color.White.copy(alpha = 0.20f * g), Color.White.copy(alpha = 0.06f * g), Color.Transparent),
                        center = c, radius = size.maxDimension * (0.45f + 0.45f * g),
                    )
                )
                // Thin bright edge along the top, like light catching a glass pane.
                drawRect(
                    Brush.horizontalGradient(listOf(Color.Transparent, Color.White.copy(alpha = 0.35f * g), Color.Transparent)),
                    size = androidx.compose.ui.geometry.Size(size.width, 1.5.dp.toPx()),
                )
            }
            if (focused) drawRoundRect(
                MmiCyan, cornerRadius = androidx.compose.ui.geometry.CornerRadius(6.dp.toPx()),
                style = androidx.compose.ui.graphics.drawscope.Stroke(2.dp.toPx()),
            )
        }
        .clickable(interactionSource = source, indication = null) {
            com.openlauncher.app.util.UiSound.tap()
            onClick()
        }
}

internal fun Modifier.mmiPanel(): Modifier {
    val shape = RoundedCornerShape(4.dp)
    return this.clip(shape)
        .background(Brush.verticalGradient(listOf(Color(0xFF17191C), Color(0xFF0D0E11))))
        .border(1.dp, Brush.verticalGradient(listOf(Color(0xFF5A6068), Color(0xFF1E2125), Color(0xFF3A3F46))), shape)
}

/** Round chrome-rimmed button with a white line glyph. */
@Composable
internal fun ChromeButton(kind: MmiLine, size: Dp, onClick: () -> Unit, highlighted: Boolean = false) {
    Box(
        Modifier.size(size).pressable(onClick).drawBehind {
            val r = this.size.minDimension / 2f
            drawCircle(Color(0x99000000), r, center + Offset(0f, r * 0.12f))
            drawCircle(Brush.linearGradient(ChromeRim, start = Offset.Zero, end = Offset(this.size.width, this.size.height)), r)
            drawCircle(
                Brush.radialGradient(listOf(Color(0xFF2B2F35), Color(0xFF0A0B0D)), center = center - Offset(0f, r * 0.3f), radius = r),
                r * 0.86f,
            )
            if (highlighted) drawCircle(MmiCyan.copy(alpha = 0.9f), r * 0.86f, style = Stroke(2.dp.toPx()))
        },
        contentAlignment = Alignment.Center,
    ) {
        MmiLineIcon(kind, if (highlighted) MmiCyan else MmiInk, Modifier.size(size * 0.38f))
    }
}

/** Rectangular chrome-edged button with an optional glyph and a label. */
@Composable
internal fun ChromePill(label: String, onClick: () -> Unit, kind: MmiLine? = null, modifier: Modifier = Modifier) {
    Row(
        modifier.height(48.dp).pressable(onClick).mmiPanel().padding(horizontal = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        if (kind != null) {
            MmiLineIcon(kind, MmiInk, Modifier.size(20.dp))
            Spacer(Modifier.width(10.dp))
        }
        Text(label, color = MmiInk, fontFamily = MmiFont, fontSize = 16.sp, maxLines = 1)
    }
}

internal fun Context.launchFirst(vararg intents: Intent): Boolean {
    for (intent in intents) {
        try {
            startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return true
        } catch (_: ActivityNotFoundException) {
        } catch (_: SecurityException) {
        }
    }
    return false
}

internal fun Context.launchPackage(pkg: String): Boolean {
    val intent = packageManager.getLaunchIntentForPackage(pkg) ?: return false
    return launchFirst(intent)
}

/** Head units ship their own Radio / Bluetooth-phone apps; find them by label. */
internal fun List<AppInfo>.findByLabel(vararg words: String): AppInfo? = firstOrNull { app ->
    val name = app.appName.lowercase()
    app.packageName != "com.android.settings" && words.any { name == it || name.contains(it) }
}

internal fun Context.appLabel(pkg: String?): String? = pkg?.let {
    runCatching { packageManager.getApplicationLabel(packageManager.getApplicationInfo(it, 0)).toString() }.getOrNull()
}

private fun formatMs(ms: Long): String {
    val s = ms.coerceAtLeast(0) / 1000
    return "%d:%02d".format(s / 60, s % 60)
}

// ── Music ────────────────────────────────────────────────────────────────────

@Composable
internal fun MmiMusicPage(
    state: NowPlayingState?,
    onPlayPause: () -> Unit,
    onNext: () -> Unit,
    onPrev: () -> Unit,
    onOpenSource: () -> Unit,
    onOpenMusicApp: () -> Unit,
    onOpenBluetooth: () -> Unit = {},
    onSettings: () -> Unit = {},
) {
    val context = LocalContext.current
    // Music comes from the phone over Bluetooth, or from a USB stick: nothing stored
    // on the unit. With neither connected, show the MIB "Media · Source" page instead.
    val phone by com.openlauncher.app.util.TwBridge.phoneConnected.collectAsStateWithLifecycle()
    val btConnected = phone == true || remember(state) { com.openlauncher.app.util.BtConnect.anyConnected() }
    val phoneName = rememberConnectedPhone()
    if (state == null && !btConnected) {
        Column(Modifier.fillMaxSize().padding(start = 10.dp)) {
            MibHeader("Media", "Source")
            MmiSourcePage(onBluetooth = {}, onConnect = onOpenBluetooth, onUsb = onOpenMusicApp, onOnline = onOpenSource)
        }
        return
    }
    val sourceName = when {
        state?.controller != null -> context.appLabel(state.controller.packageName) ?: "Media"
        else -> phoneName ?: "Bluetooth"
    }
    Column(Modifier.fillMaxSize().padding(start = 10.dp)) {
        MibHeader(
            sourceName, "Now playing",
            leading = {
                MibBox(onOpenMusicApp) { MmiLineIcon(MmiLine.GRID, Color.White, Modifier.size(28.dp)) }
                MibBox(onOpenSource, width = 150.dp) {
                    MmiLineIcon(if (state?.controller == null) MmiLine.BLUETOOTH else MmiLine.NOTE, Color.White, Modifier.size(24.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Source", color = Color.White, fontFamily = MmiFont, fontSize = 19.sp)
                }
            },
            trailing = {
                Box(
                    Modifier.size(58.dp).pressable(onSettings).drawBehind {
                        drawLine(MibLine, Offset(0f, size.height * 0.15f), Offset(0f, size.height), 1.dp.toPx())
                    },
                    contentAlignment = Alignment.Center,
                ) { MmiLineIcon(MmiLine.GEAR, Color.White, Modifier.size(26.dp)) }
            },
        )
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth().padding(start = 18.dp, end = 8.dp, top = 14.dp, bottom = 10.dp)) {
            val cover = minOf(maxHeight * 0.52f, maxWidth * 0.26f)
            Column(Modifier.fillMaxSize()) {
                Row(Modifier.fillMaxWidth()) {
                    Column(Modifier.weight(1f).height(cover)) {
                        // New song: the old title slides out to the left, the new one slides in from the right.
                        AnimatedContent(
                            targetState = Triple(state?.title.orEmpty(), state?.artist.orEmpty(), state?.controller?.metadata?.getString(MediaMetadata.METADATA_KEY_ALBUM).orEmpty()),
                            transitionSpec = {
                                (slideInHorizontally(tween(420, easing = FastOutSlowInEasing)) { it / 3 } + fadeIn(tween(320, delayMillis = 90))) togetherWith
                                    (slideOutHorizontally(tween(280)) { -it / 4 } + fadeOut(tween(200)))
                            },
                            modifier = Modifier.weight(1f),
                            label = "track",
                        ) { (title, artist, album) ->
                            Column(Modifier.fillMaxSize()) {
                                Text(
                                    title.ifBlank { if (state == null) "Bluetooth audio" else "Unknown title" }, color = Color.White, fontFamily = MmiFont,
                                    fontSize = 36.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 18.dp),
                                )
                                Spacer(Modifier.weight(1f))
                                if (state == null) {
                                    Text("Play music on your phone; the buttons below control it.", color = Color.White.copy(alpha = 0.75f), fontFamily = MmiFont, fontSize = 18.sp)
                                } else {
                                    if (artist.isNotBlank()) Text(artist, color = Color.White, fontFamily = MmiFont, fontSize = 20.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    if (album.isNotBlank()) Text(album, color = Color.White, fontFamily = MmiFont, fontSize = 20.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                            }
                        }
                    }
                    Spacer(Modifier.width(28.dp))
                    MibCover(state, cover)
                }
                Spacer(Modifier.height(10.dp))
                if (state != null) MusicProgress(state) else Spacer(Modifier.height(54.dp))
                Spacer(Modifier.weight(1f))
                // Transport: one outlined block (previous | play/pause | next) and More.
                Row(Modifier.fillMaxWidth().height(76.dp), verticalAlignment = Alignment.CenterVertically) {
                    Spacer(Modifier.weight(1f))
                    Row(Modifier.height(72.dp).border(1.dp, Brush.verticalGradient(listOf(Color(0x22FFFFFF), Color(0xAAFFFFFF))), androidx.compose.ui.graphics.RectangleShape)) {
                        TransportCell(MmiLine.PREV, onPrev)
                        TransportCell(if (state?.isPlaying == true) MmiLine.PAUSE else MmiLine.PLAY, onPlayPause)
                        TransportCell(MmiLine.NEXT, onNext, last = true)
                    }
                    Spacer(Modifier.weight(1f))
                    Column(
                        Modifier.width(110.dp).fillMaxHeight().pressable(onOpenMusicApp).drawBehind {
                            drawLine(MibLine, Offset(0f, size.height * 0.15f), Offset(0f, size.height * 0.85f), 1.dp.toPx())
                        },
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        MmiLineIcon(MmiLine.DOTS, Color.White, Modifier.size(28.dp))
                        Text("More", color = Color.White, fontFamily = MmiFont, fontSize = 17.sp)
                    }
                }
            }
        }
    }
}

@Composable
private fun TransportCell(icon: MmiLine, onClick: () -> Unit, last: Boolean = false) {
    Box(
        Modifier.width(104.dp).fillMaxHeight().pressable(onClick).drawBehind {
            if (!last) drawLine(Color(0x55FFFFFF), Offset(size.width, size.height * 0.2f), Offset(size.width, size.height * 0.8f), 1.dp.toPx())
        },
        contentAlignment = Alignment.Center,
    ) { MmiLineIcon(icon, Color.White, Modifier.size(30.dp)) }
}

/** Square cover with a hairline frame; cross-fades on a new song. */
@Composable
private fun MibCover(state: NowPlayingState?, size: Dp) {
    val cover = rememberCover(state)
    Crossfade(targetState = cover, animationSpec = tween(450), label = "cover") { art ->
        Box(
            Modifier.size(size).background(Brush.linearGradient(listOf(Color(0xFF22262C), Color(0xFF0A0B0D))))
                .border(1.dp, Color(0x66FFFFFF), androidx.compose.ui.graphics.RectangleShape),
            contentAlignment = Alignment.Center,
        ) {
            if (art != null) Image(art, contentDescription = state?.title, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            else MmiLineIcon(MmiLine.NOTE, Color.White.copy(alpha = 0.5f), Modifier.size(size * 0.35f))
        }
    }
}

/** Album art from the source app: full-size URI when readable, else the metadata bitmap. */
@Composable
private fun rememberCover(state: NowPlayingState?): ImageBitmap? {
    val context = LocalContext.current
    val uri = state?.artUri
    val fromUri by produceState<ImageBitmap?>(null, uri) {
        value = if (uri == null) null else withContext(Dispatchers.IO) {
            runCatching {
                context.contentResolver.openInputStream(Uri.parse(uri))?.use { BitmapFactory.decodeStream(it) }
                    ?.let { b -> if (b.width > 640) android.graphics.Bitmap.createScaledBitmap(b, 640, 640 * b.height / b.width, true) else b }
                    ?.asImageBitmap()
            }.getOrNull()
        }
    }
    val bitmap = state?.albumArt
    val fromMetadata = remember(bitmap) { bitmap?.asImageBitmap() }
    return fromUri ?: fromMetadata
}

@Composable
private fun CoverArt(state: NowPlayingState?, size: Dp) {
    val cover = rememberCover(state)
    val frame = RoundedCornerShape(4.dp)
    // Cross-fades to the new cover when the song changes.
    Crossfade(targetState = cover, animationSpec = tween(450), label = "cover") { art ->
        Column {
            Box(
                Modifier.size(size).clip(frame)
                    .background(Brush.linearGradient(listOf(Color(0xFF22262C), Color(0xFF0A0B0D))))
                    .border(1.5.dp, Brush.verticalGradient(ChromeRim), frame),
                contentAlignment = Alignment.Center,
            ) {
                if (art != null) {
                    Image(art, contentDescription = state?.title, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                } else {
                    MmiIcon(MmiGlyph.NOTE, Modifier.size(size * 0.45f))
                }
            }
            // Floor reflection: the flipped cover fading into the page background.
            Box(Modifier.width(size).height(size * 0.22f).clipToBounds()) {
                if (art != null) {
                    Image(
                        art, contentDescription = null, contentScale = ContentScale.Crop,
                        modifier = Modifier.requiredSize(size).align(Alignment.TopCenter)
                            .graphicsLayer { scaleY = -1f; alpha = 0.28f },
                    )
                }
                Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(MmiPageBg.copy(alpha = 0.3f), MmiPageBg))))
            }
        }
    }
}

@Composable
private fun MusicProgress(state: NowPlayingState) {
    // Bluetooth music on TS10 units: position and length come from the vendor Bluetooth app.
    val bt by com.openlauncher.app.util.TwBridge.btTrack.collectAsStateWithLifecycle()
    val btTrack = bt?.takeIf { state.controller == null && it.title == state.title }
    var positionMs by remember(state.title) { mutableLongStateOf(state.controller?.playbackState?.position ?: 0L) }
    val durationMs = btTrack?.durationMs?.toLong()?.takeIf { it > 0 }
        ?: state.controller?.metadata?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0L
    LaunchedEffect(btTrack?.positionMs) { btTrack?.let { positionMs = it.positionMs.toLong() } }
    LaunchedEffect(state.isPlaying, state.title) {
        while (state.isPlaying) {
            positionMs = state.controller?.playbackState?.position
                // Between the Bluetooth app's updates, keep counting so the time moves smoothly.
                ?: (positionMs + 500).coerceAtMost(if (durationMs > 0) durationMs else Long.MAX_VALUE)
            delay(500)
        }
    }
    // Drag to seek only works with a real media session (phone music apps). The
    // vendor Bluetooth module has no seek, so its bar is a plain indicator.
    val seekable = state.controller?.transportControls != null &&
        (state.controller.playbackState?.actions?.and(android.media.session.PlaybackState.ACTION_SEEK_TO) ?: 0L) != 0L
    var dragFraction by remember { mutableStateOf<Float?>(null) }
    val fraction = dragFraction ?: if (durationMs > 0) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f
    val glide by animateFloatAsState(fraction, tween(450), label = "progress")
    val shown = if (dragFraction != null) fraction else glide
    Column {
        // "1:20 | -1:42" above the right end of the bar, as on the MIB.
        Text(
            formatMs(positionMs) + if (durationMs > 0) "  |  -" + formatMs(durationMs - positionMs) else "",
            color = Color.White, fontFamily = MmiFont, fontSize = 18.sp,
            modifier = Modifier.align(Alignment.End).padding(end = 4.dp, bottom = 2.dp),
        )
        Canvas(
            Modifier.fillMaxWidth().height(28.dp).then(
                if (!seekable) Modifier else Modifier.pointerInput(durationMs) {
                    detectHorizontalDragGestures(
                        onDragStart = { pos -> dragFraction = (pos.x / size.width).coerceIn(0f, 1f) },
                        onDragEnd = {
                            dragFraction?.let { state.controller?.transportControls?.seekTo((it * durationMs).toLong()) }
                            dragFraction = null
                        },
                        onDragCancel = { dragFraction = null },
                    ) { change, _ -> change.consume(); dragFraction = (change.position.x / size.width).coerceIn(0f, 1f) }
                }.pointerInput(durationMs) {
                    detectTapGestures { pos ->
                        val f = (pos.x / size.width).coerceIn(0f, 1f)
                        state.controller?.transportControls?.seekTo((f * durationMs).toLong())
                    }
                }
            ),
        ) {
            val y = size.height / 2f
            val x = size.width * shown
            drawLine(Color(0xFFD5D8DC), Offset(0f, y), Offset(size.width, y), 2.dp.toPx())
            drawLine(MibAccent, Offset(0f, y), Offset(x, y), 2.5.dp.toPx())
            drawCircle(Color(0xFF0B0C0E), 7.dp.toPx(), Offset(x, y))
            drawCircle(MibAccent, 7.dp.toPx(), Offset(x, y), style = Stroke(2.dp.toPx()))
        }
    }
}

// ── Radio ────────────────────────────────────────────────────────────────────

@Composable
internal fun MmiRadioPage(
    radio: HardwareRadioState?,
    onOpenRadio: () -> Unit,
    onSeekUp: () -> Unit,
    onSeekDown: () -> Unit,
    onFm: () -> Unit,
    onAm: () -> Unit,
    /** Direct tuning (TS10): tap the scale, fine-step, presets. Null hides those controls. */
    onTune: ((am: Boolean, freq: Float) -> Unit)? = null,
) {
    val isAm = radio?.isAm == true
    val freq = radio?.freq?.filter { it.isDigit() || it == '.' }?.toFloatOrNull()
    val tw = com.openlauncher.app.util.TwBridge
    var presets by remember { mutableStateOf(if (onTune != null) tw.radioPresets() else emptyList()) }
    Column(
        Modifier.fillMaxSize().padding(horizontal = 48.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceEvenly,
    ) {
        Column(
            Modifier.fillMaxWidth(0.62f).mmiPanel().padding(vertical = 18.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(radio?.band ?: "FM", color = MmiCyan, fontFamily = MmiFont, fontSize = 16.sp, letterSpacing = 3.sp)
            Text(
                radio?.freq ?: "– – –", color = MmiInk, fontFamily = MmiFont,
                fontWeight = FontWeight.Light, fontSize = 64.sp,
            )
            Text(
                radio?.stationName ?: if (radio == null) "Radio not connected" else (if (isAm) "kHz" else "MHz"),
                color = MmiDim, fontFamily = MmiFont, fontSize = 18.sp,
            )
        }
        // Drag along the scale (or tap it): the needle follows the finger, the station
        // is tuned when the finger lifts, snapped to the band's channel steps.
        var dragFreq by remember { mutableStateOf<Float?>(null) }
        val bandMin = if (isAm) 531f else 87.5f
        val bandMax = if (isAm) 1602f else 108f
        fun snap(raw: Float) = if (isAm) kotlin.math.round(raw.coerceIn(bandMin, bandMax) / 9f) * 9f
            else kotlin.math.round(raw.coerceIn(bandMin, bandMax) * 10f) / 10f
        TuningScale(
            isAm, dragFreq ?: freq,
            Modifier.fillMaxWidth().height(70.dp).then(
                if (onTune == null) Modifier else Modifier
                    .pointerInput(isAm) {
                        detectTapGestures { pos -> onTune(isAm, snap(bandMin + (pos.x / size.width) * (bandMax - bandMin))) }
                    }
                    .pointerInput(isAm) {
                        detectHorizontalDragGestures(
                            onDragStart = { pos -> dragFreq = snap(bandMin + (pos.x / size.width) * (bandMax - bandMin)) },
                            onDragEnd = { dragFreq?.let { onTune(isAm, it) }; dragFreq = null },
                            onDragCancel = { dragFreq = null },
                        ) { change, _ ->
                            change.consume()
                            dragFreq = snap(bandMin + (change.position.x / size.width) * (bandMax - bandMin))
                        }
                    }
            ),
            follow = dragFreq != null,
        )
        // While dragging, the big readout shows where the finger is.
        dragFreq?.let {
            Text(
                if (isAm) "${it.toInt()} kHz" else "%.1f MHz".format(it),
                color = MmiCyan, fontFamily = MmiFont, fontSize = 22.sp,
            )
        }
        if (onTune != null && freq != null) {
            // Fine tuning and six presets (tap: tune, long-press: save the current station).
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                val step = if (isAm) 9f else 0.1f
                ChromePill("−", { onTune(isAm, freq - step) }, modifier = Modifier.width(64.dp))
                repeat(6) { slot ->
                    val p = presets.getOrNull(slot)?.takeIf { it > 0f }
                    Box(
                        Modifier.size(width = 92.dp, height = 48.dp).mmiPanel()
                            .pointerInput(freq, p) {
                                detectTapGestures(
                                    onTap = { if (p != null) onTune(p > 200f, p) },
                                    onLongPress = { tw.radioSavePreset(slot, freq); presets = tw.radioPresets() },
                                )
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            p?.let { if (it > 200f) it.toInt().toString() else "%.1f".format(it) } ?: "Hold to save",
                            color = if (p != null) MmiInk else MmiDim, fontFamily = MmiFont, fontSize = if (p != null) 17.sp else 11.sp,
                        )
                    }
                }
                ChromePill("+", { onTune(isAm, freq + step) }, modifier = Modifier.width(64.dp))
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            ChromeButton(MmiLine.PREV, 64.dp, onSeekDown)
            ChromeButton(MmiLine.NEXT, 64.dp, onSeekUp)
            ChromePill("FM", onFm, modifier = Modifier.width(90.dp))
            ChromePill("AM", onAm, modifier = Modifier.width(90.dp))
            ChromePill("Open radio", onOpenRadio, MmiLine.OPEN)
        }
    }
}

@Composable
private fun TuningScale(isAm: Boolean, freq: Float?, modifier: Modifier, follow: Boolean = false) {
    val min = if (isAm) 531f else 87.5f
    val max = if (isAm) 1602f else 108f
    val measurer = rememberTextMeasurer()
    val target = ((freq ?: min) - min) / (max - min)
    // Under the finger the needle follows instantly; otherwise it glides to the new station.
    val glide by animateFloatAsState(target, spring(dampingRatio = 0.85f, stiffness = Spring.StiffnessMediumLow), label = "tuning")
    val needle = if (follow) target else glide
    Box(modifier) {
        Spacer(
            Modifier.fillMaxSize().drawWithCache {
                val labelStyle = TextStyle(color = MmiDim, fontSize = 12.sp, fontFamily = MmiFont)
                val major = if (isAm) 100f else 2f
                val minor = if (isAm) 20f else 0.5f
                val labels = mutableListOf<Pair<Float, androidx.compose.ui.text.TextLayoutResult>>()
                var v = kotlin.math.ceil(min / major) * major
                while (v <= max) {
                    labels += ((v - min) / (max - min)) to measurer.measure(if (isAm) v.roundToInt().toString() else v.roundToInt().toString(), labelStyle)
                    v += major
                }
                val baseY = size.height * 0.55f
                onDrawBehind {
                    drawLine(Color(0xFF3A3F46), Offset(0f, baseY), Offset(size.width, baseY), 1.dp.toPx())
                    var t = min
                    while (t <= max + 0.001f) {
                        val x = (t - min) / (max - min) * size.width
                        val isMajor = kotlin.math.abs((t / major) - (t / major).roundToInt()) < 0.001f
                        drawLine(
                            if (isMajor) Color(0xFFB8BEC6) else Color(0xFF555B63),
                            Offset(x, baseY), Offset(x, baseY - (if (isMajor) 18.dp else 9.dp).toPx()), 1.5.dp.toPx(),
                        )
                        t += minor
                    }
                    labels.forEach { (p, layout) ->
                        drawText(layout, topLeft = Offset(p * size.width - layout.size.width / 2f, baseY + 6.dp.toPx()))
                    }
                }
            }
        )
        if (freq != null) {
            Canvas(Modifier.fillMaxSize()) {
                val x = needle * size.width
                drawLine(MmiCyan.copy(alpha = 0.35f), Offset(x, 0f), Offset(x, size.height * 0.62f), 8.dp.toPx(), StrokeCap.Round)
                drawLine(MmiCyan, Offset(x, 0f), Offset(x, size.height * 0.62f), 2.5.dp.toPx(), StrokeCap.Round)
            }
        }
    }
}

// ── Phone ────────────────────────────────────────────────────────────────────

@Composable
internal fun MmiPhonePage(apps: List<AppInfo>, onOpenContacts: () -> Unit, onOpenBluetooth: () -> Unit) {
    val context = LocalContext.current
    var number by remember { mutableStateOf("") }
    val phoneApp = remember(apps) { apps.findByLabel("bluetooth", "bt phone", "phone", "dial", "call") }
    val keys = listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "*", "0", "#")
    BoxWithConstraints(Modifier.fillMaxSize().padding(horizontal = 40.dp, vertical = 8.dp)) {
        val key = minOf(maxHeight / 6.2f, 64.dp)
        Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Row(
                    Modifier.width(key * 3 + 40.dp).height(key * 0.9f).mmiPanel().padding(horizontal = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        number.ifEmpty { "Enter number" }, color = if (number.isEmpty()) MmiDim else MmiInk,
                        fontFamily = MmiFont, fontSize = 22.sp, maxLines = 1, modifier = Modifier.weight(1f),
                    )
                    Box(Modifier.size(32.dp).pressable { number = number.dropLast(1) }, contentAlignment = Alignment.Center) {
                        MmiLineIcon(MmiLine.BACKSPACE, MmiDim, Modifier.size(26.dp))
                    }
                }
                Spacer(Modifier.height(10.dp))
                keys.chunked(3).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                        row.forEach { k ->
                            Box(
                                Modifier.size(key).pressable { number += k }.drawBehind {
                                    val r = size.minDimension / 2f
                                    drawCircle(Brush.linearGradient(ChromeRim, Offset.Zero, Offset(size.width, size.height)), r, style = Stroke(1.5.dp.toPx()))
                                    drawCircle(Brush.radialGradient(listOf(Color(0xFF24282E), Color(0xFF0B0C0E)), center, r), r - 2.dp.toPx())
                                },
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(k, color = MmiInk, fontFamily = MmiFont, fontSize = 24.sp)
                            }
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                }
            }
            Spacer(Modifier.width(56.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                val phone = rememberConnectedPhone()
                Row(verticalAlignment = Alignment.CenterVertically) {
                    MmiLineIcon(MmiLine.BLUETOOTH, if (phone != null) MmiCyan else MmiDim, Modifier.size(24.dp))
                    Spacer(Modifier.width(10.dp))
                    Text(
                        phone?.let { "Connected: $it" } ?: "No phone connected",
                        color = if (phone != null) MmiInk else MmiDim, fontFamily = MmiFont, fontSize = 18.sp,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
                ChromePill(
                    "Call", {
                        if (number.isNotEmpty()) context.placeCall(number)
                        else phoneApp?.let { context.launchPackage(it.packageName) }
                    },
                    MmiLine.CALL, Modifier.fillMaxWidth(),
                )
                ChromePill(
                    phoneApp?.appName ?: "Phone app", {
                        val opened = phoneApp?.let { context.launchPackage(it.packageName) } ?: false
                        if (!opened) context.launchFirst(Intent(Intent.ACTION_DIAL), Intent(Settings.ACTION_BLUETOOTH_SETTINGS))
                    },
                    MmiLine.OPEN, Modifier.fillMaxWidth(),
                )
                ChromePill("Contacts & recents", onOpenContacts, MmiLine.CONTACTS, Modifier.fillMaxWidth())
                ChromePill("Phones & pairing", onOpenBluetooth, MmiLine.BLUETOOTH, Modifier.fillMaxWidth())
            }
        }
    }
}

// ── Apps ─────────────────────────────────────────────────────────────────────

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun MmiAppsPage(
    apps: List<AppInfo>,
    hidden: Set<String>,
    onToggleHidden: (String) -> Unit,
    onLaunch: (String) -> Unit,
) {
    val context = LocalContext.current
    var editing by remember { mutableStateOf(false) }
    var menuApp by remember { mutableStateOf<AppInfo?>(null) }
    // Only real apps (with a launcher icon), never this launcher itself; hidden ones only while editing.
    val shown = remember(apps, hidden, editing) {
        apps.filter { it.launchable && it.packageName != context.packageName && (editing || it.packageName !in hidden) }
            .sortedBy { it.appName.lowercase() }
    }
    val perPage = 18
    val pages = ((shown.size + perPage - 1) / perPage).coerceAtLeast(1)
    val pager = rememberPagerState { pages }
    // Car knob: move a highlight through the apps and open on press (no touchscreen needed).
    var focus by remember(shown.size) { mutableIntStateOf(-1) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(shown.size) {
        com.openlauncher.app.util.KnobTarget.appsStep.collect { d ->
            if (shown.isEmpty()) return@collect
            focus = ((if (focus < 0) 0 else focus) + d + shown.size) % shown.size
            scope.launch { pager.animateScrollToPage(focus / perPage) }
        }
    }
    LaunchedEffect(shown) {
        com.openlauncher.app.util.KnobTarget.appsOpen.collect {
            shown.getOrNull(focus)?.let { onLaunch(it.packageName) }
        }
    }
    Box(Modifier.fillMaxSize()) {
    Column(Modifier.fillMaxSize().padding(horizontal = 32.dp, vertical = 4.dp)) {
        HorizontalPager(pager, Modifier.weight(1f).fillMaxWidth()) { page ->
            val slice = shown.drop(page * perPage).take(perPage)
            Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.SpaceEvenly) {
                slice.chunked(6).forEach { row ->
                    Row(Modifier.fillMaxWidth()) {
                        row.forEach { app ->
                            val isHidden = app.packageName in hidden
                            val focused = shown.getOrNull(focus) === app
                            AppCell(
                                app,
                                Modifier.weight(1f).graphicsLayer { alpha = if (editing && isHidden) 0.3f else 1f }
                                    .then(if (focused) Modifier.border(2.dp, MmiCyan, RoundedCornerShape(10.dp)) else Modifier),
                                onLongClick = { menuApp = app },
                            ) {
                                if (editing) onToggleHidden(app.packageName) else onLaunch(app.packageName)
                            }
                        }
                        repeat(6 - row.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
            }
        }
        Box(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
            Row(Modifier.align(Alignment.Center), horizontalArrangement = Arrangement.Center) {
                repeat(pages) { i ->
                    val color by animateColorAsState(if (i == pager.currentPage) MmiCyan else Color(0xFF4A5058), label = "dot")
                    Box(Modifier.padding(horizontal = 4.dp).size(width = 22.dp, height = 4.dp).clip(RoundedCornerShape(2.dp)).background(color))
                }
            }
            if (editing) {
                Text(
                    "Tap apps to hide or show them",
                    color = MmiDim, fontFamily = MmiFont, fontSize = 14.sp, modifier = Modifier.align(Alignment.CenterStart),
                )
            }
            ChromePill(if (editing) "Done" else "Edit", { editing = !editing }, modifier = Modifier.align(Alignment.CenterEnd))
        }
    }
    // Long-press menu: everything about an app without leaving MMI (uninstall asks Android to confirm).
    menuApp?.let { app ->
        Box(Modifier.fillMaxSize().background(Color(0xB0000000)).pressable { menuApp = null }, contentAlignment = Alignment.Center) {
            Column(Modifier.width(340.dp).mmiPanel().padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(app.appName, color = MmiInk, fontFamily = MmiFont, fontSize = 22.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                ChromePill("Open", { menuApp = null; onLaunch(app.packageName) }, MmiLine.OPEN, Modifier.fillMaxWidth())
                ChromePill(if (app.packageName in hidden) "Show in Apps" else "Hide from Apps", {
                    menuApp = null; onToggleHidden(app.packageName)
                }, MmiLine.GRID, Modifier.fillMaxWidth())
                if (!app.isSystemApp) {
                    ChromePill("Uninstall", {
                        menuApp = null
                        context.launchFirst(Intent(Intent.ACTION_DELETE, Uri.parse("package:${app.packageName}")))
                    }, MmiLine.CLOSE, Modifier.fillMaxWidth())
                }
                ChromePill("App info", {
                    menuApp = null
                    context.launchFirst(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${app.packageName}")))
                }, MmiLine.GEAR, Modifier.fillMaxWidth())
            }
        }
    }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun AppCell(app: AppInfo, modifier: Modifier, onLongClick: () -> Unit = {}, onClick: () -> Unit) {
    Column(
        modifier.combinedClickable(
            interactionSource = remember { MutableInteractionSource() }, indication = null,
            onLongClick = onLongClick, onClick = onClick,
        ).padding(vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier.size(64.dp).drawBehind {
                drawOval(Color(0x88000000), topLeft = Offset(size.width * 0.1f, size.height * 0.88f), size = Size(size.width * 0.8f, size.height * 0.14f))
            },
            contentAlignment = Alignment.Center,
        ) {
            val icon = remember(app.packageName) { runCatching { app.icon.toBitmap(112, 112).asImageBitmap() }.getOrNull() }
            if (icon != null) Image(icon, contentDescription = app.appName, modifier = Modifier.size(56.dp))
        }
        Spacer(Modifier.height(6.dp))
        Text(app.appName, color = MmiInk, fontFamily = MmiFont, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

// ── Dashboard ────────────────────────────────────────────────────────────────

@Composable
internal fun MmiDashboardPage(
    location: LocationData?,
    bearing: Float,
    voltage: Float?,
    metric: Boolean,
) {
    val speed = (location?.speedMps ?: 0f) * if (metric) 3.6f else 2.237f
    val maxSpeed = if (metric) 220f else 140f
    BoxWithConstraints(Modifier.fillMaxSize().padding(horizontal = 32.dp, vertical = 8.dp)) {
        val dial = minOf(maxHeight * 0.95f, maxWidth * 0.36f)
        Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            SpeedGauge(speed, maxSpeed, if (metric) "km/h" else "mph", Modifier.size(dial))
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(speed.roundToInt().toString(), color = MmiInk, fontFamily = MmiFont, fontWeight = FontWeight.Light, fontSize = 72.sp)
                Text(if (metric) "km/h" else "mph", color = MmiDim, fontFamily = MmiFont, fontSize = 16.sp)
                Spacer(Modifier.height(8.dp))
                DashStat("Altitude", location?.let { "${(if (metric) it.altitude else it.altitude * 3.281).roundToInt()} ${if (metric) "m" else "ft"}" } ?: "--")
                DashStat("Satellites", location?.satellitesUsed?.takeIf { it >= 0 }?.toString() ?: "--")
                DashStat("Battery", voltage?.let { "%.1f V".format(it) } ?: "--")
            }
            CompassGauge(bearing, Modifier.size(dial))
        }
    }
}

@Composable
private fun DashStat(label: String, value: String) {
    Row(Modifier.width(220.dp).mmiPanel().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text(label, color = MmiDim, fontFamily = MmiFont, fontSize = 15.sp)
        Spacer(Modifier.weight(1f))
        Text(value, color = MmiInk, fontFamily = MmiFont, fontSize = 15.sp)
    }
}

private const val GAUGE_START = 135f
private const val GAUGE_SWEEP = 270f

private fun androidx.compose.ui.graphics.drawscope.DrawScope.gaugeBezel(r: Float) {
    drawCircle(Color(0x99000000), r, center + Offset(0f, r * 0.05f))
    drawCircle(Brush.linearGradient(ChromeRim, Offset(center.x - r, center.y - r), Offset(center.x + r, center.y + r)), r)
    drawCircle(Brush.radialGradient(listOf(Color(0xFF23272D), Color(0xFF060708)), center - Offset(0f, r * 0.25f), r), r * 0.92f)
}

@Composable
private fun SpeedGauge(speed: Float, maxSpeed: Float, unit: String, modifier: Modifier) {
    val measurer = rememberTextMeasurer()
    val needle by animateFloatAsState(
        (speed / maxSpeed).coerceIn(0f, 1f),
        spring(dampingRatio = 0.8f, stiffness = Spring.StiffnessLow),
        label = "speed",
    )
    Box(modifier) {
        Spacer(
            Modifier.fillMaxSize().drawWithCache {
                val r = size.minDimension / 2f
                val labelStyle = TextStyle(color = MmiInk, fontSize = 15.sp, fontFamily = MmiFont)
                val step = if (maxSpeed > 150f) 20f else 10f
                val labels = generateSequence(0f) { it + step }.takeWhile { it <= maxSpeed }.map {
                    it to measurer.measure(it.roundToInt().toString(), labelStyle)
                }.toList()
                val unitLayout = measurer.measure(unit, TextStyle(color = MmiDim, fontSize = 14.sp, fontFamily = MmiFont))
                onDrawBehind {
                    gaugeBezel(r)
                    var v = 0f
                    while (v <= maxSpeed + 0.01f) {
                        val a = Math.toRadians((GAUGE_START + GAUGE_SWEEP * v / maxSpeed).toDouble())
                        val major = (v % step) < 0.01f
                        val o = r * 0.86f
                        val i = r * (if (major) 0.74f else 0.80f)
                        drawLine(
                            if (major) Color(0xFFE9ECEF) else Color(0xFF7D848D),
                            Offset(center.x + o * cos(a).toFloat(), center.y + o * sin(a).toFloat()),
                            Offset(center.x + i * cos(a).toFloat(), center.y + i * sin(a).toFloat()),
                            (if (major) 2.5.dp else 1.2.dp).toPx(),
                        )
                        v += step / 4f
                    }
                    labels.forEach { (value, layout) ->
                        val a = Math.toRadians((GAUGE_START + GAUGE_SWEEP * value / maxSpeed).toDouble())
                        val lr = r * 0.6f
                        drawText(
                            layout,
                            topLeft = Offset(
                                center.x + lr * cos(a).toFloat() - layout.size.width / 2f,
                                center.y + lr * sin(a).toFloat() - layout.size.height / 2f,
                            ),
                        )
                    }
                    drawText(unitLayout, topLeft = Offset(center.x - unitLayout.size.width / 2f, center.y + r * 0.42f))
                }
            }
        )
        Canvas(Modifier.fillMaxSize()) {
            val r = size.minDimension / 2f
            rotate(GAUGE_START + GAUGE_SWEEP * needle, center) {
                drawLine(Color(0x55E53935), center, center + Offset(r * 0.8f, 0f), 10.dp.toPx(), StrokeCap.Round)
                drawLine(Color(0xFFE53935), center, center + Offset(r * 0.8f, 0f), 3.5.dp.toPx(), StrokeCap.Round)
            }
            drawCircle(Brush.linearGradient(ChromeRim, center - Offset(r * 0.1f, r * 0.1f), center + Offset(r * 0.1f, r * 0.1f)), r * 0.09f)
        }
    }
}

@Composable
private fun CompassGauge(bearing: Float, modifier: Modifier) {
    val measurer = rememberTextMeasurer()
    // Accumulate so 359° -> 1° turns 2° instead of spinning backwards 358°.
    var continuous by remember { mutableFloatStateOf(bearing) }
    LaunchedEffect(bearing) {
        val current = ((continuous % 360f) + 360f) % 360f
        val diff = ((bearing - current + 540f) % 360f) - 180f
        continuous += diff
    }
    val heading by animateFloatAsState(continuous, spring(dampingRatio = 0.85f, stiffness = Spring.StiffnessLow), label = "heading")
    Box(modifier) {
        Spacer(Modifier.fillMaxSize().drawBehind { gaugeBezel(size.minDimension / 2f) })
        Spacer(
            Modifier.fillMaxSize().graphicsLayer { rotationZ = -heading }.drawWithCache {
                val r = size.minDimension / 2f
                val letters = listOf("N", "E", "S", "W").map {
                    measurer.measure(it, TextStyle(color = if (it == "N") Color(0xFFE53935) else MmiInk, fontSize = 22.sp, fontFamily = MmiFont))
                }
                onDrawBehind {
                    for (deg in 0 until 360 step 10) {
                        val a = Math.toRadians(deg - 90.0)
                        val major = deg % 30 == 0
                        val o = r * 0.86f
                        val i = r * (if (major) 0.76f else 0.81f)
                        drawLine(
                            if (major) Color(0xFFE9ECEF) else Color(0xFF7D848D),
                            Offset(center.x + o * cos(a).toFloat(), center.y + o * sin(a).toFloat()),
                            Offset(center.x + i * cos(a).toFloat(), center.y + i * sin(a).toFloat()),
                            (if (major) 2.dp else 1.dp).toPx(),
                        )
                    }
                    letters.forEachIndexed { idx, layout ->
                        val a = Math.toRadians(idx * 90.0 - 90.0)
                        val lr = r * 0.62f
                        drawText(
                            layout,
                            topLeft = Offset(
                                center.x + lr * cos(a).toFloat() - layout.size.width / 2f,
                                center.y + lr * sin(a).toFloat() - layout.size.height / 2f,
                            ),
                        )
                    }
                }
            }
        )
        Canvas(Modifier.fillMaxSize()) {
            val r = size.minDimension / 2f
            val tip = Path().apply {
                moveTo(center.x, center.y - r * 0.98f)
                lineTo(center.x - r * 0.06f, center.y - r * 0.86f)
                lineTo(center.x + r * 0.06f, center.y - r * 0.86f)
                close()
            }
            drawPath(tip, MmiCyan)
        }
        Text(
            "${(((bearing % 360f) + 360f) % 360f).roundToInt()}°",
            color = MmiInk, fontFamily = MmiFont, fontWeight = FontWeight.Light, fontSize = 30.sp,
            modifier = Modifier.align(Alignment.Center),
        )
    }
}

/** Whether a USB stick (or SD card) is mounted, re-checked every few seconds. */
@Composable
private fun rememberUsbInserted(): Boolean {
    val context = LocalContext.current
    var inserted by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        val sm = context.getSystemService(Context.STORAGE_SERVICE) as android.os.storage.StorageManager
        while (true) {
            inserted = android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N && runCatching {
                sm.storageVolumes.any { it.isRemovable && it.state == android.os.Environment.MEDIA_MOUNTED }
            }.getOrDefault(false)
            kotlinx.coroutines.delay(3000)
        }
    }
    return inserted
}

/**
 * MIB "Media · Source": the connected phone (selected), SD card, USB, connect a
 * new device and online media, inside an accent frame.
 */
@Composable
internal fun MmiSourcePage(onBluetooth: () -> Unit, onConnect: () -> Unit, onUsb: () -> Unit, onOnline: () -> Unit) {
    val usb = rememberUsbInserted()
    val phone = rememberConnectedPhone()
    Box(
        Modifier.fillMaxSize().padding(start = 16.dp, end = 8.dp, top = 18.dp, bottom = 14.dp)
            .border(1.dp, MibAccent.copy(alpha = 0.8f), androidx.compose.ui.graphics.RectangleShape)
            .padding(20.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(18.dp)) {
            Row(Modifier.fillMaxWidth().height(170.dp), horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                MibTile(phone ?: "Bluetooth", MmiLine.PHONE, Modifier.weight(1f).fillMaxHeight(), selected = phone != null, badge = MmiLine.BLUETOOTH) {
                    if (phone != null) onBluetooth() else onConnect()
                }
                MibTile("SD card", MmiLine.SDCARD, Modifier.weight(1f).fillMaxHeight(), enabled = false) {}
                MibTile("USB", MmiLine.USB, Modifier.weight(1f).fillMaxHeight(), enabled = usb, onClick = onUsb)
                MibTile("Connect ext. device", MmiLine.PHONE, Modifier.weight(1f).fillMaxHeight(), badge = MmiLine.PLUS, onClick = onConnect)
            }
            Row(Modifier.fillMaxWidth().height(170.dp), horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                MibTile("Connect online media", MmiLine.GLOBE, Modifier.weight(1f).fillMaxHeight(), badge = MmiLine.PLUS, onClick = onOnline)
                Spacer(Modifier.weight(3f))
            }
        }
    }
}

