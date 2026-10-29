package com.openlauncher.app.ui.screen

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.media.MediaMetadata
import android.net.Uri
import android.provider.CallLog
import android.provider.ContactsContract
import android.text.format.DateFormat
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PointMode
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.openlauncher.app.model.NowPlayingState
import com.openlauncher.app.viewmodel.LauncherViewModel
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val MbuxFont = FontFamily.SansSerif
private val Ink = Color(0xFFF2F7FC)
private val InkDim = Color(0xFF9CB6D4)
private val OrbTop = Color(0xFF1B3557)
private val OrbBottom = Color(0xFF07111F)

/**
 * MBUX-style home: media zone with glowing album art on top, Phone / Navigation /
 * Radio tiles below, perspective dot floor behind. Shown in place of the widget
 * grid when the MBUX dashboard theme is active.
 */
@Composable
fun MbuxHomeScreen(
    nowPlaying: NowPlayingState?,
    hardwareRadio: LauncherViewModel.HardwareRadioState?,
    accent: Color,
    onPlayPause: () -> Unit,
    onNext: () -> Unit,
    onPrev: () -> Unit,
    onTapNowPlaying: () -> Unit,
    onLaunchRadio: () -> Unit,
    onRadioSeekUp: () -> Unit,
    onRadioSeekDown: () -> Unit,
    onOpenApps: () -> Unit,
) {
    Box(Modifier.fillMaxSize().mbuxBackdrop(accent)) {
        Column(Modifier.fillMaxSize()) {
            StatusStrip()
            MediaZone(
                state = nowPlaying,
                accent = accent,
                onPlayPause = onPlayPause,
                onNext = onNext,
                onPrev = onPrev,
                onTapArt = { if (nowPlaying != null) onTapNowPlaying() else onOpenApps() },
                modifier = Modifier.weight(1.15f).fillMaxWidth(),
            )
            GlowDivider(accent)
            TilesZone(
                radio = hardwareRadio,
                accent = accent,
                onLaunchRadio = onLaunchRadio,
                onRadioSeekUp = onRadioSeekUp,
                onRadioSeekDown = onRadioSeekDown,
                modifier = Modifier.weight(1f).fillMaxWidth(),
            )
            SuggestionsHandle(accent, onOpenApps)
        }
    }
}

// ── Background ────────────────────────────────────────────────────────────────

private fun Modifier.mbuxBackdrop(accent: Color) = drawWithCache {
    val w = size.width
    val h = size.height
    val base = Brush.verticalGradient(
        0f to Color(0xFF0B2D57),
        0.45f to Color(0xFF041122),
        1f to Color(0xFF01050C),
    )
    val glow = Brush.radialGradient(
        colors = listOf(accent.copy(alpha = 0.20f), Color.Transparent),
        center = Offset(w / 2f, h * 0.30f),
        radius = w * 0.55f,
    )
    // Perspective "floor" of dots: rows get further apart and dots larger and
    // brighter toward the bottom, like the MBUX zero-layer ground plane.
    val horizon = h * 0.60f
    val rows = 13
    val rowsPts = (0 until rows).map { r ->
        val t = (r + 1f) / rows
        val y = horizon + (h - horizon) * t * t
        val spacing = (10f + 30f * t * t).dp.toPx()
        val count = (w / spacing).toInt() + 2
        val start = (w - (count - 1) * spacing) / 2f
        Triple(List(count) { i -> Offset(start + i * spacing, y) }, t, (1.2f + 2.4f * t).dp.toPx())
    }
    onDrawBehind {
        drawRect(base)
        drawRect(glow)
        rowsPts.forEach { (pts, t, stroke) ->
            drawPoints(
                points = pts,
                pointMode = PointMode.Points,
                color = accent.copy(alpha = 0.08f + 0.30f * t),
                strokeWidth = stroke,
                cap = StrokeCap.Round,
            )
        }
    }
}

@Composable
private fun GlowDivider(accent: Color) {
    Box(
        Modifier.fillMaxWidth().height(1.dp).background(
            Brush.horizontalGradient(
                listOf(Color.Transparent, accent.copy(alpha = 0.55f), Color.Transparent)
            )
        )
    )
}

// ── Status strip ──────────────────────────────────────────────────────────────

@Composable
private fun StatusStrip() {
    val context = LocalContext.current
    val now by produceState(System.currentTimeMillis()) {
        while (true) {
            value = System.currentTimeMillis()
            delay(1000 - value % 1000)
        }
    }
    val timeFmt = remember(context) {
        SimpleDateFormat(if (DateFormat.is24HourFormat(context)) "HH:mm" else "h:mm", Locale.getDefault())
    }
    val dateFmt = remember { SimpleDateFormat("EEE, d MMM", Locale.getDefault()) }
    Box(Modifier.fillMaxWidth().height(40.dp).padding(horizontal = 20.dp)) {
        Text(
            dateFmt.format(Date(now)),
            color = InkDim, fontFamily = MbuxFont, fontSize = 13.sp,
            modifier = Modifier.align(Alignment.CenterStart),
        )
        Text(
            timeFmt.format(Date(now)),
            color = Ink, fontFamily = MbuxFont, fontWeight = FontWeight.Light, fontSize = 18.sp,
            modifier = Modifier.align(Alignment.Center),
        )
    }
}

// ── Media zone ────────────────────────────────────────────────────────────────

@Composable
private fun MediaZone(
    state: NowPlayingState?,
    accent: Color,
    onPlayPause: () -> Unit,
    onNext: () -> Unit,
    onPrev: () -> Unit,
    onTapArt: () -> Unit,
    modifier: Modifier,
) {
    BoxWithConstraints(modifier, contentAlignment = Alignment.Center) {
        val artSize = (maxHeight * 0.68f).coerceIn(96.dp, 210.dp)
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 32.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Spacer(Modifier.weight(0.15f))
            GlowingArt(state, accent, artSize, onTapArt)
            Spacer(Modifier.width(36.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    state?.title?.takeIf { it.isNotBlank() } ?: "Nothing playing",
                    color = Ink, fontFamily = MbuxFont, fontWeight = FontWeight.Light,
                    fontSize = 26.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    state?.artist?.takeIf { it.isNotBlank() } ?: "Tap the cover to choose music",
                    color = InkDim, fontFamily = MbuxFont, fontSize = 15.sp,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(16.dp))
                if (state != null) ProgressRow(state, accent)
                Spacer(Modifier.height(14.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    MiniOrb(Icons.Default.SkipPrevious, "Previous", accent, 44.dp, onPrev)
                    Spacer(Modifier.width(18.dp))
                    MiniOrb(
                        if (state?.isPlaying == true) Icons.Default.Pause else Icons.Default.PlayArrow,
                        "Play or pause", accent, 56.dp, onPlayPause, highlighted = true,
                    )
                    Spacer(Modifier.width(18.dp))
                    MiniOrb(Icons.Default.SkipNext, "Next", accent, 44.dp, onNext)
                }
            }
        }
    }
}

@Composable
private fun GlowingArt(state: NowPlayingState?, accent: Color, size: Dp, onClick: () -> Unit) {
    Box(
        Modifier
            .size(size)
            .drawBehind {
                val r = this.size.minDimension
                drawCircle(
                    Brush.radialGradient(listOf(accent.copy(alpha = 0.50f), Color.Transparent), center, r),
                    radius = r, center = center,
                )
                drawCircle(
                    Brush.radialGradient(listOf(Color.White.copy(alpha = 0.16f), Color.Transparent), center, r * 0.75f),
                    radius = r * 0.75f, center = center,
                )
            }
            .clip(RoundedCornerShape(8.dp))
            .border(1.dp, Color.White.copy(alpha = 0.35f), RoundedCornerShape(8.dp))
            .background(Brush.linearGradient(listOf(Color(0xFF1F2A44), Color(0xFF060911))))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        val model: Any? = state?.artUri ?: state?.albumArt
        if (model != null) {
            AsyncImage(
                model = model,
                contentDescription = state?.title,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Icon(Icons.Default.MusicNote, null, tint = accent, modifier = Modifier.size(size * 0.35f))
        }
    }
}

@Composable
private fun ProgressRow(state: NowPlayingState, accent: Color) {
    var positionMs by remember(state.title) {
        mutableLongStateOf(state.controller?.playbackState?.position ?: 0L)
    }
    val durationMs = state.controller?.metadata?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0L
    LaunchedEffect(state.isPlaying, state.title) {
        while (state.isPlaying) {
            positionMs = state.controller?.playbackState?.position ?: positionMs
            delay(500)
        }
    }
    if (durationMs <= 0) return
    val fraction = (positionMs.toFloat() / durationMs).coerceIn(0f, 1f)
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(formatMs(positionMs), color = InkDim, fontFamily = MbuxFont, fontSize = 12.sp)
        Spacer(Modifier.width(10.dp))
        Box(
            Modifier.weight(1f).height(3.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.15f))
        ) {
            Box(Modifier.fillMaxHeight().fillMaxWidth(fraction).background(accent))
        }
        Spacer(Modifier.width(10.dp))
        Text("-" + formatMs(durationMs - positionMs), color = InkDim, fontFamily = MbuxFont, fontSize = 12.sp)
    }
}

private fun formatMs(ms: Long): String {
    val s = (ms.coerceAtLeast(0) / 1000)
    return "%d:%02d".format(s / 60, s % 60)
}

// ── Tiles ─────────────────────────────────────────────────────────────────────

@Composable
private fun TilesZone(
    radio: LauncherViewModel.HardwareRadioState?,
    accent: Color,
    onLaunchRadio: () -> Unit,
    onRadioSeekUp: () -> Unit,
    onRadioSeekDown: () -> Unit,
    modifier: Modifier,
) {
    val context = LocalContext.current
    BoxWithConstraints(modifier) {
        val orbSize = (maxHeight * 0.42f).coerceIn(56.dp, 110.dp)
        Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
            Tile(
                label = "Phone", sublabel = "Dialer",
                modifier = Modifier.weight(1f),
                orb = { TileOrb(orbSize, accent, glow = 0.25f, onClick = { context.startFirst(dialIntent()) }) {
                    Icon(Icons.Default.Phone, "Phone", tint = accent, modifier = Modifier.size(orbSize * 0.45f))
                } },
                minis = {
                    MiniOrb(Icons.Default.History, "Recent calls", accent, 34.dp,
                        { context.startFirst(Intent(Intent.ACTION_VIEW, CallLog.Calls.CONTENT_URI), dialIntent()) })
                    Spacer(Modifier.width(10.dp))
                    MiniOrb(Icons.Default.Contacts, "Contacts", accent, 34.dp,
                        { context.startFirst(Intent(Intent.ACTION_VIEW, ContactsContract.Contacts.CONTENT_URI)) })
                },
            )
            Tile(
                label = "Navigation", sublabel = "Tap to navigate",
                modifier = Modifier.weight(1f),
                orb = { TileOrb(orbSize * 1.25f, accent, glow = 0.6f, onClick = { context.openMaps(null) }) {
                    NavArrow(Modifier.size(orbSize * 0.6f))
                } },
                minis = {
                    MiniOrb(Icons.Default.Search, "Search destination", accent, 34.dp, { context.openMaps("") })
                    Spacer(Modifier.width(10.dp))
                    MiniOrb(Icons.Default.LocalGasStation, "Nearby fuel", accent, 34.dp, { context.openMaps("gas station") })
                },
            )
            Tile(
                label = "Radio", sublabel = radio?.stationName ?: radio?.display ?: "FM",
                modifier = Modifier.weight(1f),
                orb = { TileOrb(orbSize, accent, glow = 0.25f, onClick = onLaunchRadio) {
                    Icon(Icons.Default.Radio, "Radio", tint = accent, modifier = Modifier.size(orbSize * 0.45f))
                } },
                minis = {
                    MiniOrb(Icons.Default.SkipPrevious, "Seek down", accent, 34.dp, onRadioSeekDown)
                    Spacer(Modifier.width(10.dp))
                    MiniOrb(Icons.Default.SkipNext, "Seek up", accent, 34.dp, onRadioSeekUp)
                },
            )
        }
    }
}

@Composable
private fun Tile(
    label: String,
    sublabel: String,
    modifier: Modifier,
    orb: @Composable () -> Unit,
    minis: @Composable RowScope.() -> Unit,
) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        orb()
        Spacer(Modifier.height(8.dp))
        Text(label, color = Ink, fontFamily = MbuxFont, fontWeight = FontWeight.Light, fontSize = 18.sp)
        Text(sublabel, color = InkDim, fontFamily = MbuxFont, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically, content = minis)
    }
}

@Composable
private fun TileOrb(size: Dp, accent: Color, glow: Float, onClick: () -> Unit, content: @Composable BoxScope.() -> Unit) {
    Box(
        Modifier
            .size(size)
            .drawBehind {
                val r = this.size.minDimension * 0.85f
                drawCircle(
                    Brush.radialGradient(listOf(accent.copy(alpha = glow), Color.Transparent), center, r),
                    radius = r, center = center,
                )
            }
            .clip(CircleShape)
            .background(Brush.verticalGradient(listOf(OrbTop, OrbBottom)))
            .border(1.dp, accent.copy(alpha = 0.55f), CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
        content = content,
    )
}

@Composable
private fun MiniOrb(
    icon: ImageVector,
    description: String,
    accent: Color,
    size: Dp,
    onClick: () -> Unit,
    highlighted: Boolean = false,
) {
    Box(
        Modifier
            .size(size)
            .clip(CircleShape)
            .background(Color(0xFF0E1E33))
            .border(if (highlighted) 1.5.dp else 1.dp, if (highlighted) accent else Color(0xFF2A4A72), CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, description, tint = Ink, modifier = Modifier.size(size * 0.5f))
    }
}

@Composable
private fun NavArrow(modifier: Modifier) {
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        val tip = Offset(w / 2f, h * 0.06f)
        val notch = Offset(w / 2f, h * 0.70f)
        drawPath(Path().apply {
            moveTo(tip.x, tip.y); lineTo(w * 0.14f, h * 0.94f); lineTo(notch.x, notch.y); close()
        }, Color(0xFFE53935))
        drawPath(Path().apply {
            moveTo(tip.x, tip.y); lineTo(notch.x, notch.y); lineTo(w * 0.86f, h * 0.94f); close()
        }, Color(0xFFA6232A))
    }
}

// ── Suggestions handle ───────────────────────────────────────────────────────

@Composable
private fun SuggestionsHandle(accent: Color, onClick: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(top = 4.dp, bottom = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            repeat(7) { i ->
                Box(
                    Modifier.size(if (i == 2) 7.dp else 5.dp).clip(CircleShape)
                        .background(if (i == 2) Color(0xFFE53935) else Ink.copy(alpha = 0.5f))
                )
            }
        }
        Spacer(Modifier.height(4.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.KeyboardArrowUp, null, tint = InkDim, modifier = Modifier.size(16.dp))
            Text("SUGGESTIONS", color = InkDim, fontFamily = MbuxFont, fontSize = 11.sp, letterSpacing = 3.sp)
        }
    }
}

// ── Intents ──────────────────────────────────────────────────────────────────

private fun dialIntent() = Intent(Intent.ACTION_DIAL)

private fun Context.startFirst(vararg intents: Intent) {
    for (intent in intents) {
        try {
            startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return
        } catch (_: ActivityNotFoundException) {
        } catch (_: SecurityException) {
        }
    }
}

private val MAPS_PACKAGES = listOf("com.google.android.apps.maps", "com.waze", "com.here.app.maps")

private fun Context.openMaps(query: String?) {
    if (query != null) {
        val geo = Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=" + Uri.encode(query)))
        startFirst(geo)
        return
    }
    val launch = MAPS_PACKAGES.firstNotNullOfOrNull { packageManager.getLaunchIntentForPackage(it) }
    if (launch != null) startFirst(launch) else startFirst(Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0")))
}
