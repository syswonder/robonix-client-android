package com.robonix.client.ui.components

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.robonix.client.ui.theme.*

/**
 * High-precision Cybernetic HUD Card with subtle corner brackets and futuristic glow.
 */
@Composable
fun CyberCard(
    modifier: Modifier = Modifier,
    backgroundColor: Color = CyberCardBg,
    borderColor: Color = CyberCardBorder,
    cornerRadius: Dp = 10.dp,
    showBrackets: Boolean = true,
    content: @Composable BoxScope.() -> Unit,
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(cornerRadius))
            .background(backgroundColor)
            .border(1.dp, borderColor, RoundedCornerShape(cornerRadius))
            .drawBehind {
                if (showBrackets) {
                    val bracketLen = 10.dp.toPx()
                    val stroke = 1.5.dp.toPx()
                    val c = HudBracket

                    // Top-left
                    drawLine(c, Offset(0f, stroke / 2), Offset(bracketLen, stroke / 2), stroke, StrokeCap.Round)
                    drawLine(c, Offset(stroke / 2, 0f), Offset(stroke / 2, bracketLen), stroke, StrokeCap.Round)

                    // Top-right
                    drawLine(c, Offset(size.width - bracketLen, stroke / 2), Offset(size.width, stroke / 2), stroke, StrokeCap.Round)
                    drawLine(c, Offset(size.width - stroke / 2, 0f), Offset(size.width - stroke / 2, bracketLen), stroke, StrokeCap.Round)

                    // Bottom-left
                    drawLine(c, Offset(0f, size.height - stroke / 2), Offset(bracketLen, size.height - stroke / 2), stroke, StrokeCap.Round)
                    drawLine(c, Offset(stroke / 2, size.height - bracketLen), Offset(stroke / 2, size.height), stroke, StrokeCap.Round)

                    // Bottom-right
                    drawLine(c, Offset(size.width - bracketLen, size.height - stroke / 2), Offset(size.width, size.height - stroke / 2), stroke, StrokeCap.Round)
                    drawLine(c, Offset(size.width - stroke / 2, size.height - bracketLen), Offset(size.width - stroke / 2, size.height), stroke, StrokeCap.Round)
                }
            }
            .padding(1.dp),
        content = content,
    )
}

/**
 * Animated breathing/pulsing status dot for live streaming or active sessions.
 */
@Composable
fun PulsingStatusDot(
    modifier: Modifier = Modifier,
    color: Color = Cyan,
    size: Dp = 8.dp,
) {
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val scale by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 1.8f,
        animationSpec = infiniteRepeatable(
            animation = tween(1000, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "scale",
    )
    val alpha by infiniteTransition.animateFloat(
        initialValue = 0.8f,
        targetValue = 0.15f,
        animationSpec = infiniteRepeatable(
            animation = tween(1000, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "alpha",
    )

    Box(
        modifier = modifier.size(size * 2),
        contentAlignment = Alignment.Center,
    ) {
        // Outer halo
        Box(
            modifier = Modifier
                .size(size * scale)
                .clip(CircleShape)
                .background(color.copy(alpha = alpha)),
        )
        // Core dot
        Box(
            modifier = Modifier
                .size(size)
                .clip(CircleShape)
                .background(color),
        )
    }
}

/**
 * Realtime dynamic sound wave bars simulating voice input or VU meter activity.
 */
@Composable
fun AudioWaveformVisualizer(
    modifier: Modifier = Modifier,
    active: Boolean = true,
    barCount: Int = 16,
    color: Color = Cyan,
) {
    val infiniteTransition = rememberInfiniteTransition(label = "waveform")
    val phase by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = (2 * Math.PI).toFloat(),
        animationSpec = infiniteRepeatable(
            animation = tween(1200, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "phase",
    )

    Canvas(modifier = modifier.fillMaxWidth().height(28.dp)) {
        val spacing = size.width / (barCount * 2)
        val barWidth = spacing * 0.8f
        val centerY = size.height / 2f
        val maxAmp = size.height * 0.42f

        for (i in 0 until barCount) {
            val x = (i * 2 + 1) * spacing
            val h = if (active) {
                val wave = Math.sin((phase + i * 0.45).toDouble()).toFloat()
                val wave2 = Math.cos((phase * 1.5 + i * 0.25).toDouble()).toFloat()
                (Math.abs(wave * 0.6f + wave2 * 0.4f) * maxAmp).coerceAtLeast(3f)
            } else {
                3f
            }

            drawLine(
                brush = Brush.verticalGradient(
                    colors = listOf(color, color.copy(alpha = 0.4f)),
                    startY = centerY - h,
                    endY = centerY + h,
                ),
                start = Offset(x, centerY - h),
                end = Offset(x, centerY + h),
                strokeWidth = barWidth,
                cap = StrokeCap.Round,
            )
        }
    }
}

/**
 * Beautiful code block component with syntax-friendly styling and copy button.
 */
@Composable
fun CodeBlockView(
    code: String,
    modifier: Modifier = Modifier,
    language: String = "",
) {
    val context = LocalContext.current
    val scrollState = rememberScrollState()

    // Safety guard: prevent Compose Constraints crash on massive payload strings (e.g. 1.2MB image/pointcloud)
    val maxDisplayChars = 8000
    val isTruncated = code.length > maxDisplayChars
    val displayCode = remember(code) {
        if (isTruncated) {
            code.take(maxDisplayChars) + "\n\n/* ... [数据量过大 (${code.length / 1024} KB)，已安全截断显示。点击上方 Copy 可复制完整数据] ... */"
        } else {
            code
        }
    }

    Surface(
        modifier = modifier.fillMaxWidth(),
        color = Panel,
        shape = RoundedCornerShape(8.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, Line),
    ) {
        Column {
            // Header bar with language tag & copy button
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Panel2)
                    .padding(horizontal = 10.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = language.ifBlank { "CODE" }.uppercase(),
                    color = Cyan,
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 0.5.sp,
                )
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .clickable {
                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            clipboard.setPrimaryClip(ClipData.newPlainText("code", code))
                            Toast.makeText(context, "Copied to clipboard", Toast.LENGTH_SHORT).show()
                        }
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        Icons.Default.ContentCopy,
                        contentDescription = "Copy",
                        tint = Muted,
                        modifier = Modifier.size(12.dp),
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        text = "Copy",
                        color = Muted,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Medium,
                    )
                }
            }

            // Code content with horizontal scroll
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(scrollState)
                    .padding(10.dp),
            ) {
                Text(
                    text = displayCode,
                    color = Text,
                    fontSize = 11.5.sp,
                    lineHeight = 16.sp,
                    fontFamily = FontFamily.Monospace,
                )
            }
        }
    }
}

/**
 * Telemetry Chip displaying key metrics with status glow.
 */
@Composable
fun TelemetryChip(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    accentColor: Color = Cyan,
) {
    Surface(
        modifier = modifier,
        color = Panel2,
        shape = RoundedCornerShape(6.dp),
        border = androidx.compose.foundation.BorderStroke(0.8.dp, accentColor.copy(alpha = 0.3f)),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = label.uppercase(),
                color = Muted,
                fontSize = 9.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text = value,
                color = accentColor,
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.ExtraBold,
            )
        }
    }
}
