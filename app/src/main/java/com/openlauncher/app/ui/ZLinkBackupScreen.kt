package com.openlauncher.app.ui

import android.widget.Toast
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.openlauncher.app.util.ZLinkBackup
import kotlinx.coroutines.launch

@Composable
fun ZLinkBackupScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var statusText by remember { mutableStateOf("Ready to backup.") }
    var isRunning by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp)
    ) {
        Button(
            onClick = {
                if (!isRunning) {
                    isRunning = true
                    statusText = "Starting..."
                    scope.launch {
                        try {
                            ZLinkBackup.performBackup(context) { status ->
                                statusText = status
                            }
                            Toast.makeText(context, "Backup Complete! Check Downloads folder.", Toast.LENGTH_LONG).show()
                        } catch (e: Exception) {
                            statusText = "Error: ${e.message}"
                        } finally {
                            isRunning = false
                        }
                    }
                }
            },
            enabled = !isRunning
        ) {
            Text(if (isRunning) "Running Backup..." else "Start ZLink Backup")
        }

        Spacer(modifier = Modifier.height(24.dp))
        
        Text(text = statusText)
    }
}
