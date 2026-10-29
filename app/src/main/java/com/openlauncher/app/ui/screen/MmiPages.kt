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
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
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

/** Click with a quick press-in scale, read in the draw phase so it never recomposes. */
@Composable
internal fun Modifier.pressable(onClick: () -> Unit): Modifier {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.92f else 1f, tween(110), label = "press")
    return this
        .graphicsLayer { scaleX = scale; scaleY = scale }
        .clickable(interactionSource = source, indication = null, onClick = onClick)
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
) {
    val context = LocalContext.current
    BoxWithConstraints(Modifier.fillMaxSize().padding(horizontal = 40.dp, vertical = 8.dp)) {
        val cover = minOf(maxHeight * 0.66f, maxWidth * 0.32f)
        Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                CoverArt(state, cover)
            }
            Spacer(Modifier.width(48.dp))
            Column(Modifier.weight(1f)) {
                if (state == null) {
                    Text("BLUETOOTH AUDIO", color = MmiCyan, fontFamily = MmiFont, fontSize = 13.sp, letterSpacing = 2.sp)
                    Spacer(Modifier.height(6.dp))
                    Text("No track info", color = MmiInk, fontFamily = MmiFont, fontWeight = FontWeight.Light, fontSize = 32.sp)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Play music on your phone. The buttons below still control it over Bluetooth.",
                        color = MmiDim, fontFamily = MmiFont, fontSize = 17.sp,
                    )
                    Spacer(Modifier.height(28.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        ChromeButton(MmiLine.PREV, 64.dp, onPrev)
                        Spacer(Modifier.width(22.dp))
                        ChromeButton(MmiLine.PLAY, 84.dp, onPlayPause, highlighted = true)
                        Spacer(Modifier.width(22.dp))
                        ChromeButton(MmiLine.NEXT, 64.dp, onNext)
                        Spacer(Modifier.width(36.dp))
                        ChromePill("Music app", onOpenMusicApp, MmiLine.NOTE)
                    }
                } else {
                    Text(
                        context.appLabel(state.controller?.packageName)?.uppercase() ?: "MEDIA",
                        color = MmiCyan, fontFamily = MmiFont, fontSize = 13.sp, letterSpacing = 2.sp,
                    )
                    Spacer(Modifier.height(6.dp))
                    // New song: the old title slides out to the left, the new one slides in from the right.
                    AnimatedContent(
                        targetState = state.title to state.artist,
                        transitionSpec = {
                            (slideInHorizontally(tween(420, easing = FastOutSlowInEasing)) { it / 3 } + fadeIn(tween(320, delayMillis = 90))) togetherWith
                                (slideOutHorizontally(tween(280)) { -it / 4 } + fadeOut(tween(200)))
                        },
                        label = "track",
                    ) { (title, artist) ->
                        Column(Modifier.fillMaxWidth()) {
                            Text(
                                title.ifBlank { "Unknown title" }, color = MmiInk, fontFamily = MmiFont,
                                fontWeight = FontWeight.Light, fontSize = 32.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                artist, color = MmiDim, fontFamily = MmiFont, fontSize = 20.sp,
                                maxLines = 1, overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                    Spacer(Modifier.height(24.dp))
                    MusicProgress(state)
                    Spacer(Modifier.height(24.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        ChromeButton(MmiLine.PREV, 64.dp, onPrev)
                        Spacer(Modifier.width(22.dp))
                        ChromeButton(if (state.isPlaying) MmiLine.PAUSE else MmiLine.PLAY, 84.dp, onPlayPause, highlighted = true)
                        Spacer(Modifier.width(22.dp))
                        ChromeButton(MmiLine.NEXT, 64.dp, onNext)
                        Spacer(Modifier.width(36.dp))
                        ChromePill("Open app", onOpenSource, MmiLine.OPEN)
                    }
                }
            }
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
    var positionMs by remember(state.title) { mutableLongStateOf(state.controller?.playbackState?.position ?: 0L) }
    val durationMs = state.controller?.metadata?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0L
    LaunchedEffect(state.isPlaying, state.title) {
        while (state.isPlaying) {
            positionMs = state.controller?.playbackState?.position ?: positionMs
            delay(500)
        }
    }
    val fraction = if (durationMs > 0) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f
    val shown by animateFloatAsState(fraction, tween(450), label = "progress")
    Column {
        Canvas(Modifier.fillMaxWidth().height(14.dp)) {
            val y = size.height / 2f
            val track = 4.dp.toPx()
            drawLine(Color(0xFF2A2E34), Offset(0f, y), Offset(size.width, y), track, StrokeCap.Round)
            val x = size.width * shown
            drawLine(
                Brush.horizontalGradient(listOf(Color(0xFF1C8FB8), MmiCyan)), Offset(0f, y), Offset(x, y), track, StrokeCap.Round,
            )
            drawCircle(Brush.linearGradient(ChromeRim), 7.dp.toPx(), Offset(x, y))
        }
        Spacer(Modifier.height(6.dp))
        Row(Modifier.fillMaxWidth()) {
            Text(formatMs(positionMs), color = MmiDim, fontFamily = MmiFont, fontSize = 14.sp)
            Spacer(Modifier.weight(1f))
            Text(if (durationMs > 0) "-" + formatMs(durationMs - positionMs) else "", color = MmiDim, fontFamily = MmiFont, fontSize = 14.sp)
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
) {
    val isAm = radio?.isAm == true
    val freq = radio?.freq?.filter { it.isDigit() || it == '.' }?.toFloatOrNull()
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
        TuningScale(isAm, freq, Modifier.fillMaxWidth().height(70.dp))
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
private fun TuningScale(isAm: Boolean, freq: Float?, modifier: Modifier) {
    val min = if (isAm) 530f else 87.5f
    val max = if (isAm) 1710f else 108f
    val measurer = rememberTextMeasurer()
    val needle by animateFloatAsState(
        ((freq ?: min) - min) / (max - min),
        spring(dampingRatio = 0.75f, stiffness = Spring.StiffnessLow),
        label = "tuning",
    )
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
internal fun MmiPhonePage(apps: List<AppInfo>) {
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
                ChromePill(
                    "Call", {
                        if (number.isNotEmpty()) context.launchFirst(Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + Uri.encode(number))))
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
                ChromePill("Contacts", { context.launchFirst(Intent(Intent.ACTION_VIEW, ContactsContract.Contacts.CONTENT_URI)) }, MmiLine.CONTACTS, Modifier.fillMaxWidth())
                ChromePill("Bluetooth", { context.launchFirst(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)) }, MmiLine.BLUETOOTH, Modifier.fillMaxWidth())
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
    // Only real apps (with a launcher icon), never this launcher itself; hidden ones only while editing.
    val shown = remember(apps, hidden, editing) {
        apps.filter { it.launchable && it.packageName != context.packageName && (editing || it.packageName !in hidden) }
            .sortedBy { it.appName.lowercase() }
    }
    val perPage = 18
    val pages = ((shown.size + perPage - 1) / perPage).coerceAtLeast(1)
    val pager = rememberPagerState { pages }
    Column(Modifier.fillMaxSize().padding(horizontal = 32.dp, vertical = 4.dp)) {
        HorizontalPager(pager, Modifier.weight(1f).fillMaxWidth()) { page ->
            val slice = shown.drop(page * perPage).take(perPage)
            Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.SpaceEvenly) {
                slice.chunked(6).forEach { row ->
                    Row(Modifier.fillMaxWidth()) {
                        row.forEach { app ->
                            val isHidden = app.packageName in hidden
                            AppCell(app, Modifier.weight(1f).graphicsLayer { alpha = if (editing && isHidden) 0.3f else 1f }) {
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
}

@Composable
private fun AppCell(app: AppInfo, modifier: Modifier, onClick: () -> Unit) {
    Column(modifier.pressable(onClick).padding(vertical = 4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
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
