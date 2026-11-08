package com.robonix.client.ui.chat.rtdl

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CropFree
import androidx.compose.material.icons.filled.GpsFixed
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.robonix.client.data.model.RtdlNodeState
import com.robonix.client.data.model.RtdlPlan
import com.robonix.client.ui.theme.*
import kotlinx.coroutines.launch
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
            animation = tween(1400, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "pulsePhase",
    )

    // Helper functions for animating viewport
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

        // 1. Cyber Grid & Connectors Canvas
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
            // Draw connections between nodes
            layoutResult.edges.forEach { edge ->
                val from = edge.fromNode
                val to = edge.toNode

                val x0 = from.bottomCenter.x.dp.toPx()
                val y0 = from.bottomCenter.y.dp.toPx()
                val x1 = to.topCenter.x.dp.toPx()
                val y1 = to.topCenter.y.dp.toPx()

                val dy = y1 - y0
                val ctrlY0 = y0 + dy * 0.5f
                val ctrlY1 = y1 - dy * 0.5f

                val path = Path().apply {
                    moveTo(x0, y0)
                    cubicTo(x0, ctrlY0, x1, ctrlY1, x1, y1)
                }

                // Connector line
                if (edge.isActivePath) {
                    // Glowing aura under active line
                    drawPath(
                        path = path,
                        color = Amber.copy(alpha = 0.25f),
                        style = Stroke(width = 6.dp.toPx(), cap = StrokeCap.Round),
                    )
                    drawPath(
                        path = path,
                        color = Amber,
                        style = Stroke(width = 2.6.dp.toPx(), cap = StrokeCap.Round),
                    )
                } else {
                    // Crisp, high-contrast cyber connector line
                    drawPath(
                        path = path,
                        color = Cyan.copy(alpha = 0.55f),
                        style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round),
                    )
                }

                // Energy Pulse Animation along Active Paths
                if (edge.isActivePath) {
                    val t = pulsePhase
                    val omt = 1f - t
                    // Cubic Bezier interpolation: B(t) = (1-t)^3 P0 + 3(1-t)^2 t P1 + 3(1-t) t^2 P2 + t^3 P3
                    val px = omt.pow(3) * x0 + 3 * omt.pow(2) * t * x0 + 3 * omt * t.pow(2) * x1 + t.pow(3) * x1
                    val py = omt.pow(3) * y0 + 3 * omt.pow(2) * t * ctrlY0 + 3 * omt * t.pow(2) * ctrlY1 + t.pow(3) * y1

                    // Outer halo
                    drawCircle(
                        color = Amber.copy(alpha = 0.35f),
                        radius = 7.dp.toPx(),
                        center = Offset(px, py),
                    )
                    // Inner glowing core
                    drawCircle(
                        color = Amber,
                        radius = 3.5.dp.toPx(),
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

        // 3. Floating HUD Controls (Bottom-Right)
        Surface(
            color = Panel.copy(alpha = 0.85f),
            shape = RoundedCornerShape(16.dp),
            border = androidx.compose.foundation.BorderStroke(1.dp, LineSoft),
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(12.dp),
        ) {
            Column(
                modifier = Modifier.padding(4.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                // Focus Active Node Button
                val runningNode = layoutResult.nodes.find {
                    it.status.uppercase() in listOf("RUNNING", "ACTIVE", "VERIFYING")
                }
                IconButton(
                    onClick = {
                        val targetNode = runningNode ?: layoutResult.nodes.firstOrNull()
                        if (targetNode != null) {
                            with(density) {
                                val targetScale = 1.0f
                                val targetPanX = (viewportWidthPx / 2f) - (targetNode.x.dp.toPx() * targetScale)
                                val targetPanY = (viewportHeightPx / 2f) - (targetNode.y.dp.toPx() * targetScale)
                                animateCamera(targetScale, Offset(targetPanX, targetPanY))
                            }
                        }
                    },
                    modifier = Modifier.size(32.dp),
                ) {
                    Icon(
                        imageVector = Icons.Default.GpsFixed,
                        contentDescription = "Focus Active",
                        tint = if (runningNode != null) Amber else Cyan,
                        modifier = Modifier.size(16.dp),
                    )
                }

                // Fit View / Reset View Button
                IconButton(
                    onClick = {
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
                    },
                    modifier = Modifier.size(32.dp),
                ) {
                    Icon(
                        imageVector = Icons.Default.CropFree,
                        contentDescription = "Fit View",
                        tint = Text,
                        modifier = Modifier.size(16.dp),
                    )
                }

                // Zoom In Button
                IconButton(
                    onClick = {
                        val newScale = (scaleAnim.value * 1.25f).coerceAtMost(2.5f)
                        val factor = newScale / scaleAnim.value
                        val newPanX = (viewportWidthPx / 2f) - ((viewportWidthPx / 2f) - panXAnim.value) * factor
                        val newPanY = (viewportHeightPx / 2f) - ((viewportHeightPx / 2f) - panYAnim.value) * factor
                        animateCamera(newScale, Offset(newPanX, newPanY))
                    },
                    modifier = Modifier.size(32.dp),
                ) {
                    Icon(
                        imageVector = Icons.Default.Add,
                        contentDescription = "Zoom In",
                        tint = Text,
                        modifier = Modifier.size(16.dp),
                    )
                }

                // Zoom Out Button
                IconButton(
                    onClick = {
                        val newScale = (scaleAnim.value / 1.25f).coerceAtLeast(0.35f)
                        val factor = newScale / scaleAnim.value
                        val newPanX = (viewportWidthPx / 2f) - ((viewportWidthPx / 2f) - panXAnim.value) * factor
                        val newPanY = (viewportHeightPx / 2f) - ((viewportHeightPx / 2f) - panYAnim.value) * factor
                        animateCamera(newScale, Offset(newPanX, newPanY))
                    },
                    modifier = Modifier.size(32.dp),
                ) {
                    Icon(
                        imageVector = Icons.Default.Remove,
                        contentDescription = "Zoom Out",
                        tint = Text,
                        modifier = Modifier.size(16.dp),
                    )
                }
            }
        }
    }
}

