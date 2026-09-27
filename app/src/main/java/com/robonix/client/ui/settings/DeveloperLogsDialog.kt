package com.robonix.client.ui.settings

import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.robonix.client.AppLog
import com.robonix.client.ui.i18n.t
import com.robonix.client.ui.theme.*

private enum class LogFilter {
    ALL, ERRORS, NETWORK
}

@Composable
fun DeveloperLogsDialog(
    onDismissRequest: () -> Unit,
) {
    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current
    val copiedMsg = t("settings.dev_logs.copied")
    val clearedMsg = t("settings.dev_logs.cleared")
    var rawLogs by remember { mutableStateOf(AppLog.getLogs()) }
    var filter by remember { mutableStateOf(LogFilter.ALL) }
    val listState = rememberLazyListState()

    fun refreshLogs() {
        rawLogs = AppLog.getLogs()
    }

    val lines = remember(rawLogs, filter) {
        val allLines = rawLogs.lines().filter { it.isNotBlank() }
        when (filter) {
            LogFilter.ALL -> allLines
            LogFilter.ERRORS -> allLines.filter { line ->
                val upper = line.uppercase()
                upper.contains("ERR") ||
                    upper.contains("FAIL") ||
                    upper.contains("EXCEPTION") ||
                    upper.contains("UNAVAILABLE") ||
                    upper.contains("TERMINATED") ||
                    upper.contains("CRASH") ||
                    upper.contains("FATAL") ||
                    upper.contains("TIMEOUT") ||
                    line.trimStart().startsWith("at ")
            }
            LogFilter.NETWORK -> allLines.filter { line ->
                val upper = line.uppercase()
                upper.contains("GRPC") ||
                    upper.contains("ATLAS") ||
                    upper.contains("LIAISON") ||
                    upper.contains("VITALS") ||
                    upper.contains("PERCEPTION") ||
                    upper.contains("CONN")
            }
        }
    }

    LaunchedEffect(lines.size) {
        if (lines.isNotEmpty()) {
            listState.scrollToItem(lines.size - 1)
        }
    }

    Dialog(
        onDismissRequest = onDismissRequest,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier = Modifier
                .fillMaxSize()
                .padding(14.dp),
            shape = RoundedCornerShape(16.dp),
            color = Bg,
            border = BorderStroke(1.dp, Line),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(14.dp),
            ) {
                // Header Row
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(34.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(Cyan.copy(alpha = 0.15f)),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(Icons.Default.Terminal, null, tint = Cyan, modifier = Modifier.size(18.dp))
                        }
                        Spacer(Modifier.width(10.dp))
                        Column {
                            Text(
                                text = t("settings.dev_logs.title"),
                                color = Text,
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Bold,
                            )
                            Text(
                                text = "${lines.size} / ${rawLogs.lines().size} lines",
                                color = Dim,
                                fontSize = 11.sp,
                                fontFamily = FontFamily.Monospace,
                            )
                        }
                    }

                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        // Refresh button
                        IconButton(
                            onClick = { refreshLogs() },
                            modifier = Modifier
                                .size(32.dp)
                                .clip(CircleShape)
                                .background(Panel2),
                        ) {
                            Icon(Icons.Default.Refresh, t("action.refresh"), tint = Cyan, modifier = Modifier.size(16.dp))
                        }

                        // Copy All Button
                        Button(
                            onClick = {
                                val textToCopy = if (filter == LogFilter.ALL) rawLogs else lines.joinToString("\n")
                                clipboardManager.setText(AnnotatedString(textToCopy))
                                Toast.makeText(context, copiedMsg, Toast.LENGTH_SHORT).show()
                            },
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                            shape = RoundedCornerShape(8.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Cyan.copy(alpha = 0.2f),
                                contentColor = Cyan,
                            ),
                            modifier = Modifier.height(32.dp),
                        ) {
                            Icon(Icons.Default.ContentCopy, null, modifier = Modifier.size(13.dp))
                            Spacer(Modifier.width(4.dp))
                            Text(t("settings.dev_logs.copy"), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }

                        // Close Button
                        IconButton(
                            onClick = onDismissRequest,
                            modifier = Modifier
                                .size(32.dp)
                                .clip(CircleShape)
                                .background(Panel2),
                        ) {
                            Icon(Icons.Default.Close, t("action.close"), tint = Muted, modifier = Modifier.size(16.dp))
                        }
                    }
                }

                Spacer(Modifier.height(10.dp))

                // Filter chips & Clear action
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        FilterPill(
                            label = t("settings.dev_logs.filter.all"),
                            selected = filter == LogFilter.ALL,
                            onClick = { filter = LogFilter.ALL },
                        )
                        FilterPill(
                            label = t("settings.dev_logs.filter.errors"),
                            selected = filter == LogFilter.ERRORS,
                            selectedColor = Red,
                            onClick = { filter = LogFilter.ERRORS },
                        )
                        FilterPill(
                            label = t("settings.dev_logs.filter.network"),
                            selected = filter == LogFilter.NETWORK,
                            onClick = { filter = LogFilter.NETWORK },
                        )
                    }

                    // Clear logs button
                    TextButton(
                        onClick = {
                            AppLog.clear()
                            refreshLogs()
                            Toast.makeText(context, clearedMsg, Toast.LENGTH_SHORT).show()
                        },
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                        modifier = Modifier.height(28.dp),
                    ) {
                        Icon(Icons.Default.DeleteOutline, null, tint = Dim, modifier = Modifier.size(14.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(t("settings.dev_logs.clear"), color = Dim, fontSize = 11.sp)
                    }
                }

                Spacer(Modifier.height(8.dp))

                // Terminal console area
                Surface(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    shape = RoundedCornerShape(10.dp),
                    color = Color(0xFF090D12),
                    border = BorderStroke(1.dp, LineSoft),
                ) {
                    if (lines.isEmpty()) {
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text(t("settings.dev_logs.empty"), color = Dim, fontSize = 12.sp)
                        }
                    } else {
                        LazyColumn(
                            state = listState,
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(8.dp),
                            verticalArrangement = Arrangement.spacedBy(3.dp),
                        ) {
                            items(lines) { line ->
                                LogLineItem(line)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun FilterPill(
    label: String,
    selected: Boolean,
    selectedColor: Color = Cyan,
    onClick: () -> Unit,
) {
    Surface(
        color = if (selected) selectedColor.copy(alpha = 0.16f) else Panel2,
        shape = RoundedCornerShape(6.dp),
        border = BorderStroke(0.8.dp, if (selected) selectedColor.copy(alpha = 0.5f) else LineSoft),
        modifier = Modifier
            .clickable(onClick = onClick)
            .height(26.dp),
    ) {
        Box(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = label,
                color = if (selected) selectedColor else Muted,
                fontSize = 11.sp,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
            )
        }
    }
}

@Composable
private fun LogLineItem(line: String) {
    val isError = remember(line) {
        val u = line.uppercase()
        u.contains("ERR") || u.contains("FAIL") || u.contains("EXCEPTION") ||
            u.contains("UNAVAILABLE") || u.contains("TERMINATED") || u.contains("FATAL") ||
            line.trimStart().startsWith("at ")
    }
    val isWarn = remember(line) {
        val u = line.uppercase()
        !isError && (u.contains("WARN") || u.contains("TIMEOUT"))
    }

    val textColor = when {
        isError -> Color(0xFFFF5252)
        isWarn -> Amber
        line.contains("[SYSTEM]") -> Cyan
        line.contains("[PERCEPTION]") -> Color(0xFF81D4FA)
        line.contains("[VOICE]") || line.contains("[HANDSFREE]") -> Color(0xFFCE93D8)
        else -> Color(0xFFC0CAD8)
    }

    Text(
        text = line,
        color = textColor,
        fontSize = 11.sp,
        lineHeight = 15.sp,
        fontFamily = FontFamily.Monospace,
    )
}
