package com.openlauncher.app.ui.screen

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.openlauncher.app.R

/** One home tile: your artwork, a small outline icon with an upper-case label, a coloured underline. */
internal class HomeTile(
    val key: String,
    val label: String,
    val icon: MmiLine,
    val art: Int,
    val underline: Color,
)

// Underline colours as on the MIB home screen.
private val GOLD = Color(0xFFE8C25A)
private val AMBER = Color(0xFFF0A030)
private val SILVER = Color(0xFFD9DCE0)
private val BLUE = Color(0xFF3FA9F5)
private val LIME = Color(0xFFD8E05A)
private val RED = Color(0xFFE5304A)

/** Two pages of eight, in knob order. */
internal val HOME_TILES = listOf(
    HomeTile("radio", "RADIO", MmiLine.ANTENNA, R.drawable.home_art_radio, GOLD),
    HomeTile("music", "MEDIA", MmiLine.NOTE, R.drawable.home_art_media, AMBER),
    HomeTile("phone", "TELEPHONE", MmiLine.CALL, R.drawable.home_art_telephone, SILVER),
    HomeTile("nav", "NAVIGATION", MmiLine.NAV_ARROW, R.drawable.home_art_navigation, BLUE),
    HomeTile("phonelink", "PHONE APPS", MmiLine.LINK, R.drawable.home_art_phoneapps, LIME),
    HomeTile("carinfo", "CAR", MmiLine.CAR, R.drawable.home_art_car, RED),
    HomeTile("settings", "SETTINGS", MmiLine.GEAR, R.drawable.home_art_settings, SILVER),
    HomeTile("help", "HELP", MmiLine.CARD, R.drawable.home_art_help, SILVER),
    HomeTile("video", "VIDEO", MmiLine.FILM, R.drawable.home_art_video, AMBER),
    HomeTile("eq", "EQUALISER", MmiLine.SPEAKER, R.drawable.home_art_equaliser, GOLD),
    HomeTile("weather", "WEATHER", MmiLine.CLOUD, R.drawable.home_art_weather, BLUE),
    HomeTile("apps", "APPS", MmiLine.GRID, R.drawable.home_art_apps, LIME),
    HomeTile("browser", "BROWSER", MmiLine.GLOBE, R.drawable.home_art_browser, SILVER),
)

private const val PER_PAGE = 8

// Reference screen geometry (1920 × 720 px). Everything is scaled by width ÷ 1920;
// only the tile height grows to fill a taller screen.
private const val REF_W = 1920f
private const val TOP_BAR = 86f
private const val RAIL_TOP = 105f
private const val RAIL_BOTTOM_GAP = 22f          // 720 − 698
private const val RAIL_W = 180f
private const val RAIL_GAP = 2f
private const val TILE_TOP = 116f
private const val TILE_BOTTOM_GAP = 62f          // 720 − 658
private const val ROW_GAP = 42f
private const val STRIP_H = 57f                  // label strip incl. underline
private const val ART_W = 348f
private const val ART_H = 193f
private const val FRAME_W = 2f
private val TILE_X = floatArrayOf(270f, 672f, 1074f, 1477f)
private val TILE_W = floatArrayOf(352f, 352f, 353f, 353f)

/** The rail, top to bottom, as on the MIB: Home, Radio, Media, Telephone, Navigation. */
private class RailEntry(val key: String, val icon: MmiLine)
private val HOME_RAIL = listOf(
    RailEntry("home", MmiLine.HOME),
    RailEntry("radio", MmiLine.ANTENNA),
    RailEntry("music", MmiLine.NOTE),
    RailEntry("phone", MmiLine.CALL),
    RailEntry("nav", MmiLine.NAV_ARROW),
)
private const val RAIL_ICON = 60f

/** Reference top bar (grey gradient with its hairline), full width, 86 px tall. */
@Composable
internal fun RefTopBar(k: Dp, width: Dp) {
    Image(
        painterResource(R.drawable.home_topbar), null, contentScale = ContentScale.FillBounds,
        modifier = Modifier.size(width, k * TOP_BAR),
    )
}

/** Reference scale: dp per pixel of the 1920-wide reference screen. */
internal fun refScale(width: Dp): Dp = width / REF_W

/** Height of the reference top bar for scale [k]. */
internal fun refTopBarHeight(k: Dp): Dp = k * TOP_BAR
internal fun refRailWidth(k: Dp): Dp = k * RAIL_W

/**
 * Reference rail: five cells (Home, Radio, Media, Telephone, Navigation) with 2 px black
 * gaps, from 105 px down to 22 px above the bottom; [active] lights its cell.
 */
@Composable
internal fun RefRail(k: Dp, height: Dp, active: String?, onOpen: (String) -> Unit) {
    val railTop = k * RAIL_TOP
    val cellH = (height - railTop - k * RAIL_BOTTOM_GAP - k * RAIL_GAP * 4) / 5
    HOME_RAIL.forEachIndexed { i, entry ->
        Box(
            Modifier.offset(0.dp, railTop + (cellH + k * RAIL_GAP) * i).size(k * RAIL_W, cellH)
                .pressable { onOpen(entry.key) },
        ) {
            RailCell(k, entry.key == active)
            MmiLineIcon(
                entry.icon, if (entry.key == active) Color.White else Color(0xFFF2F2F2),
                Modifier.align(Alignment.Center).offset(x = -(k * 2f)).size(k * RAIL_ICON),
            )
        }
    }
}

/**
 * Home tiles, laid out to the reference screen inside the content area (right of
 * the rail, below the top bar): two pages of 4 × 2 tiles and the page dots. The knob
 * moves [selected] (outlined; nothing is outlined until the knob is used) and turns
 * the page; a press opens it.
 */
@Composable
internal fun HomeGrid(selected: String, k: Dp, onOpen: (String) -> Unit, modifier: Modifier = Modifier) {
    val pages = (HOME_TILES.size + PER_PAGE - 1) / PER_PAGE
    val pager = rememberPagerState { pages }
    LaunchedEffect(selected) {
        val i = HOME_TILES.indexOfFirst { it.key == selected }
        if (i >= 0 && i / PER_PAGE != pager.currentPage) pager.animateScrollToPage(i / PER_PAGE)
    }
    // This area starts at x = 180 (rail) and y = 86 (top bar) of the reference screen.
    val left = RAIL_W
    val top = TOP_BAR
    BoxWithConstraints(modifier.fillMaxSize()) {
        val h = maxHeight
        fun x(v: Float): Dp = k * v
        val tileTop = x(TILE_TOP - top)
        val tileH = (h - tileTop - x(TILE_BOTTOM_GAP) - x(ROW_GAP)) / 2
        HorizontalPager(pager, Modifier.fillMaxSize(), beyondViewportPageCount = 1) { page ->
            Box(Modifier.fillMaxSize()) {
                HOME_TILES.drop(page * PER_PAGE).take(PER_PAGE).forEachIndexed { i, tile ->
                    val row = i / 4
                    val col = i % 4
                    HomeTileView(
                        tile, tile.key == selected, k, x(TILE_W[col]), tileH,
                        Modifier.offset(x(TILE_X[col] - left), tileTop + (tileH + x(ROW_GAP)) * row),
                    ) { onOpen(tile.key) }
                }
            }
        }
        // Page dots: 28 × 11 (active, white) and 27 × 11 (grey), 26 px from the bottom.
        val dotY = h - x(26f)
        repeat(pages) { i ->
            Box(
                Modifier.offset(x((if (i == 0) 1009f else 1062f + (i - 1) * 53f) - left), dotY)
                    .size(x(if (i == 0) 28f else 27f), x(11f))
                    .clip(RoundedCornerShape(x(2f)))
                    .background(if (i == pager.currentPage) Color.White else Color(0xFF7E7E7E)),
            )
        }
    }
}

/** Rail cell background: dark-to-grey across, 1 px highlight on top, bright 2 px right edge. */
@Composable
private fun RailCell(k: Dp, active: Boolean) {
    val d = LocalDensity.current
    Canvas(Modifier.fillMaxSize()) {
        val px = with(d) { k.toPx() }
        if (active) drawRect(Brush.horizontalGradient(0f to Color(0xFF2A2A2A), 0.5f to Color(0xFF4A4A4A), 1f to Color(0xFF6A6A6A)))
        else drawRect(Brush.horizontalGradient(0f to Color(0xFF000000), 0.5f to Color(0xFF1E1E1E), 1f to Color(0xFF3A3A3A)))
        drawRect(Color(0xFFBBBBBB), size = Size(size.width, px.coerceAtLeast(1f)))
        drawRect(if (active) Color.White else Color(0xFFD8D8D8), topLeft = Offset(size.width - 2 * px, 0f), size = Size(2 * px, size.height))
    }
}

@Composable
private fun HomeTileView(tile: HomeTile, selected: Boolean, k: Dp, w: Dp, h: Dp, modifier: Modifier, onClick: () -> Unit) {
    val d = LocalDensity.current
    val stripH = k * STRIP_H
    Box(
        modifier.size(w, h)
            .then(if (selected) Modifier.border(k * 3f, Color.White.copy(alpha = 0.85f), RectangleShape) else Modifier)
            .pressable(onClick),
    ) {
        // Side lines fading in from black at the top to grey at the bottom, then the 3 px underline.
        Canvas(Modifier.fillMaxSize()) {
            val fw = with(d) { (k * FRAME_W).toPx() }
            val ul = with(d) { (k * 3f).toPx() }
            val fade = Brush.verticalGradient(listOf(Color.Black, Color(0xFF969696)), 0f, size.height)
            drawRect(fade, Offset(0f, 0f), Size(fw, size.height))
            drawRect(fade, Offset(size.width - fw, 0f), Size(fw, size.height))
            drawRect(tile.underline, Offset(0f, size.height - ul), Size(size.width, ul))
        }
        // Artwork, centred in the space above the label.
        val artTop = ((h - stripH - k * ART_H) / 2).coerceAtLeast(0.dp)
        Image(
            painterResource(tile.art), null, contentScale = ContentScale.Fit,
            modifier = Modifier.offset(k * FRAME_W, artTop).size(k * ART_W, k * ART_H),
        )
        // Label: outline icon + upper-case Roboto, centred in the strip above the underline.
        Row(
            Modifier.align(Alignment.BottomCenter).height(stripH).padding(bottom = k * 6f),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            MmiLineIcon(tile.icon, Color.White, Modifier.size(k * 36f))
            Spacer(Modifier.width(k * 12f))
            Text(
                tile.label, color = Color.White, fontFamily = MmiFont, fontSize = with(d) { (k * 34f).toSp() },
                maxLines = 1,
            )
        }
    }
}
