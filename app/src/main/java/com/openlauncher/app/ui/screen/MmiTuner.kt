package com.openlauncher.app.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openlauncher.app.util.Root
import com.openlauncher.app.util.SystemTuner
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** System tuning: memory, app compilation and root-only kernel tweaks, with live memory and temperature. */
@Composable
internal fun MmiTunerPage() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val root = remember { Root.available() }
    var message by remember { mutableStateOf<String?>(null) }
    var memory by remember { mutableStateOf(SystemTuner.memoryLine(context)) }
    var temp by remember { mutableStateOf(SystemTuner.cpuTemperature()) }
    LaunchedEffect(Unit) {
        while (true) {
            memory = SystemTuner.memoryLine(context)
            temp = SystemTuner.cpuTemperature()
            delay(3000)
        }
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 48.dp, vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(memory, color = MmiInk, fontFamily = MmiFont, fontSize = 18.sp, modifier = Modifier.weight(1f))
            temp?.let {
                Text(
                    "CPU $it °C", fontFamily = MmiFont, fontSize = 18.sp,
                    color = when { it >= 85 -> Color(0xFFFF5252); it >= 70 -> Color(0xFFFFC93C); else -> Color(0xFF4CD964) },
                )
            }
        }
        if ((temp ?: 0) >= 85) {
            Text("Very hot — the unit slows itself down. Shade the screen or improve airflow behind the dash.",
                color = Color(0xFFFF5252), fontFamily = MmiFont, fontSize = 14.sp)
        }
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            ChromePill("Free memory now", {
                scope.launch {
                    val freed = SystemTuner.freeMemory(context)
                    message = "Freed about $freed MB."
                    memory = SystemTuner.memoryLine(context)
                }
            }, MmiLine.CLOSE)
            if (root) {
                ChromePill("Optimise apps (few minutes)", {
                    scope.launch {
                        message = "Optimising… the screen may be slower until it finishes."
                        message = if (SystemTuner.compileApps(context)) "Done — MMI is fully pre-compiled." else "Root refused."
                    }
                }, MmiLine.STAR)
            }
        }
        message?.let { Text(it, color = MmiCyan, fontFamily = MmiFont, fontSize = 14.sp, modifier = Modifier.padding(top = 8.dp)) }

        Text(
            "ROOT TWEAKS", color = MmiCyan, fontFamily = MmiFont, fontSize = 13.sp, letterSpacing = 2.sp,
            modifier = Modifier.padding(top = 18.dp, bottom = 4.dp),
        )
        if (!root) {
            Text("Need root. Nothing here changes the system permanently: switched-on tweaks are re-applied at each start.",
                color = MmiDim, fontFamily = MmiFont, fontSize = 15.sp)
        }
        SystemTuner.Tweak.entries.forEach { t ->
            var on by remember { mutableStateOf(SystemTuner.isOn(context, t)) }
            Row(
                Modifier.fillMaxWidth().height(64.dp).pressable {
                    if (!root) return@pressable
                    on = !on
                    SystemTuner.setOn(context, t, on)
                    if (on) scope.launch { message = if (SystemTuner.applyEnabled(context)) "${t.label}: on" else "Root refused." }
                    else message = "${t.label}: off after the next restart."
                }.padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(t.label, color = if (root) MmiInk else MmiDim, fontFamily = MmiFont, fontSize = 18.sp)
                    Text(t.detail, color = MmiDim, fontFamily = MmiFont, fontSize = 13.sp)
                }
                Box(
                    Modifier.size(width = 48.dp, height = 26.dp).clip(RoundedCornerShape(13.dp))
                        .background(if (on) MmiCyan.copy(alpha = 0.55f) else Color(0xFF2A2E34)),
                    contentAlignment = if (on) Alignment.CenterEnd else Alignment.CenterStart,
                ) { Box(Modifier.padding(3.dp).size(20.dp).clip(RoundedCornerShape(10.dp)).background(Color(0xFFE6E9EC))) }
            }
            Box(Modifier.fillMaxWidth().height(1.dp).background(Color(0xFF1E2125)))
        }
    }
}
