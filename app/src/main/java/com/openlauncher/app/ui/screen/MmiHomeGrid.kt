package com.openlauncher.app.ui.screen

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.openlauncher.app.R

/** One home tile: artwork (or a drawn glyph), label with its small line icon, coloured underline. */
internal class HomeTile(
    val key: String,
    val label: String,
    val line: MmiLine,
    val art: Int? = null,
    val glyph: MmiGlyph? = null,
    val underline: List<Color>,
)

/** Two pages of eight, in knob order. */
internal val HOME_TILES = listOf(
    HomeTile("video", "Video", MmiLine.FILM, R.drawable.mmi_tile_video, underline = listOf(Color(0xFFE53935), Color(0xFFFF7A2E))),
    HomeTile("music", "Music", MmiLine.NOTE, R.drawable.mmi_tile_music, underline = listOf(Color(0xFFFF7A2E), Color(0xFFFFB13C))),
    HomeTile("phone", "Phone", MmiLine.CALL, R.drawable.mmi_tile_phone, underline = listOf(Color(0xFF9BE15D), Color(0xFFE8E04A))),
    HomeTile("nav", "Navigation", MmiLine.NAV_ARROW, R.drawable.mmi_tile_navigation, underline = listOf(Color(0xFF2F80ED), Color(0xFF56CCF2))),
    HomeTile("phonelink", "PhoneLink", MmiLine.LINK, R.drawable.mmi_tile_phonelink, underline = listOf(Color(0xFF3FD07A), Color(0xFFE8E04A), Color(0xFFE53935))),
    HomeTile("carinfo", "Car Info", MmiLine.CAR, R.drawable.mmi_tile_carinfo, underline = listOf(Color(0xFFE53935), Color(0xFFB71C1C))),
    HomeTile("weather", "Weather", MmiLine.CLOUD, R.drawable.mmi_tile_weather, underline = listOf(Color(0xFF3FD07A), Color(0xFF29C5F6))),
    HomeTile("settings", "Settings", MmiLine.GEAR, R.drawable.mmi_tile_settings, underline = listOf(Color(0xFFE8E04A), Color(0xFFFFB13C))),
    HomeTile("radio", "Radio", MmiLine.ANTENNA, glyph = MmiGlyph.RADIO, underline = listOf(Color(0xFF9B51E0), Color(0xFF56CCF2))),
    HomeTile("apps", "Apps", MmiLine.GRID, glyph = MmiGlyph.APPS, underline = listOf(Color(0xFF29C5F6), Color(0xFF3FD07A))),
    HomeTile("eq", "Equaliser", MmiLine.SPEAKER, glyph = MmiGlyph.GAUGE, underline = listOf(Color(0xFFE040FB), Color(0xFFFF7A2E))),
)

private const val PER_PAGE = 8

/** Left shortcut rail, as on the unit's own home screen. */
private val RAIL_KEYS = listOf("music" to MmiLine.NOTE, "nav" to MmiLine.NAV_ARROW, "phone" to MmiLine.CALL, "settings" to MmiLine.GEAR, "carinfo" to MmiLine.CAR)

/**
 * Home: shortcut rail on the left, two swipeable pages of large tiles. The knob
 * moves [selected] (and turns the page when it crosses over); a press opens it.
 */
@Composable
internal fun HomeGrid(selected: String, onOpen: (String) -> Unit, modifier: Modifier = Modifier) {
    val pages = (HOME_TILES.size + PER_PAGE - 1) / PER_PAGE
    val pager = rememberPagerState { pages }
    LaunchedEffect(selected) {
        val i = HOME_TILES.indexOfFirst { it.key == selected }
        if (i >= 0 && i / PER_PAGE != pager.currentPage) pager.animateScrollToPage(i / PER_PAGE)
    }
    BoxWithConstraints(modifier.fillMaxSize()) {
        val railWidth = maxWidth * 0.094f
        Row(Modifier.fillMaxSize()) {
            Column(Modifier.width(railWidth).fillMaxHeight().padding(top = 18.dp, bottom = 18.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                RAIL_KEYS.forEach { (key, icon) ->
                    Box(
                        Modifier.fillMaxWidth().weight(1f)
                            .background(Brush.horizontalGradient(listOf(Color(0xFF15171A), Color(0xFF2A2D31))))
                            .pressable { onOpen(key) },
                        contentAlignment = Alignment.Center,
                    ) { MmiLineIcon(icon, Color.White, Modifier.size(railWidth * 0.42f)) }
                }
            }
            Column(Modifier.weight(1f).fillMaxHeight()) {
                HorizontalPager(pager, Modifier.weight(1f).fillMaxWidth(), beyondViewportPageCount = 1) { page ->
                    val tiles = HOME_TILES.drop(page * PER_PAGE).take(PER_PAGE)
                    Column(
                        Modifier.fillMaxSize().padding(start = 40.dp, end = 40.dp, top = 18.dp, bottom = 6.dp),
                        verticalArrangement = Arrangement.spacedBy(24.dp),
                    ) {
                        for (row in 0 until 2) {
                            Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(28.dp)) {
                                for (col in 0 until 4) {
                                    val tile = tiles.getOrNull(row * 4 + col)
                                    if (tile == null) Spacer(Modifier.weight(1f))
                                    else HomeTileView(tile, tile.key == selected, Modifier.weight(1f).fillMaxHeight()) { onOpen(tile.key) }
                                }
                            }
                        }
                    }
                }
                // Page dots.
                Row(Modifier.align(Alignment.CenterHorizontally).padding(bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    repeat(pages) { i ->
                        Box(
                            Modifier.size(width = 24.dp, height = 4.dp).clip(RoundedCornerShape(2.dp))
                                .background(if (i == pager.currentPage) Color.White else Color(0xFF5A6068))
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun HomeTileView(tile: HomeTile, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    BoxWithConstraints(
        modifier
            .border(
                if (selected) 2.dp else 1.dp,
                if (selected) Brush.verticalGradient(listOf(Color(0xFFE9ECEF), Color(0xFF8E959E)))
                else Brush.verticalGradient(listOf(Color(0x14FFFFFF), Color(0x40FFFFFF))),
            )
            .pressable(onClick),
    ) {
        val labelSize = with(LocalDensity.current) { (maxHeight * 0.12f).toSp() }
        Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.weight(1f).fillMaxWidth().padding(top = 10.dp, start = 18.dp, end = 18.dp), contentAlignment = Alignment.Center) {
                when {
                    tile.art != null -> Image(painterResource(tile.art), null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
                    tile.glyph != null -> MmiIcon(tile.glyph, Modifier.size(maxHeight * 0.5f))
                }
            }
            Row(Modifier.padding(bottom = 8.dp, top = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                MmiLineIcon(tile.line, Color.White, Modifier.size(maxHeight * 0.13f))
                Spacer(Modifier.width(12.dp))
                Text(tile.label, color = Color.White, fontFamily = MmiFont, fontSize = labelSize, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Box(Modifier.fillMaxWidth().height(3.dp).background(Brush.horizontalGradient(tile.underline)))
        }
    }
}
