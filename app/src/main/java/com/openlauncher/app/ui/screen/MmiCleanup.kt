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
import com.openlauncher.app.util.CarLinkScan
import com.openlauncher.app.util.Root
import com.openlauncher.app.util.UnitApp
import com.openlauncher.app.util.UnitCleanup
import com.openlauncher.app.util.Verdict
import com.openlauncher.app.util.WifiShare
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
            ChromePill("Export vendor apps", {
                scope.launch {
                    message = "Copying apps…"
                    val n = UnitCleanup.exportApks(context) { done, total -> message = "Copying apps… $done / $total" }
                    message = if (n > 0) "Copied $n apps to Download/mmi-apks — move that folder to a USB stick and send it over."
                    else "Couldn't copy — the unit's storage refused."
                }
            }, MmiLine.OPEN, Modifier.fillMaxWidth())
            // Car link scan: learns the vendor's broadcasts on the unit, saved as one small text file.
            var linkStep by remember { mutableIntStateOf(if (CarLinkScan.listening) 2 else 0) }
            var heard by remember { mutableIntStateOf(0) }
            LaunchedEffect(linkStep) {
                while (linkStep == 2) { heard = CarLinkScan.eventCount(); delay(1000) }
            }
            ChromePill(
                when (linkStep) {
                    1 -> "Reading vendor apps…"
                    2 -> "Stop and save ($heard heard)"
                    else -> "Car link scan"
                },
                {
                    when (linkStep) {
                        0 -> scope.launch {
                            linkStep = 1
                            CarLinkScan.scan(context) { done, total -> message = "Reading vendor apps… $done / $total" }
                            val n = CarLinkScan.startListening(context)
                            linkStep = 2
                            message = "Listening for $n car messages. Now press every steering-wheel button, play, pause and skip a " +
                                "Bluetooth song, connect and disconnect your phone, open the radio and change station. Then come back and tap Stop and save."
                        }
                        2 -> scope.launch {
                            CarLinkScan.stopListening(context)
                            linkStep = 0
                            message = CarLinkScan.save(context)?.let { "Saved mmi-carlink.txt to $it — send me that one file." }
                                ?: "Couldn't save the file — the unit's storage refused."
                        }
                        else -> Unit
                    }
                },
                MmiLine.HISTORY, Modifier.fillMaxWidth(),
            )
            ChromePill("Export car apps (for upload)", {
                scope.launch {
                    message = "Packing the car apps…"
                    val zips = com.openlauncher.app.util.CarAppsExport.export(context) { message = it }
                    message = zips?.let {
                        "Ready: ${it.joinToString()}. Upload them from the browser with the MMI file picker (choose files → MMI files)."
                    } ?: "Couldn't read the car apps."
                }
            }, MmiLine.OPEN, Modifier.fillMaxWidth())
            // One tap: export the car apps and upload everything to the developer's GitHub.
            // One tap: export the car apps and upload everything to the developer's GitHub.
            // Runs in MMI's background, so leaving this page doesn't stop it.
            val sendStatus by com.openlauncher.app.util.DevUpload.status.collectAsState()
            ChromePill(if (com.openlauncher.app.util.DevUpload.running) "Sending… (keeps going in background)" else "Send to developer", {
                val app = context.applicationContext
                com.openlauncher.app.util.DevUpload.start(app) {
                    com.openlauncher.app.util.CarAppsExport.export(app) { s -> com.openlauncher.app.util.DevUpload.say(s) }
                    UnitCleanup.writeScripts(app, list, selected)
                }
            }, MmiLine.OPEN, Modifier.fillMaxWidth())
            sendStatus?.let { Text(it, color = MmiCyan, fontFamily = MmiFont, fontSize = 14.sp) }
            // Probe: may MMI read the car library directly (voltage, doors, radar, TPMS)?
            ChromePill("Test car data access (30 s)", {
                scope.launch {
                    message = com.openlauncher.app.util.TwDirect.probe { message = it } +
                        "  ·  MMI Car Core: " + com.openlauncher.app.util.CarCore.status
                }
            }, MmiLine.HISTORY, Modifier.fillMaxWidth())
            // Wi-Fi share: a phone on the same hotspot downloads the report files from the unit.
            var sharing by remember { mutableStateOf(WifiShare.running) }
            ChromePill(if (sharing) "Stop sharing over Wi-Fi" else "Share files over Wi-Fi", {
                if (sharing) {
                    WifiShare.stop()
                    sharing = false
                    message = null
                } else scope.launch {
                    val ip = WifiShare.address()
                    if (ip == null) {
                        message = "Connect the unit to your phone's hotspot first (Settings → Wi-Fi), then tap again."
                        return@launch
                    }
                    val problem = WifiShare.start(context)
                    sharing = WifiShare.running
                    message = if (problem == null) {
                        "Sharing. On your phone (same hotspot), type exactly:  http://$ip:${WifiShare.port}  " +
                            "— on this unit's browser: http://127.0.0.1:${WifiShare.port}"
                    } else problem
                }
            }, MmiLine.OPEN, Modifier.fillMaxWidth())
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
