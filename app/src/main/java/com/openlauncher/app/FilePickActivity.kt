package com.openlauncher.app

import android.app.Activity
import android.content.ClipData
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import com.openlauncher.app.ui.screen.ChromePill
import com.openlauncher.app.ui.screen.MmiDim
import com.openlauncher.app.ui.screen.MmiFont
import com.openlauncher.app.ui.screen.MmiInk
import com.openlauncher.app.ui.screen.MmiLine
import com.openlauncher.app.ui.screen.MmiPageBg
import com.openlauncher.app.ui.screen.pressable
import com.openlauncher.app.util.WifiShare
import java.io.File

/**
 * File picker for units without a file manager: when a browser asks for a file
 * to upload, MMI offers its own reports (car link scan, package list, scripts).
 */
class FilePickActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val files = collect()
        val multiple = intent.getBooleanExtra(Intent.EXTRA_ALLOW_MULTIPLE, false)
        setContent {
            Column(
                Modifier.fillMaxSize().background(MmiPageBg).padding(horizontal = 48.dp, vertical = 24.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                Text("Choose an MMI file", color = MmiInk, fontFamily = MmiFont, fontSize = 26.sp)
                Spacer(Modifier.height(12.dp))
                if (files.isEmpty()) {
                    Text(
                        "No files yet. In MMI, open Settings → Unit cleanup & speed and run Save PC script or Car link scan first.",
                        color = MmiDim, fontFamily = MmiFont, fontSize = 18.sp,
                    )
                }
                files.forEach { f ->
                    Row(
                        Modifier.fillMaxWidth().height(64.dp).pressable { finishWith(listOf(f)) }.padding(horizontal = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(f.name, color = MmiInk, fontFamily = MmiFont, fontSize = 20.sp, modifier = Modifier.weight(1f))
                        Text("${(f.length() + 1023) / 1024} KB", color = MmiDim, fontFamily = MmiFont, fontSize = 16.sp)
                    }
                    Box(Modifier.fillMaxWidth().height(1.dp).background(Color(0xFF1E2125)))
                }
                Spacer(Modifier.height(16.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    if (multiple && files.size > 1) ChromePill("Send all", { finishWith(files) }, MmiLine.OPEN, Modifier.width(240.dp))
                    ChromePill("Cancel", { setResult(Activity.RESULT_CANCELED); finish() }, MmiLine.CLOSE, Modifier.width(200.dp))
                }
            }
        }
    }

    /** Copies MMI's report files (wherever they were saved) into a folder the file provider can hand out. */
    private fun collect(): List<File> {
        val dir = File(filesDir, "reports").apply { mkdirs() }
        return WifiShare.NAMES.mapNotNull { name ->
            runCatching {
                val input = WifiShare.open(this, name) ?: return@runCatching null
                File(dir, name).also { out -> input.use { i -> out.outputStream().use { i.copyTo(it) } } }
            }.getOrNull()
        }
    }

    private fun finishWith(picked: List<File>) {
        val uris: List<Uri> = picked.map { FileProvider.getUriForFile(this, "$packageName.updates", it) }
        val result = Intent().setData(uris.first()).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        if (uris.size > 1) {
            result.clipData = ClipData.newRawUri(null, uris.first()).apply { uris.drop(1).forEach { addItem(ClipData.Item(it)) } }
        }
        setResult(Activity.RESULT_OK, result)
        finish()
    }
}
