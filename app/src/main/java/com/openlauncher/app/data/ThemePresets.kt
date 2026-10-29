package com.openlauncher.app.data

import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

/**
 * A named accent + surface pairing for each mode. Each preset's light and dark
 * surfaces are independently tinted toward the theme's character — not a shared
 * neutral with only the accent swapped, and not the dark accent simply dropped
 * onto white. Every accent/surface pair here was contrast-checked (WCAG) before
 * landing: see the design artifact this was picked from for the exact ratios.
 *
 * Themes may also carry a gradient endpoint. When set, the resolved theme exposes
 * a Brush that the home screen uses as its background in place of the flat color
 * (widgets still sit on the flat surface, which stays opaque under them).
 */
data class DashboardThemeDef(
    val id: String,
    val label: String,
    val darkAccent: Long,
    val darkBg: Long,
    val darkInk: Long,
    val lightAccent: Long,
    val lightBg: Long,
    val darkGradientEnd: Long? = null,
    val lightGradientEnd: Long? = null,
    val gradient: GradientDirection? = null,
)

val DASHBOARD_THEMES = listOf(
    DashboardThemeDef(
        id = "ignition", label = "Ignition",
        darkAccent  = 0xFFFF6B4AL, darkBg  = 0xFF160F0DL, darkInk = 0xFFF3E9E4L,
        lightAccent = 0xFFB23814L, lightBg = 0xFFF4EAE1L
    ),
    DashboardThemeDef(
        id = "amber", label = "Amber Cluster",
        darkAccent  = 0xFFE8A93DL, darkBg  = 0xFF171008L, darkInk = 0xFFF3ECDDL,
        lightAccent = 0xFF7C4E0CL, lightBg = 0xFFF2E6CEL
    ),
    DashboardThemeDef(
        id = "cobalt", label = "Cobalt Run",
        darkAccent  = 0xFF5B7CFAL, darkBg  = 0xFF0A0E17L, darkInk = 0xFFE7EAF5L,
        lightAccent = 0xFF3247B5L, lightBg = 0xFFE4E9F5L
    ),
    DashboardThemeDef(
        id = "verdigris", label = "Verdigris",
        darkAccent  = 0xFF2FBFA0L, darkBg  = 0xFF0B1613L, darkInk = 0xFFE4F2EDL,
        lightAccent = 0xFF0E6E58L, lightBg = 0xFFDCEBE6L
    ),
    DashboardThemeDef(
        id = "plum", label = "Plum Static",
        darkAccent  = 0xFFC86BE0L, darkBg  = 0xFF150A16L, darkInk = 0xFFF0E4F1L,
        lightAccent = 0xFF7A1F94L, lightBg = 0xFFEFE1EEL
    ),
    DashboardThemeDef(
        id = "blueprint", label = "Blueprint",
        darkAccent  = 0xFFB8E4FFL, darkBg  = 0xFF0B1D30L, darkInk = 0xFFE4F3FFL,
        lightAccent = 0xFF1B3A6BL, lightBg = 0xFFF2EFE4L
    ),
    DashboardThemeDef(
        id = "circuit", label = "Circuit",
        darkAccent  = 0xFFE8B84DL, darkBg  = 0xFF0A1F17L, darkInk = 0xFFEAF3EEL,
        lightAccent = 0xFF7A4A12L, lightBg = 0xFFF0EDE4L
    ),
    DashboardThemeDef(
        id = "instrument", label = "Instrument Cluster",
        darkAccent  = 0xFFFFA23DL, darkBg  = 0xFF14100AL, darkInk = 0xFFF3ECDDL,
        lightAccent = 0xFF1F2A44L, lightBg = 0xFFF0E6D2L
    ),
    DashboardThemeDef(
        id = "mbux", label = "MBUX",
        darkAccent  = 0xFF5BC8FFL, darkBg  = 0xFF01060EL, darkInk = 0xFFF2F7FCL,
        lightAccent = 0xFF1E6FB3L, lightBg = 0xFFE4EEF8L,
        darkGradientEnd  = 0xFF0A3E7AL,
        lightGradientEnd = 0xFFCDE0F2L,
        gradient = GradientDirection.TOP_TO_BOTTOM,
    ),
    DashboardThemeDef(
        id = "mmi", label = "MMI",
        darkAccent  = 0xFF3EC6F0L, darkBg  = 0xFF0B0C0EL, darkInk = 0xFFF0F2F4L,
        lightAccent = 0xFF1E7FA8L, lightBg = 0xFFE6E8EBL
    ),
)

data class ResolvedTheme(
    val accent: Color,
    val background: Color,
    val ink: Color?,  // non-null only in dark mode — light mode keeps the app's existing neutral ink
    val backgroundBrush: Brush? = null,
)

fun resolveDashboardTheme(themeId: String, isDayMode: Boolean): ResolvedTheme? {
    val def = DASHBOARD_THEMES.find { it.id == themeId } ?: return null
    val base = if (isDayMode) {
        ResolvedTheme(accent = Color(def.lightAccent), background = Color(def.lightBg), ink = null)
    } else {
        ResolvedTheme(accent = Color(def.darkAccent), background = Color(def.darkBg), ink = Color(def.darkInk))
    }
    val endLong = if (isDayMode) def.lightGradientEnd else def.darkGradientEnd
    if (endLong == null || def.gradient == null) return base
    val endColor = Color(endLong)
    // The gradient end is the lit side (top / left / centre), fading into the base surface.
    val stops = listOf(endColor, base.background)
    val brush = when (def.gradient) {
        GradientDirection.TOP_TO_BOTTOM -> Brush.verticalGradient(stops)
        GradientDirection.LEFT_TO_RIGHT -> Brush.horizontalGradient(stops)
        GradientDirection.DIAGONAL -> Brush.linearGradient(stops)
        GradientDirection.RADIAL -> Brush.radialGradient(stops)
    }
    return base.copy(backgroundBrush = brush)
}
