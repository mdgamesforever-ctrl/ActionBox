package com.futurepath.actionbox.ui.settings

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import com.futurepath.actionbox.diagnostics.CrashLogger

/**
 * Debug-tool screen (reached from Settings' "Debug tools" section) showing the last recorded
 * crash — see [CrashLogger] — read directly via the app's own file access. Added because
 * wireless adb debugging is exactly the kind of thing that's unreliable precisely when you need
 * it (mid-crash-hunting), so this needed a path that doesn't depend on adb working at all: Copy
 * and Share both act on the exact same text shown on screen.
 */
@Composable
fun CrashLogScreen() {
    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current
    val crashText = remember { CrashLogger.readLastCrash(context) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        if (crashText == null) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(32.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "No crash has been recorded since this app was installed (or since its " +
                        "data was last cleared).",
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        } else {
            Text(
                text = "A copy of every recorded crash is also saved directly to your phone's " +
                    "Downloads folder (as ActionBox_crash_<timestamp>.txt) — open it with any " +
                    "file manager if you'd rather not use Copy/Share below.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { clipboardManager.setText(AnnotatedString(crashText)) }) {
                    Text("Copy")
                }
                Button(onClick = { shareCrashLog(context, crashText) }) {
                    Text("Share")
                }
            }
            Spacer(modifier = Modifier.height(12.dp))
            SelectionContainer(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
            ) {
                Text(text = crashText, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

private fun shareCrashLog(context: Context, crashText: String) {
    val shareIntent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, crashText)
    }
    context.startActivity(Intent.createChooser(shareIntent, "Share crash log"))
}
