package com.robonix.client.ui.chat.rtdl

import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.AltRoute
import androidx.compose.material.icons.automirrored.filled.CallSplit
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircleOutline
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Lens
import androidx.compose.material.icons.filled.PlayArrow
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
import com.robonix.client.ui.theme.*

@Composable
fun CapsuleNodeView(
    layoutNode: LayoutNode,
    isSelected: Boolean = false,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val node = layoutNode.node
    val status = layoutNode.status.uppercase()
    val isRunning = status in listOf("RUNNING", "ACTIVE", "VERIFYING")
    val isSucceeded = status in listOf("SUCCEEDED", "SUCCESS")
    val isFailed = status in listOf("FAILED", "ERROR", "TIMEOUT")

    // Breathing glow animation for running nodes
    val infiniteTransition = rememberInfiniteTransition(label = "nodeGlow")
    val glowAlpha by if (isRunning) {
        infiniteTransition.animateFloat(
            initialValue = 0.25f,
            targetValue = 0.85f,
            animationSpec = infiniteRepeatable(
                animation = tween(1100, easing = FastOutSlowInEasing),
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
                animation = tween(1100, easing = FastOutSlowInEasing),
                repeatMode = RepeatMode.Reverse,
            ),
            label = "glowRadius",
        )
    } else {
        remember { mutableFloatStateOf(0f) }
    }

    val nodeIcon: ImageVector = when (node.kind.lowercase()) {
        "action" -> Icons.Default.PlayArrow
        "sequence" -> Icons.AutoMirrored.Filled.AltRoute
        "fallback", "selector" -> Icons.AutoMirrored.Filled.CallSplit
        "condition" -> Icons.Default.CheckCircleOutline
        else -> Icons.Default.Lens
    }

    val label = node.call?.name
        ?: node.opId.ifBlank { node.description.ifBlank { node.kind } }

    val shape = RoundedCornerShape(22.dp)

    Box(
        modifier = modifier
            .size(width = layoutNode.width.dp, height = layoutNode.height.dp)
            .then(
                if (isRunning) {
                    Modifier.drawBehind {
                        drawRoundRect(
                            color = Amber.copy(alpha = glowAlpha * 0.45f),
                            cornerRadius = androidx.compose.ui.geometry.CornerRadius(22.dp.toPx()),
                            style = Stroke(width = glowRadius.dp.toPx()),
                        )
                    }
                } else Modifier
            )
            .clip(shape)
            .background(Panel2)
            .border(
                border = BorderStroke(
                    width = if (isSelected) 2.dp else if (isRunning) 1.5.dp else 0.8.dp,
                    color = when {
                        isSelected -> Cyan
                        isRunning -> Amber.copy(alpha = glowAlpha)
                        isSucceeded -> Green.copy(alpha = 0.6f)
                        isFailed -> Red.copy(alpha = 0.7f)
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
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            // Left: Node Kind Icon
            Surface(
                color = if (isRunning) Amber.copy(alpha = 0.18f) else Cyan.copy(alpha = 0.12f),
                shape = CircleShape,
                modifier = Modifier.size(24.dp),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = nodeIcon,
                        contentDescription = null,
                        tint = if (isRunning) Amber else Cyan,
                        modifier = Modifier.size(13.dp),
                    )
                }
            }

            Spacer(Modifier.width(6.dp))

            // Center: Node index and short action name
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.Center,
            ) {
                Text(
                    text = "#${node.index} $label",
                    color = Text,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = node.kind.uppercase(),
                    color = Dim,
                    fontSize = 8.5.sp,
                    fontFamily = FontFamily.Monospace,
                    maxLines = 1,
                )
            }

            Spacer(Modifier.width(4.dp))

            // Right: Status Badge
            Box(
                modifier = Modifier.size(16.dp),
                contentAlignment = Alignment.Center,
            ) {
                when {
                    isRunning -> PulsingStatusDot(color = Amber, size = 6.dp)
                    isSucceeded -> Icon(
                        imageVector = Icons.Default.Check,
                        contentDescription = null,
                        tint = Green,
                        modifier = Modifier.size(12.dp),
                    )
                    isFailed -> Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = null,
                        tint = Red,
                        modifier = Modifier.size(12.dp),
                    )
                    else -> Box(
                        modifier = Modifier
                            .size(5.dp)
                            .clip(CircleShape)
                            .background(Dim.copy(alpha = 0.6f)),
                    )
                }
            }
        }
    }
}
