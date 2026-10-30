package com.openlauncher.app.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openlauncher.app.data.CarPrefs
import com.openlauncher.app.util.Root
import com.openlauncher.app.util.UnitApp
import com.openlauncher.app.util.UnitCleanup
import com.openlauncher.app.util.Verdict
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Unit cleanup & speed: everything installed on the head unit, sorted into what
 * the car needs and what it doesn't. Tick what to switch off, then apply with
 * root or save an ADB script for a PC. Nothing is deleted; every step can be undone.
 */
@Composable
internal fun MmiCleanupPage() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var apps by remember { mutableStateOf<List<UnitApp>?>(null) }
    var selected by remember { mutableStateOf<Set<String>>(emptySet()) }
    var message by remember { mutableStateOf<String?>(null) }
    var refresh by remember { mutableIntStateOf(0) }
    val root = remember { Root.available() }
    var tweaks by remember { mutableStateOf(UnitCleanup.speedTweaksApplied(context)) }
    val prefs = remember(context) { CarPrefs.get(context) }
    val overlay by prefs.perfOverlay.collectAsState()

    LaunchedEffect(refresh) {
        val list = UnitCleanup.scan(context)
        apps = list
        // Pre-tick what is clearly not needed and still switched on.
        if (refresh == 0) selected = list.filter { it.verdict == Verdict.OFF && it.enabled }.map { it.pkg }.toSet()
    }

    Row(Modifier.fillMaxSize().padding(horizontal = 32.dp, vertical = 6.dp)) {
        // Left: actions.
        // Scrolls so every button stays reachable on 720 px tall screens.
        Column(
            Modifier.width(300.dp).fillMaxHeight().verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            val list = apps.orEmpty()
            val on = list.count { it.enabled }
            val mem = list.filter { it.pkg in selected }.sumOf { it.memoryMb ?: 0 }
            Text("$on apps running on this unit", color = MmiInk, fontFamily = MmiFont, fontSize = 18.sp)
            Text(
                "${selected.size} ticked to switch off" + (if (mem > 0) " · frees ~$mem MB" else ""),
                color = MmiDim, fontFamily = MmiFont, fontSize = 15.sp,
            )
            if (root) {
                ChromePill("Switch off ticked (root)", {
                    scope.launch {
                        val off = selected.filter { p -> list.any { it.pkg == p && it.enabled } }
                        message = UnitCleanup.applyWithRoot(context, off)?.let { r ->
                            "Switched off ${r.done}." + (if (r.failed.isNotEmpty()) " Couldn't switch off: ${r.failed.joinToString()}." else "") +
                                " Restart the unit to feel the difference."
                        } ?: "Root refused the change."
                        refresh++
                    }
                }, MmiLine.CLOSE, Modifier.fillMaxWidth())
                if (UnitCleanup.switchedOff(context).isNotEmpty()) {
                    ChromePill("Undo: turn everything back on", {
                        scope.launch {
                            message = UnitCleanup.undoWithRoot(context)?.let { r ->
                                "Turned ${r.done} back on." + (if (r.failed.isNotEmpty()) " Still off: ${r.failed.joinToString()}." else "")
                            } ?: "Root refused the change."
                            refresh++
                        }
                    }, MmiLine.HISTORY, Modifier.fillMaxWidth())
                }
            }
            ChromePill("Save PC script", {
                scope.launch {
                    message = UnitCleanup.writeScripts(context, list, selected)?.let { where ->
                        "Saved mmi-packages.txt, mmi-cleanup.bat/.sh and the undo scripts to $where"
                    } ?: "Couldn't save the files — the unit's storage refused."
                }
            }, MmiLine.OPEN, Modifier.fillMaxWidth())
            ChromePill(if (tweaks) "Speed settings: on · undo" else "Apply speed settings", {
                scope.launch {
                    if (tweaks) {
                        tweaks = !UnitCleanup.revertSpeedTweaks(context) || UnitCleanup.speedTweaksApplied(context)
                        message = if (!tweaks) "Speed settings restored to how they were." else "Couldn't restore — needs root or the PC script grant."
                    } else {
                        tweaks = UnitCleanup.applySpeedTweaks(context) && UnitCleanup.speedTweaksApplied(context)
                        if (!tweaks) message = "Needs root, or run the PC script once (it grants MMI the right to do this)."
                    }
                }
            }, MmiLine.STAR, Modifier.fillMaxWidth())
            // One tap: export the car apps and upload everything to the developer's private repo.
            // Runs in MMI's background, so leaving this page doesn't stop it.
            val sendStatus by com.openlauncher.app.util.DevUpload.status.collectAsState()
            ChromePill(if (com.openlauncher.app.util.DevUpload.running) "Sending… (keeps going in background)" else "Send to developer", {
                val app = context.applicationContext
                com.openlauncher.app.util.DevUpload.start(app) {
                    com.openlauncher.app.util.CarAppsExport.export(app) { s -> com.openlauncher.app.util.DevUpload.say(s) }
                    java.io.File(com.openlauncher.app.util.CarAppsExport.dir(app), "mmi-packages.txt").writeText(UnitCleanup.packageList(list))
                }
            }, MmiLine.OPEN, Modifier.fillMaxWidth())
            sendStatus?.let { Text(it, color = MmiCyan, fontFamily = MmiFont, fontSize = 14.sp) }
            Text(
                "Car link: " + com.openlauncher.app.util.CarCore.status,
                color = MmiDim, fontFamily = MmiFont, fontSize = 13.sp,
            )
            ChromePill(if (overlay) "Performance readout: on" else "Performance readout: off", {
                prefs.setPerfOverlay(!overlay)
            }, MmiLine.HISTORY, Modifier.fillMaxWidth())
            message?.let { Text(it, color = MmiCyan, fontFamily = MmiFont, fontSize = 14.sp) }
            Spacer(Modifier.height(8.dp))
            Text(
                "Keep = the car's own software (radio, steering-wheel keys, Bluetooth, camera, CAN box). " +
                    "Unsure items stay on unless you tick them.",
                color = MmiDim, fontFamily = MmiFont, fontSize = 13.sp,
            )
        }
        Spacer(Modifier.width(24.dp))
        // Right: the list.
        val list = apps
        if (list == null) {
            Text("Scanning the unit…", color = MmiDim, fontFamily = MmiFont, fontSize = 18.sp)
        } else {
            LazyColumn(Modifier.weight(1f).fillMaxHeight()) {
                Verdict.entries.forEach { verdict ->
                    val group = list.filter { it.verdict == verdict }
                    if (group.isEmpty()) return@forEach
                    item(key = verdict.name) {
                        Text(
                            "${verdict.label.uppercase()} · ${group.size}",
                            color = when (verdict) {
                                Verdict.KEEP -> Color(0xFF4CD964)
                                Verdict.OFF -> Color(0xFFFF7A2E)
                                Verdict.UNSURE -> Color(0xFFFFC93C)
                            },
                            fontFamily = MmiFont, fontSize = 13.sp, letterSpacing = 1.sp,
                            modifier = Modifier.padding(top = 12.dp, bottom = 4.dp),
                        )
                    }
                    items(group, key = { it.pkg }) { app ->
                        val ticked = app.pkg in selected
                        Row(
                            Modifier.fillMaxWidth().height(54.dp).pressable {
                                if (app.verdict == Verdict.KEEP) return@pressable
                                selected = if (ticked) selected - app.pkg else selected + app.pkg
                            }.padding(horizontal = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Box(
                                Modifier.size(20.dp).clip(RoundedCornerShape(3.dp))
                                    .background(if (ticked) Color(0xFFFF7A2E) else Color(0xFF2A2E34)),
                            )
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(
                                    app.label + if (!app.enabled) "  (already off)" else "",
                                    color = if (app.enabled) MmiInk else MmiDim, fontFamily = MmiFont, fontSize = 16.sp,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    "${app.pkg} · ${app.reason}",
                                    color = MmiDim, fontFamily = MmiFont, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                )
                            }
                            app.memoryMb?.let { Text("$it MB", color = MmiDim, fontFamily = MmiFont, fontSize = 13.sp) }
                        }
                    }
                }
            }
        }
    }
}
