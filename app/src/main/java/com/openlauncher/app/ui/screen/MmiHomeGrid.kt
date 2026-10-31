package com.openlauncher.app.ui.screen

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
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

/**
 * One home tile. Page-one tiles use artwork and label strips cut straight from the
 * reference screen (so logos, their fades, icons, text and underline are exact);
 * the others draw a glyph and label until their artwork is supplied.
 */
internal class HomeTile(
    val key: String,
    val label: String,
    val art: Int? = null,
    val strip: Int? = null,
    val glyph: MmiGlyph? = null,
    val underline: List<Color> = emptyList(),
)

/** Two pages of eight, in knob order. */
internal val HOME_TILES = listOf(
    HomeTile("video", "Video", R.drawable.home_art_video, R.drawable.home_label_video),
    HomeTile("music", "Music", R.drawable.home_art_music, R.drawable.home_label_music),
    HomeTile("phone", "Phone", R.drawable.home_art_phone, R.drawable.home_label_phone),
    HomeTile("nav", "Navigation", R.drawable.home_art_navigation, R.drawable.home_label_navigation),
    HomeTile("phonelink", "PhoneLink", R.drawable.home_art_phonelink, R.drawable.home_label_phonelink),
    HomeTile("carinfo", "Car Info", R.drawable.home_art_carinfo, R.drawable.home_label_carinfo),
    HomeTile("weather", "Weather", R.drawable.home_art_weather, R.drawable.home_label_weather),
    HomeTile("settings", "Settings", R.drawable.home_art_settings, R.drawable.home_label_settings),
    HomeTile("radio", "Radio", glyph = MmiGlyph.RADIO, underline = listOf(Color(0xFF9B51E0), Color(0xFF56CCF2))),
    HomeTile("apps", "Apps", glyph = MmiGlyph.APPS, underline = listOf(Color(0xFF29C5F6), Color(0xFF3FD07A))),
    HomeTile("eq", "Equaliser", glyph = MmiGlyph.GAUGE, underline = listOf(Color(0xFFE040FB), Color(0xFFFF7A2E))),
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

/** Left rail of the home screen, top to bottom, with its cut-out icons (size in ref px). */
private class RailEntry(val key: String, val icon: Int, val w: Float, val h: Float)
private val HOME_RAIL = listOf(
    RailEntry("music", R.drawable.home_rail_music, 51f, 56f),
    RailEntry("nav", R.drawable.home_rail_nav, 46f, 56f),
    RailEntry("phone", R.drawable.home_rail_phone, 60f, 57f),
    RailEntry("settings", R.drawable.home_rail_settings, 59f, 58f),
    RailEntry("carinfo", R.drawable.home_rail_car, 61f, 47f),
)

/**
 * Home, laid out to the reference screen: top bar, five-cell rail, two pages of
 * 4 × 2 tiles and the page dots. The knob moves [selected] (outlined; nothing is
 * outlined until the knob is used) and turns the page; a press opens it.
 */
@Composable
internal fun HomeGrid(selected: String, onOpen: (String) -> Unit, modifier: Modifier = Modifier) {
    val pages = (HOME_TILES.size + PER_PAGE - 1) / PER_PAGE
    val pager = rememberPagerState { pages }
    LaunchedEffect(selected) {
        val i = HOME_TILES.indexOfFirst { it.key == selected }
        if (i >= 0 && i / PER_PAGE != pager.currentPage) pager.animateScrollToPage(i / PER_PAGE)
    }
    BoxWithConstraints(modifier.fillMaxSize().background(Color.Black)) {
        val k = maxWidth / REF_W
        val h = maxHeight
        fun x(v: Float): Dp = k * v

        // Top bar artwork, stretched only along its width (it is the full 1920 wide).
        Image(
            painterResource(R.drawable.home_topbar), null, contentScale = ContentScale.FillBounds,
            modifier = Modifier.size(maxWidth, x(TOP_BAR)),
        )

        // Rail: five cells with 2 px black gaps.
        val railTop = x(RAIL_TOP)
        val cellH = (h - railTop - x(RAIL_BOTTOM_GAP) - x(RAIL_GAP) * 4) / 5
        HOME_RAIL.forEachIndexed { i, entry ->
            Box(
                Modifier.offset(0.dp, railTop + (cellH + x(RAIL_GAP)) * i).size(x(RAIL_W), cellH)
                    .pressable { onOpen(entry.key) },
            ) {
                RailCell(k)
                Image(
                    painterResource(entry.icon), null,
                    modifier = Modifier.align(Alignment.Center).offset(x = -x(2f)).size(x(entry.w), x(entry.h)),
                )
            }
        }

        // Tiles: rows stretch to the screen height; columns are exact.
        val tileTop = x(TILE_TOP)
        val tileH = (h - tileTop - x(TILE_BOTTOM_GAP) - x(ROW_GAP)) / 2
        val pagerLeft = x(RAIL_W)
        HorizontalPager(
            pager,
            modifier = Modifier.offset(x = pagerLeft).size(maxWidth - pagerLeft, h),
            beyondViewportPageCount = 1,
        ) { page ->
            Box(Modifier.fillMaxSize()) {
                HOME_TILES.drop(page * PER_PAGE).take(PER_PAGE).forEachIndexed { i, tile ->
                    val row = i / 4
                    val col = i % 4
                    HomeTileView(
                        tile, tile.key == selected, k, x(TILE_W[col]), tileH,
                        Modifier.offset(x(TILE_X[col]) - pagerLeft, tileTop + (tileH + x(ROW_GAP)) * row),
                    ) { onOpen(tile.key) }
                }
            }
        }

        // Page dots: 28 × 11 (active, white) and 27 × 11 (grey), 26 px from the bottom.
        val dotY = h - x(26f)
        repeat(pages) { i ->
            Box(
                Modifier.offset(x(if (i == 0) 1009f else 1062f + (i - 1) * 53f), dotY)
                    .size(x(if (i == 0) 28f else 27f), x(11f))
                    .clip(RoundedCornerShape(x(2f)))
                    .background(if (i == pager.currentPage) Color.White else Color(0xFF7E7E7E)),
            )
        }
    }
}

/** Rail cell background: dark-to-grey across, 1 px highlight on top, bright 2 px right edge. */
@Composable
private fun RailCell(k: Dp) {
    val d = LocalDensity.current
    Canvas(Modifier.fillMaxSize()) {
        val px = with(d) { k.toPx() }
        drawRect(Brush.horizontalGradient(0f to Color(0xFF000000), 0.5f to Color(0xFF1E1E1E), 1f to Color(0xFF3A3A3A)))
        drawRect(Color(0xFFBBBBBB), size = Size(size.width, px.coerceAtLeast(1f)))
        drawRect(Color(0xFFD8D8D8), topLeft = Offset(size.width - 2 * px, 0f), size = Size(2 * px, size.height))
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
        // Side lines fading in from black at the top to the strip's grey (116/255).
        Canvas(Modifier.fillMaxSize()) {
            val fw = with(d) { (k * FRAME_W).toPx() }
            val bottom = size.height - with(d) { stripH.toPx() }
            val fade = Brush.verticalGradient(listOf(Color.Black, Color(0xFF747474)), 0f, bottom)
            drawRect(fade, Offset(0f, 0f), Size(fw, bottom))
            drawRect(fade, Offset(size.width - fw, 0f), Size(fw, bottom))
            if (tile.strip == null) {
                // Placeholder tiles: continue the lines and draw the 3 px underline.
                val grey = Brush.verticalGradient(listOf(Color(0xFF747474), Color(0xFF969696)), bottom, size.height)
                drawRect(grey, Offset(0f, bottom), Size(fw, size.height - bottom))
                drawRect(grey, Offset(size.width - fw, bottom), Size(fw, size.height - bottom))
                val ul = with(d) { (k * 3f).toPx() }
                drawRect(Brush.horizontalGradient(tile.underline), Offset(0f, size.height - ul), Size(size.width, ul))
            }
        }
        // Artwork block, centred in the space above the label strip.
        val artTop = ((h - stripH - k * ART_H) / 2).coerceAtLeast(0.dp)
        when {
            tile.art != null -> Image(
                painterResource(tile.art), null, contentScale = ContentScale.FillBounds,
                modifier = Modifier.offset(k * FRAME_W, artTop).size(k * ART_W, k * ART_H),
            )
            tile.glyph != null -> MmiIcon(
                tile.glyph,
                Modifier.align(Alignment.TopCenter).offset(y = artTop + k * 25f).size(k * 150f),
            )
        }
        if (tile.strip != null) {
            Image(
                painterResource(tile.strip), null, contentScale = ContentScale.FillBounds,
                modifier = Modifier.align(Alignment.BottomStart).size(w, stripH),
            )
        } else {
            val labelSize = with(d) { (k * 40f).toSp() }
            Text(
                tile.label, color = Color.White, fontFamily = MmiFont, fontSize = labelSize,
                modifier = Modifier.align(Alignment.BottomCenter).offset(y = -(k * 10f)),
            )
        }
    }
}
