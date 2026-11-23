package com.robonix.client.ui.components

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.robonix.client.ui.i18n.t
import com.robonix.client.ui.theme.Cyan
import com.robonix.client.ui.theme.Muted
import com.robonix.client.ui.theme.Panel
import com.robonix.client.ui.theme.Text as TextColor

/** Asks before taking the robot's single audio bridge slot from another client. */
@Composable
fun BridgeTakeoverDialog(
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(t("audio.bridge.takeover.title"), color = TextColor) },
        text = { Text(t("audio.bridge.takeover.body"), color = Muted, fontSize = 13.sp) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(t("audio.bridge.takeover"), color = Cyan, fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(t("action.cancel"), color = Muted) }
        },
        containerColor = Panel,
    )
}
