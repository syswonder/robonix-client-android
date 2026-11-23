package com.robonix.client.ui.chat.rtdl

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.robonix.client.data.model.RtdlNodeState
import com.robonix.client.data.model.RtdlPlan
import com.robonix.client.ui.theme.*
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

@Composable
fun RtdlCanvasView(
    plan: RtdlPlan,
    nodeStates: Map<Int, RtdlNodeState>,
    selectedNodeIndex: Int?,
    onNodeClick: (LayoutNode) -> Unit,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()

    // Layout computation
    val layoutResult = remember(plan, nodeStates) {
        TreeLayoutEngine.computeLayout(plan, nodeStates)
    }

    // Camera / Viewport Transform
    val scaleAnim = remember { Animatable(1.0f) }
    val panXAnim = remember { Animatable(0f) }
    val panYAnim = remember { Animatable(0f) }

    // Energy pulse animation phase: [0.0f .. 1.0f]
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val pulsePhase by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1300, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "pulsePhase",
    )

    // Reused across frames: the pulse animation redraws every edge each frame.
    val badgeTextPaint = remember {
        android.graphics.Paint().apply {
            textAlign = android.graphics.Paint.Align.CENTER
            isFakeBoldText = true
        }
    }

    fun animateCamera(targetScale: Float, targetOffset: Offset) {
        scope.launch {
            launch { scaleAnim.animateTo(targetScale.coerceIn(0.35f, 2.5f), spring(stiffness = Spring.StiffnessMediumLow)) }
            launch { panXAnim.animateTo(targetOffset.x, spring(stiffness = Spring.StiffnessMediumLow)) }
            launch { panYAnim.animateTo(targetOffset.y, spring(stiffness = Spring.StiffnessMediumLow)) }
        }
    }

    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .clip(RoundedCornerShape(12.dp))
            .background(Bg)
            .pointerInput(layoutResult) {
                detectTransformGestures { centroid, pan, zoom, _ ->
                    scope.launch {
                        val newScale = (scaleAnim.value * zoom).coerceIn(0.35f, 2.5f)
                        val scaleFactor = newScale / scaleAnim.value

                        // Zoom towards gesture centroid
                        val newPanX = centroid.x - (centroid.x - panXAnim.value) * scaleFactor + pan.x
                        val newPanY = centroid.y - (centroid.y - panYAnim.value) * scaleFactor + pan.y

                        scaleAnim.snapTo(newScale)
                        panXAnim.snapTo(newPanX)
                        panYAnim.snapTo(newPanY)
                    }
                }
            },
    ) {
        val viewportWidthPx = constraints.maxWidth.toFloat()
        val viewportHeightPx = constraints.maxHeight.toFloat()

        // Auto-fit on first load or plan change
        LaunchedEffect(plan.planId, plan.round) {
            if (layoutResult.nodes.isNotEmpty()) {
                val b = layoutResult.bounds
                with(density) {
                    val treeWidthPx = (b.width + 60f).dp.toPx()
                    val treeHeightPx = (b.height + 80f).dp.toPx()
                    val targetScale = min(viewportWidthPx / max(treeWidthPx, 1f), viewportHeightPx / max(treeHeightPx, 1f))
                        .coerceIn(0.45f, 1.1f)
                    val treeCenterXPx = ((b.left + b.right) / 2f).dp.toPx()
                    val treeCenterYPx = ((b.top + b.bottom) / 2f).dp.toPx()

                    val targetPanX = (viewportWidthPx / 2f) - (treeCenterXPx * targetScale)
                    val targetPanY = max(24.dp.toPx(), (viewportHeightPx / 2f) - (treeCenterYPx * targetScale))

                    animateCamera(targetScale, Offset(targetPanX, targetPanY))
                }
            }
        }

        // 1. Connectors Canvas with semantic status coloring & energy flow
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    scaleX = scaleAnim.value
                    scaleY = scaleAnim.value
                    translationX = panXAnim.value
                    translationY = panYAnim.value
                    transformOrigin = TransformOrigin(0f, 0f)
                },
        ) {
            layoutResult.edges.forEach { edge ->
                val from = edge.fromNode
                val to = edge.toNode

                if (edge.edgeType == EdgeType.LOOP_BACK) {
                    val fromRight = from.right.dp.toPx()
                    val fromY = from.y.dp.toPx()
                    val toRight = to.right.dp.toPx()
                    val toY = to.y.dp.toPx()
                    val railX = max(fromRight, toRight) + 24.dp.toPx()
                    val r = 8.dp.toPx()

                    val loopPath = Path().apply {
                        moveTo(fromRight, fromY)
                        lineTo(railX - r, fromY)
                        quadraticBezierTo(railX, fromY, railX, if (toY < fromY) fromY - r else fromY + r)
                        lineTo(railX, if (toY < fromY) toY + r else toY - r)
                        quadraticBezierTo(railX, toY, railX - r, toY)
                        lineTo(toRight, toY)
                    }

                    val loopColor = if (edge.isActivePath) Amber else Cyan.copy(alpha = 0.55f)
                    drawPath(
                        path = loopPath,
                        color = loopColor,
                        style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round),
                    )

                    // Arrow entering toRight pointing left
                    val arrowSize = 4.5.dp.toPx()
                    val arrowPath = Path().apply {
                        moveTo(toRight + arrowSize * 1.6f, toY - arrowSize)
                        lineTo(toRight, toY)
                        lineTo(toRight + arrowSize * 1.6f, toY + arrowSize)
                    }
                    drawPath(
                        path = arrowPath,
                        color = loopColor,
                        style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round),
                    )

                    if (edge.badge.isNotBlank()) {
                        val midY = (fromY + toY) / 2f
                        val bw = 32.dp.toPx()
                        val bh = 15.dp.toPx()
                        drawRoundRect(
                            color = Panel2,
                            topLeft = Offset(railX - bw / 2f, midY - bh / 2f),
                            size = Size(bw, bh),
                            cornerRadius = CornerRadius(4.dp.toPx()),
                        )
                        drawRoundRect(
                            color = Amber.copy(alpha = 0.8f),
                            topLeft = Offset(railX - bw / 2f, midY - bh / 2f),
                            size = Size(bw, bh),
                            cornerRadius = CornerRadius(4.dp.toPx()),
                            style = Stroke(width = 1.dp.toPx()),
                        )
                        drawContext.canvas.nativeCanvas.drawText(
                            edge.badge,
                            railX,
                            midY + 3.5.dp.toPx(),
                            badgeTextPaint.apply {
                                color = android.graphics.Color.parseColor("#FFD369")
                                textSize = 8.5f.dp.toPx()
                            },
                        )
                    }
                    return@forEach
                }

                val x0 = from.bottomCenter.x.dp.toPx()
                val y0 = from.bottomCenter.y.dp.toPx()
                val x1 = to.topCenter.x.dp.toPx()
                val y1 = to.topCenter.y.dp.toPx()

                val dx = x1 - x0
                val isStraight = abs(dx) < 2.dp.toPx()
                val midY = (y0 + y1) / 2f
                val maxR = minOf(10.dp.toPx(), abs(dx) / 2f, (y1 - y0) / 4f)
                // Never wider than half the offset, or the horizontal run turns negative and the curve folds back.
                val r = if (isStraight) 0f else maxOf(maxR, 0f)
                val dirX = if (dx > 0) 1f else -1f

                val path = Path().apply {
                    moveTo(x0, y0)
                    if (isStraight) {
                        lineTo(x1, y1)
                    } else {
                        lineTo(x0, midY - r)
                        quadraticBezierTo(x0, midY, x0 + dirX * r, midY)
                        lineTo(x1 - dirX * r, midY)
                        quadraticBezierTo(x1, midY, x1, midY + r)
                        lineTo(x1, y1)
                    }
                }

                // Branch color and stroke based on destination node's status
                val toStatus = to.status.uppercase()
                val isToRunning = toStatus in listOf("RUNNING", "ACTIVE", "VERIFYING")
                val isToSucceeded = toStatus in listOf("SUCCEEDED", "SUCCESS")
                val isToFailed = toStatus in listOf("FAILED", "ERROR", "TIMEOUT")
                val isSkippedEdge = edge.isSkipped

                val baseLineColor = when {
                    isSkippedEdge -> LineSoft.copy(alpha = 0.35f)
                    edge.isActivePath || isToRunning -> Amber
                    isToFailed -> Red.copy(alpha = 0.75f)
                    isToSucceeded -> Green.copy(alpha = 0.7f)
                    else -> Cyan.copy(alpha = 0.45f)
                }
                val baseStrokeWidth = when {
                    isSkippedEdge -> 1.4.dp.toPx()
                    edge.isActivePath -> 2.6.dp.toPx()
                    isToFailed -> 2.2.dp.toPx()
                    isToSucceeded -> 2.2.dp.toPx()
                    else -> 1.8.dp.toPx()
                }

                // Soft glowing aura for active or failed paths
                if (edge.isActivePath && !isSkippedEdge) {
                    drawPath(
                        path = path,
                        color = Amber.copy(alpha = 0.25f),
                        style = Stroke(width = 6.dp.toPx(), cap = StrokeCap.Round),
                    )
                } else if (isToFailed && !isSkippedEdge) {
                    drawPath(
                        path = path,
                        color = Red.copy(alpha = 0.18f),
                        style = Stroke(width = 5.dp.toPx(), cap = StrokeCap.Round),
                    )
                }

                // Main connector line
                drawPath(
                    path = path,
                    color = baseLineColor,
                    style = Stroke(width = baseStrokeWidth, cap = StrokeCap.Round),
                )

                // Directional arrowhead entering destination node
                val arrowSize = 4.5.dp.toPx()
                val arrowPath = Path().apply {
                    moveTo(x1 - arrowSize, y1 - arrowSize * 1.6f)
                    lineTo(x1, y1)
                    lineTo(x1 + arrowSize, y1 - arrowSize * 1.6f)
                }
                drawPath(
                    path = arrowPath,
                    color = baseLineColor,
                    style = Stroke(width = baseStrokeWidth, cap = StrokeCap.Round),
                )

                // IF branch badge (✓ True / ✗ False) placed on vertical drop above destination node
                if (edge.edgeType == EdgeType.IF_TRUE || edge.edgeType == EdgeType.IF_FALSE) {
                    val isTrue = edge.edgeType == EdgeType.IF_TRUE
                    val badgeX = x1
                    val badgeY = if (isStraight) (y0 + y1) / 2f else (midY + y1) / 2f
                    val badgeColor = if (isSkippedEdge) LineSoft.copy(alpha = 0.5f) else if (isTrue) Green else Red
                    val badgeBg = if (isSkippedEdge) Panel2.copy(alpha = 0.6f) else Panel2

                    val bw = 38.dp.toPx()
                    val bh = 15.dp.toPx()

                    drawRoundRect(
                        color = badgeBg,
                        topLeft = Offset(badgeX - bw / 2f, badgeY - bh / 2f),
                        size = Size(bw, bh),
                        cornerRadius = CornerRadius(4.dp.toPx()),
                    )
                    drawRoundRect(
                        color = badgeColor.copy(alpha = if (isSkippedEdge) 0.3f else 0.85f),
                        topLeft = Offset(badgeX - bw / 2f, badgeY - bh / 2f),
                        size = Size(bw, bh),
                        cornerRadius = CornerRadius(4.dp.toPx()),
                        style = Stroke(width = 1.dp.toPx()),
                    )
                    drawContext.canvas.nativeCanvas.drawText(
                        edge.label,
                        badgeX,
                        badgeY + 3.5.dp.toPx(),
                        badgeTextPaint.apply {
                            color = if (isSkippedEdge) android.graphics.Color.GRAY else if (isTrue) android.graphics.Color.parseColor("#4ECCA3") else android.graphics.Color.parseColor("#E94560")
                            textSize = 8.5f.dp.toPx()
                        },
                    )
                }

                // Energy Pulse Animation along Active Paths
                if (edge.isActivePath && !isSkippedEdge) {
                    val px: Float
                    val py: Float
                    if (isStraight) {
                        px = x0
                        py = y0 + pulsePhase * (y1 - y0)
                    } else {
                        val seg1 = midY - r - y0
                        val arc1 = 1.57f * r
                        val seg2 = abs(dx) - 2f * r
                        val arc2 = 1.57f * r
                        val seg3 = y1 - (midY + r)
                        val totalLen = seg1 + arc1 + seg2 + arc2 + seg3
                        val d = (pulsePhase * totalLen).coerceIn(0f, totalLen)

                        when {
                            d <= seg1 -> {
                                px = x0
                                py = y0 + d
                            }
                            d <= seg1 + arc1 -> {
                                val u = (d - seg1) / arc1
                                px = (1 - u) * (1 - u) * x0 + 2 * (1 - u) * u * x0 + u * u * (x0 + dirX * r)
                                py = (1 - u) * (1 - u) * (midY - r) + 2 * (1 - u) * u * midY + u * u * midY
                            }
                            d <= seg1 + arc1 + seg2 -> {
                                val rem = d - (seg1 + arc1)
                                px = x0 + dirX * r + dirX * rem
                                py = midY
                            }
                            d <= seg1 + arc1 + seg2 + arc2 -> {
                                val u = (d - seg1 - arc1 - seg2) / arc2
                                px = (1 - u) * (1 - u) * (x1 - dirX * r) + 2 * (1 - u) * u * x1 + u * u * x1
                                py = (1 - u) * (1 - u) * midY + 2 * (1 - u) * u * midY + u * u * (midY + r)
                            }
                            else -> {
                                val rem = d - (seg1 + arc1 + seg2 + arc2)
                                px = x1
                                py = midY + r + rem
                            }
                        }
                    }

                    // Outer halo
                    drawCircle(
                        color = Amber.copy(alpha = 0.35f),
                        radius = 6.dp.toPx(),
                        center = Offset(px, py),
                    )
                    // Inner glowing core
                    drawCircle(
                        color = Amber,
                        radius = 3.dp.toPx(),
                        center = Offset(px, py),
                    )
                }
            }
        }

        // 2. Node Views Container (Transformed via graphicsLayer for 60/120fps smooth scaling)
        Box(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    scaleX = scaleAnim.value
                    scaleY = scaleAnim.value
                    translationX = panXAnim.value
                    translationY = panYAnim.value
                    transformOrigin = TransformOrigin(0f, 0f)
                },
        ) {
            layoutResult.nodes.forEach { lNode ->
                CapsuleNodeView(
                    layoutNode = lNode,
                    isSelected = selectedNodeIndex == lNode.node.index,
                    onClick = { onNodeClick(lNode) },
                    modifier = Modifier.offset(x = lNode.left.dp, y = lNode.top.dp),
                )
            }
        }
    }
}
