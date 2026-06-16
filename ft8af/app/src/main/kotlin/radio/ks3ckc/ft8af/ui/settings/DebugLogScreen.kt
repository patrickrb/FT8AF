package radio.ks3ckc.ft8af.ui.settings

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.FileProvider
import com.k1af.ft8af.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import radio.ks3ckc.ft8af.theme.Accent
import radio.ks3ckc.ft8af.theme.BgApp
import radio.ks3ckc.ft8af.theme.BgSurface2
import radio.ks3ckc.ft8af.theme.TextFaint
import radio.ks3ckc.ft8af.theme.TextMuted
import radio.ks3ckc.ft8af.theme.TextPrimary
import java.io.File

/**
 * In-app log viewer surfaced from Settings -> About -> Debug (only visible
 * after the user taps the version 7 times to unlock debug mode).
 *
 * Default content is the tail of /Android/data/.../files/debug.log, which the
 * app writes via GeneralVariables.fileLog(). The Logcat toggle additionally
 * captures the running app's own logcat output for richer diagnostics during
 * an active repro.
 */
@Composable
fun DebugLogScreen(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val debugLogFile = remember {
        context.getExternalFilesDir(null)?.let { File(it, "debug.log") }
    }

    var lines by remember { mutableStateOf<List<String>>(emptyList()) }
    var captureLogcat by remember { mutableStateOf(false) }
    var statusMsg by remember { mutableStateOf<String?>(null) }

    // Tail loop: re-read debug.log (and logcat if toggled) every 2s.
    LaunchedEffect(captureLogcat) {
        while (true) {
            val newLines = withContext(Dispatchers.IO) {
                buildLogLines(debugLogFile, captureLogcat)
            }
            lines = newLines
            delay(2_000)
        }
    }

    val listState = rememberLazyListState()
    // Auto-scroll to bottom when new lines arrive (only if user hasn't scrolled up).
    LaunchedEffect(lines.size) {
        if (lines.isNotEmpty()) {
            val lastVisible = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            val total = listState.layoutInfo.totalItemsCount
            // If we were within 5 lines of the bottom, stick to the bottom.
            if (total == 0 || lastVisible >= total - 5) {
                listState.scrollToItem((lines.size - 1).coerceAtLeast(0))
            }
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(BgApp),
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                // Header
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Column {
                        Text(
                            text = stringResource(R.string.debug_title),
                            color = TextPrimary,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 20.sp,
                        )
                        Text(
                            text = stringResource(R.string.debug_line_count, lines.size) +
                                if (captureLogcat) stringResource(R.string.debug_logcat_on_suffix) else "",
                            color = TextFaint,
                            fontSize = 12.sp,
                        )
                    }
                    TextButton(onClick = onDismiss) {
                        Text(stringResource(R.string.debug_close), color = Accent, fontWeight = FontWeight.SemiBold)
                    }
                }

                // Action row
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    TextButton(onClick = {
                        debugLogFile?.let { shareDebugLog(context, it) }
                            ?: run { statusMsg = context.getString(R.string.debug_no_log_file) }
                    }) { Text(stringResource(R.string.debug_share), color = Accent) }
                    TextButton(onClick = {
                        debugLogFile?.takeIf { it.exists() }?.delete()
                        statusMsg = context.getString(R.string.debug_log_cleared)
                    }) { Text(stringResource(R.string.debug_clear), color = Accent) }
                    TextButton(onClick = { captureLogcat = !captureLogcat }) {
                        Text(
                            if (captureLogcat) stringResource(R.string.debug_logcat_on)
                            else stringResource(R.string.debug_logcat_off),
                            color = Accent,
                        )
                    }
                }

                statusMsg?.let {
                    Text(
                        text = it,
                        color = TextMuted,
                        fontSize = 12.sp,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    )
                }

                // Log tail
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 8.dp, vertical = 8.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(BgSurface2),
                ) {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(8.dp),
                    ) {
                        items(lines) { line ->
                            Text(
                                text = line,
                                color = TextPrimary,
                                fontSize = 11.sp,
                                fontFamily = FontFamily.Monospace,
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * Build the log tail: last ~500 lines of debug.log, optionally interleaved
 * with the most recent app-pid logcat output. Runs on IO.
 */
private fun buildLogLines(debugLogFile: File?, captureLogcat: Boolean): List<String> {
    val MAX_LINES = 500
    val fileLines: List<String> = debugLogFile
        ?.takeIf { it.exists() }
        ?.runCatching { readLines().takeLast(MAX_LINES) }
        ?.getOrNull()
        ?: emptyList()

    if (!captureLogcat) return fileLines

    val pid = android.os.Process.myPid()
    val logcatLines: List<String> = runCatching {
        val proc = ProcessBuilder("logcat", "-d", "-v", "threadtime", "--pid=$pid")
            .redirectErrorStream(true)
            .start()
        proc.inputStream.bufferedReader().useLines { seq ->
            seq.toList().takeLast(MAX_LINES)
        }
    }.getOrElse { emptyList() }

    // Crude merge: file lines first, then a separator and logcat. Not chronological
    // across the two streams, but each is internally ordered and they capture
    // different signal (structured app events vs. raw runtime).
    return buildList {
        addAll(fileLines)
        if (logcatLines.isNotEmpty()) {
            add("--- logcat (--pid=$pid) ---")
            addAll(logcatLines)
        }
    }
}

private fun shareDebugLog(context: android.content.Context, debugLogFile: File) {
    if (!debugLogFile.exists()) return
    val uri = FileProvider.getUriForFile(
        context, "radio.ks3ckc.ft8af.fileprovider", debugLogFile,
    )
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_STREAM, uri)
        putExtra(Intent.EXTRA_SUBJECT, "FT8AF debug.log")
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(
        Intent.createChooser(send, context.getString(R.string.debug_share_chooser_title)).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        },
    )
}
