package com.robonix.client.ui.chat.rtdl

import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.AltRoute
import androidx.compose.material.icons.automirrored.filled.CallSplit
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.robonix.client.ui.components.PulsingStatusDot
import com.robonix.client.ui.i18n.t
import com.robonix.client.ui.theme.*

@Composable
fun CapsuleNodeView(
    layoutNode: LayoutNode,
    isSelected: Boolean = false,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val node = layoutNode.node
    val status = layoutNode.displayStatus
    val isSkipped = status == RtdlDisplayStatus.Skipped
    val isRunning = status == RtdlDisplayStatus.Running
    val isFailed = status == RtdlDisplayStatus.Failed
    val isSucceeded = status == RtdlDisplayStatus.Succeeded

    // Breathing glow animation for running nodes
    val infiniteTransition = rememberInfiniteTransition(label = "nodeGlow")
    val glowAlpha by if (isRunning) {
        infiniteTransition.animateFloat(
            initialValue = 0.3f,
            targetValue = 0.95f,
            animationSpec = infiniteRepeatable(
                animation = tween(1000, easing = FastOutSlowInEasing),
                repeatMode = RepeatMode.Reverse,
            ),
            label = "glowAlpha",
        )
    } else {
        remember { mutableFloatStateOf(0f) }
    }

    val glowRadius by if (isRunning) {
        infiniteTransition.animateFloat(
            initialValue = 2f,
            targetValue = 8f,
            animationSpec = infiniteRepeatable(
                animation = tween(1000, easing = FastOutSlowInEasing),
                repeatMode = RepeatMode.Reverse,
            ),
            label = "glowRadius",
        )
    } else {
        remember { mutableFloatStateOf(0f) }
    }

    val nodeIcon: ImageVector = when (node.kind.lowercase()) {
        "sequence" -> Icons.Default.FormatListNumbered
        "parallel" -> Icons.AutoMirrored.Filled.CallSplit
        "if" -> Icons.AutoMirrored.Filled.AltRoute
        "repeat" -> Icons.Default.Refresh
        "while" -> Icons.Default.Sync
        "fallback", "selector" -> Icons.AutoMirrored.Filled.AltRoute
        "condition" -> Icons.Default.CheckCircleOutline
        "action", "do" -> Icons.Default.PlayArrow
        else -> Icons.Default.Lens
    }

    val isControlNode = node.kind.lowercase() in listOf("sequence", "parallel", "if", "repeat", "while", "fallback", "selector")

    // Single concise, human-readable title without repetitive noise
    val label = when {
        node.call != null ->
            node.call.name.substringAfterLast("/")
        node.description.isNotBlank() && !node.description.startsWith("-") && node.description.length <= 18 ->
            node.description
        node.kind.lowercase() == "repeat" ->
            if (node.repeatCount > 0) "Repeat ×${node.repeatCount}" else "Repeat"
        node.kind.lowercase() == "while" ->
            if (node.maxIterations > 0) "While (≤${node.maxIterations})" else "While"
        node.kind.lowercase() == "if" ->
            "If"
        node.kind.isNotBlank() && node.kind.lowercase() in listOf("sequence", "parallel", "selector", "fallback", "condition") ->
            node.kind.replaceFirstChar { it.uppercase() }
        node.opId.isNotBlank() && !node.opId.contains("-") ->
            node.opId.substringAfterLast("/")
        else -> node.kind.replaceFirstChar { it.uppercase() }
    }

    val cardBg = when {
        isSkipped -> Panel2.copy(alpha = 0.45f)
        isSucceeded -> Color(0xFF0D221D)
        isFailed -> Color(0xFF281215)
        isRunning -> Color(0xFF281F0D)
        isControlNode -> Panel2.copy(alpha = 0.85f)
        else -> Panel2
    }

    val themeColor = when {
        isSkipped -> Dim
        isRunning -> Amber
        isSucceeded -> Green
        isFailed -> Red
        isControlNode -> Cyan
        else -> Text
    }

    val shape = RoundedCornerShape(14.dp)

    Box(
        modifier = modifier
            .size(width = layoutNode.width.dp, height = layoutNode.height.dp)
            .then(
                if (isRunning) {
                    Modifier.drawBehind {
                        drawRoundRect(
                            color = Amber.copy(alpha = glowAlpha * 0.4f),
                            cornerRadius = androidx.compose.ui.geometry.CornerRadius(14.dp.toPx()),
                            style = Stroke(width = glowRadius.dp.toPx()),
                        )
                    }
                } else Modifier
            )
            .clip(shape)
            .background(cardBg)
            .border(
                border = BorderStroke(
                    width = if (isSelected) 2.dp else if (isRunning) 1.5.dp else 1.dp,
                    color = when {
                        isSelected -> Cyan
                        isSkipped -> LineSoft.copy(alpha = 0.35f)
                        isRunning -> Amber.copy(alpha = glowAlpha)
                        isSucceeded -> Green.copy(alpha = 0.65f)
                        isFailed -> Red.copy(alpha = 0.75f)
                        isControlNode -> Cyan.copy(alpha = 0.35f)
                        else -> LineSoft
                    },
                ),
                shape = shape,
            )
            .clickable { onClick() }
            .padding(horizontal = 10.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            modifier = Modifier.fillMaxSize(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Left: Node Icon inside a soft squircle container
            Surface(
                color = themeColor.copy(alpha = if (isControlNode) 0.12f else 0.16f),
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.size(32.dp),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = nodeIcon,
                        contentDescription = null,
                        tint = themeColor,
                        modifier = Modifier.size(17.dp),
                    )
                }
            }

            Spacer(Modifier.width(8.dp))

            // Center: Clean, unclipped single-line title (#index + tag + action/kind name)
            Row(
                modifier = Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "#${node.index}",
                    color = themeColor,
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                )

                if (node.kind.lowercase() == "if") {
                    Spacer(Modifier.width(4.dp))
                    Surface(
                        color = NeonPurple.copy(alpha = 0.18f),
                        shape = RoundedCornerShape(4.dp),
                    ) {
                        Text(
                            text = "IF",
                            color = NeonPurple,
                            fontSize = 9.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp),
                        )
                    }
                } else if (node.kind.lowercase() in listOf("repeat", "while")) {
                    Spacer(Modifier.width(4.dp))
                    val tagText = if (node.repeatCount > 0) "×${node.repeatCount}" else if (node.maxIterations > 0) "≤${node.maxIterations}" else "LOOP"
                    Surface(
                        color = Amber.copy(alpha = 0.18f),
                        shape = RoundedCornerShape(4.dp),
                    ) {
                        Text(
                            text = tagText,
                            color = Amber,
                            fontSize = 9.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp),
                        )
                    }
                }

                Spacer(Modifier.width(6.dp))

                Text(
                    text = label,
                    color = if (isSkipped) Dim else Text,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            Spacer(Modifier.width(6.dp))

            // Right: Glanceable status badge with icon & text
            Surface(
                color = when {
                    isSkipped -> LineSoft.copy(alpha = 0.25f)
                    isRunning -> Amber.copy(alpha = 0.16f)
                    isSucceeded -> Green.copy(alpha = 0.14f)
                    isFailed -> Red.copy(alpha = 0.16f)
                    else -> LineSoft.copy(alpha = 0.4f)
                },
                shape = RoundedCornerShape(8.dp),
                border = BorderStroke(
                    width = 0.8.dp,
                    color = when {
                        isSkipped -> LineSoft.copy(alpha = 0.35f)
                        isRunning -> Amber.copy(alpha = 0.45f)
                        isSucceeded -> Green.copy(alpha = 0.4f)
                        isFailed -> Red.copy(alpha = 0.45f)
                        else -> LineSoft
                    },
                ),
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    when {
                        isSkipped -> {
                            Text(
                                text = t(status.label),
                                color = Dim,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Medium,
                            )
                        }
                        isRunning -> {
                            PulsingStatusDot(color = Amber, size = 5.dp)
                            Spacer(Modifier.width(4.dp))
                            Text(
                                text = t(status.label),
                                color = Amber,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                            )
                        }
                        isSucceeded -> {
                            Icon(
                                imageVector = Icons.Default.Check,
                                contentDescription = null,
                                tint = Green,
                                modifier = Modifier.size(11.dp),
                            )
                            Spacer(Modifier.width(3.dp))
                            Text(
                                text = t(status.label),
                                color = Green,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                            )
                        }
                        isFailed -> {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = null,
                                tint = Red,
                                modifier = Modifier.size(11.dp),
                            )
                            Spacer(Modifier.width(3.dp))
                            Text(
                                text = t(status.label),
                                color = Red,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                            )
                        }
                        else -> {
                            Text(
                                text = t(status.label),
                                color = Dim,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Medium,
                            )
                        }
                    }
                }
            }
        }
    }
}
