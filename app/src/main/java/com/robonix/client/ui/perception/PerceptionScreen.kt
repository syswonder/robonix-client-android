package com.robonix.client.ui.perception

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.robonix.client.ui.components.CyberCard
import com.robonix.client.ui.components.PulsingStatusDot
import com.robonix.client.ui.i18n.t
import com.robonix.client.ui.theme.*
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

@Composable
fun PerceptionScreen(
    viewModel: PerceptionViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsState()
    var showExportChoices by remember { mutableStateOf(false) }
    val documentLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("image/png")) { uri ->
        viewModel.finishDocumentSnapshot(uri)
    }
    BackHandler(enabled = state.selectedChannel != 0) { viewModel.selectChannel(0) }
    val exportChannel: (Int) -> Unit = { channel ->
        viewModel.beginSnapshot(channel)?.let { filename ->
            try {
                documentLauncher.launch(filename)
            } catch (_: Exception) {
                viewModel.snapshotPickerFailed()
            }
        }
    }
    val exportSnapshot: () -> Unit = snapshot@{
        if (state.isExportingSnapshot) return@snapshot
        val selected = state.selectedChannel
        if (selected == 0) {
            if ((1..3).none { viewModel.snapshotBitmap(it) != null }) {
                viewModel.reportSnapshotResult(false, unavailable = true)
            } else {
                showExportChoices = true
            }
        } else {
            exportChannel(selected)
        }
    }

    if (showExportChoices) {
        AlertDialog(
            onDismissRequest = { showExportChoices = false },
            title = { Text(t("perception.snapshot.choose")) },
            text = {
                Column {
                    listOf(1 to "perception.tab.camera", 2 to "perception.tab.depth", 3 to "perception.tab.map").forEach { (channel, label) ->
                        TextButton(
                            onClick = { showExportChoices = false; exportChannel(channel) },
                            enabled = viewModel.snapshotBitmap(channel) != null,
                            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                        ) { Text(t(label)) }
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { showExportChoices = false }) { Text(t("perception.snapshot.cancel")) } },
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Bg)
            .padding(10.dp),
    ) {
        // Streamlined 2-Row Header: Title & Telemetry + Segmented Navigation Tabs
        PerceptionTopBar(
            state = state,
            onSelectChannel = { viewModel.selectChannel(it) },
            onTogglePause = { viewModel.togglePause() },
            onRefresh = { viewModel.restartLivePoll() },
            onSnapshot = exportSnapshot,
        )

        Spacer(Modifier.height(8.dp))

        // Snapshot notification banner
        AnimatedVisibility(
            visible = state.snapshotNotice != null,
            enter = fadeIn() + expandVertically(),
            exit = fadeOut() + shrinkVertically(),
        ) {
            val noticeColor = if (state.snapshotNotice == "SNAPSHOT_FAILED" || state.snapshotNotice == "SNAPSHOT_UNAVAILABLE") Amber else Green
            Surface(
                color = noticeColor.copy(alpha = 0.15f),
                shape = RoundedCornerShape(8.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, noticeColor.copy(alpha = 0.4f)),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 6.dp),
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        if (state.snapshotNotice == "SNAPSHOT_FAILED" || state.snapshotNotice == "SNAPSHOT_UNAVAILABLE") Icons.Default.ErrorOutline else Icons.Default.CheckCircle,
                        null,
                        tint = if (state.snapshotNotice == "SNAPSHOT_FAILED" || state.snapshotNotice == "SNAPSHOT_UNAVAILABLE") Amber else Green,
                        modifier = Modifier.size(15.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        when (state.snapshotNotice) {
                            "SNAPSHOT_DOCUMENT_SAVED" -> t("perception.snapshot.documentSaved")
                            "SNAPSHOT_FAILED" -> t("perception.snapshot.failed")
                            "SNAPSHOT_UNAVAILABLE" -> t("perception.snapshot.unavailable")
                            else -> t("perception.snapshot.saved")
                        },
                        color = if (state.snapshotNotice == "SNAPSHOT_FAILED") Amber else Text,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
        }

        // Viewport Switcher
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
        ) {
            when (state.selectedChannel) {
                0 -> MatrixGrid(
                    state = state,
                    onSelectTile = { viewModel.selectChannel(it) },
                )
                1 -> CameraView(
                    state = state,
                    onBack = { viewModel.selectChannel(0) },
                )
                2 -> DepthView(
                    state = state,
                    onTouch = { offset, w, h -> viewModel.onDepthTouch(offset, w, h) },
                    onToggleHeatmap = { viewModel.toggleHeatmap() },
                    onBack = { viewModel.selectChannel(0) },
                )
                3 -> SceneMapView(
                    state = state,
                    onToggleLayer = { viewModel.toggleLayer(it) },
                    onBack = { viewModel.selectChannel(0) },
                )
            }
        }
    }
}

/**
 * Clean & Compact 2-Row Header:
 * Row 1: Perception title + Live status + FPS/RTT stats + Controls (Play/Pause, Refresh, Snapshot)
 * Row 2: Segmented Pill Channel Selector
 */
@Composable
private fun PerceptionTopBar(
    state: PerceptionUiState,
    onSelectChannel: (Int) -> Unit,
    onTogglePause: () -> Unit,
    onRefresh: () -> Unit,
    onSnapshot: () -> Unit,
) {
    Column {
        // Row 1: Title, Telemetry, and Action Icons
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                PulsingStatusDot(
                    color = if (state.hasLiveFeed && !state.isPaused) Green else Amber,
                    size = 8.dp,
                )
                Spacer(Modifier.width(7.dp))
                Text(
                    t("perception.header.title"),
                    color = Text,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                )
            }

            // Telemetry & Quick actions
            Row(
                horizontalArrangement = Arrangement.spacedBy(5.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // Compact telemetry badge
                Surface(
                    color = Panel2,
                    shape = RoundedCornerShape(6.dp),
                    border = androidx.compose.foundation.BorderStroke(0.6.dp, LineSoft),
                ) {
                    Text(
                        "${state.fps} FPS · ${state.latencyMs}ms",
                        color = Muted,
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp),
                    )
                }

                // IconButton's default minimumInteractiveComponentSize keeps each
                // tap target at 48dp even though the visible chip stays compact.
                IconButton(
                    onClick = onTogglePause,
                    modifier = Modifier.size(48.dp),
                ) {
                    Box(
                        modifier = Modifier
                            .size(28.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .background(if (state.isPaused) Amber.copy(alpha = 0.2f) else Panel2),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            if (state.isPaused) Icons.Default.PlayArrow else Icons.Default.Pause,
                            contentDescription = "Toggle Live",
                            tint = if (state.isPaused) Amber else Green,
                            modifier = Modifier.size(15.dp),
                        )
                    }
                }

                IconButton(
                    onClick = onRefresh,
                    modifier = Modifier.size(48.dp),
                ) {
                    Box(
                        modifier = Modifier
                            .size(28.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .background(Panel2),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(Icons.Default.Refresh, "Refresh", tint = Cyan, modifier = Modifier.size(14.dp))
                    }
                }

                IconButton(
                    onClick = onSnapshot,
                    enabled = !state.isExportingSnapshot,
                    modifier = Modifier.size(48.dp),
                ) {
                    Box(
                        modifier = Modifier
                            .size(28.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .background(Panel2),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(Icons.Default.CameraAlt, t("perception.snapshot"), tint = Text, modifier = Modifier.size(14.dp))
                    }
                }
            }
        }

        Spacer(Modifier.height(8.dp))

        // Row 2: Segmented Pill Channel Tabs
        val tabs = listOf(
            t("perception.tab.all"),
            t("perception.tab.camera"),
            t("perception.tab.depth"),
            t("perception.tab.map"),
        )
        Surface(
            color = Panel,
            shape = RoundedCornerShape(8.dp),
            border = androidx.compose.foundation.BorderStroke(0.8.dp, LineSoft),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(3.dp),
                horizontalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                tabs.forEachIndexed { index, label ->
                    val isSelected = state.selectedChannel == index
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(28.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .background(
                                if (isSelected) Cyan.copy(alpha = 0.18f) else Color.Transparent
                            )
                            .then(
                                if (isSelected) Modifier.border(0.8.dp, Cyan.copy(alpha = 0.5f), RoundedCornerShape(6.dp))
                                else Modifier
                            )
                            .clickable { onSelectChannel(index) },
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            label,
                            fontSize = 11.5.sp,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                            color = if (isSelected) Cyan else Muted,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}

/**
 * Clean Perception Matrix Grid:
 * Row 1: Camera and Depth feeds side-by-side.
 * Row 2: 2D Scene Map spanning full width.
 * Tapping any tile smoothly opens its full view.
 */
@Composable
private fun MatrixGrid(
    state: PerceptionUiState,
    onSelectTile: (Int) -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Box(Modifier.weight(1f).fillMaxHeight()) {
                CameraTile(state = state, isExpanded = false, onSelect = { onSelectTile(1) })
            }
            Box(Modifier.weight(1f).fillMaxHeight()) {
                DepthTile(state = state, isExpanded = false, onTouch = { _, _, _ -> }, onSelect = { onSelectTile(2) })
            }
        }
        Box(
            modifier = Modifier
                .weight(1.2f)
                .fillMaxWidth(),
        ) {
            SceneMapTile(state = state, isExpanded = false, onSelect = { onSelectTile(3) })
        }
    }
}

/** Minimal Frosted Badge for Sensor Tiles */
@Composable
private fun SensorBadge(
    name: String,
    isOnline: Boolean,
    modifier: Modifier = Modifier,
) {
    Surface(
        color = Panel.copy(alpha = 0.85f),
        shape = RoundedCornerShape(4.dp),
        border = androidx.compose.foundation.BorderStroke(0.6.dp, LineSoft),
        modifier = modifier.padding(6.dp),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(5.dp)
                    .clip(CircleShape)
                    .background(if (isOnline) Green else Amber)
            )
            Spacer(Modifier.width(4.dp))
            Text(
                name,
                color = if (isOnline) Text else Muted,
                fontSize = 9.5.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}

/** RGB Camera Tile (Pure video feed with no corner HUD clutter) */
@Composable
private fun CameraTile(
    state: PerceptionUiState,
    isExpanded: Boolean,
    onSelect: () -> Unit,
    onBack: (() -> Unit)? = null,
) {
    CyberCard(
        showBrackets = false,
        cornerRadius = 10.dp,
        borderColor = LineSoft,
        modifier = Modifier
            .fillMaxSize()
            .clickable(enabled = !isExpanded) { onSelect() },
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            if (state.cameraBitmap != null) {
                Image(
                    bitmap = state.cameraBitmap.asImageBitmap(),
                    contentDescription = "RGB Camera",
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                )
            } else {
                // Clean standby placeholder
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Panel2),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Default.Videocam, null, tint = Dim, modifier = Modifier.size(28.dp))
                        Spacer(Modifier.height(4.dp))
                        Text(
                            t("perception.stream.standby"),
                            color = Muted,
                            fontSize = 10.5.sp,
                            fontFamily = FontFamily.Monospace,
                        )
                    }
                }
            }

            // Top Header: Badge on left, Expand/Back on right
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.TopStart),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SensorBadge(
                    name = if (state.cameraInfo.isNotBlank()) "RGB · ${state.cameraInfo}" else "RGB CAM",
                    isOnline = state.cameraBitmap != null,
                )

                if (isExpanded && onBack != null) {
                    // 48dp tap target (via IconButton's own size) around the
                    // same compact 24dp visual chip, so the back/collapse
                    // action meets the touch-target minimum on narrow screens.
                    IconButton(
                        onClick = onBack,
                        modifier = Modifier
                            .padding(4.dp)
                            .size(48.dp),
                    ) {
                        Box(
                            modifier = Modifier
                                .size(24.dp)
                                .clip(RoundedCornerShape(4.dp))
                                .background(Panel.copy(alpha = 0.85f)),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(Icons.Default.FullscreenExit, "Collapse", tint = Cyan, modifier = Modifier.size(15.dp))
                        }
                    }
                } else if (!isExpanded) {
                    Icon(
                        Icons.Default.Fullscreen,
                        "Expand",
                        tint = Dim,
                        modifier = Modifier
                            .padding(8.dp)
                            .size(16.dp),
                    )
                }
            }
        }
    }
}

/** Full Camera View */
@Composable
private fun CameraView(
    state: PerceptionUiState,
    onBack: () -> Unit,
) {
    CameraTile(
        state = state,
        isExpanded = true,
        onSelect = {},
        onBack = onBack,
    )
}

/** Depth Heatmap Tile with Interactive Touch Probe */
@Composable
private fun DepthTile(
    state: PerceptionUiState,
    isExpanded: Boolean,
    onTouch: (Offset, Float, Float) -> Unit,
    onSelect: () -> Unit,
    onBack: (() -> Unit)? = null,
) {
    var canvasSize by remember { mutableStateOf(Size.Zero) }

    CyberCard(
        showBrackets = false,
        cornerRadius = 10.dp,
        borderColor = LineSoft,
        modifier = Modifier
            .fillMaxSize()
            .clickable(enabled = !isExpanded) { onSelect() },
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(isExpanded) {
                    if (isExpanded) {
                        detectTapGestures { offset ->
                            onTouch(offset, canvasSize.width, canvasSize.height)
                        }
                    }
                }
                .pointerInput(isExpanded) {
                    if (isExpanded) {
                        detectDragGestures { change, _ ->
                            change.consume()
                            onTouch(change.position, canvasSize.width, canvasSize.height)
                        }
                    }
                },
        ) {
            if (state.depthBitmap != null) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black),
                    contentAlignment = Alignment.Center,
                ) {
                    Image(
                        bitmap = state.depthBitmap.asImageBitmap(),
                        contentDescription = "Depth Map",
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxSize(),
                        alignment = Alignment.Center,
                    )
                }
            } else {
                // Clean standby placeholder
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Panel2),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Default.Sensors, null, tint = Dim, modifier = Modifier.size(28.dp))
                        Spacer(Modifier.height(4.dp))
                        Text(
                            t("perception.stream.standby"),
                            color = Muted,
                            fontSize = 10.5.sp,
                            fontFamily = FontFamily.Monospace,
                        )
                    }
                }
            }

            // Touch probe reticle (only visible when tapped in expanded view)
            if (isExpanded) {
                Canvas(modifier = Modifier.fillMaxSize()) {
                    canvasSize = size
                    state.depthProbeOffset?.let { probe ->
                        val r = 16.dp.toPx()
                        drawCircle(color = NeonCyan, radius = r, center = probe, style = Stroke(width = 2.dp.toPx()))
                        drawCircle(color = NeonCyan.copy(alpha = 0.25f), radius = r * 1.4f, center = probe)
                        drawLine(color = NeonCyan, start = Offset(probe.x - r - 5f, probe.y), end = Offset(probe.x + r + 5f, probe.y), strokeWidth = 1.5f)
                        drawLine(color = NeonCyan, start = Offset(probe.x, probe.y - r - 5f), end = Offset(probe.x, probe.y + r + 5f), strokeWidth = 1.5f)
                    }
                }
            }

            // Top Header: Badge on left, Expand/Back on right
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.TopStart),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val depthLabel = if (state.depthInfo.isNotBlank()) {
                    "DEPTH · ${state.depthInfo}"
                } else if (state.depthHeatmapEnabled) {
                    "DEPTH · TURBO"
                } else {
                    "DEPTH · RAW"
                }
                SensorBadge(
                    name = depthLabel,
                    isOnline = state.depthBitmap != null,
                )

                if (isExpanded && onBack != null) {
                    // 48dp tap target (via IconButton's own size) around the
                    // same compact 24dp visual chip, so the back/collapse
                    // action meets the touch-target minimum on narrow screens.
                    IconButton(
                        onClick = onBack,
                        modifier = Modifier
                            .padding(4.dp)
                            .size(48.dp),
                    ) {
                        Box(
                            modifier = Modifier
                                .size(24.dp)
                                .clip(RoundedCornerShape(4.dp))
                                .background(Panel.copy(alpha = 0.85f)),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(Icons.Default.FullscreenExit, "Collapse", tint = Cyan, modifier = Modifier.size(15.dp))
                        }
                    }
                } else if (!isExpanded) {
                    Icon(
                        Icons.Default.Fullscreen,
                        "Expand",
                        tint = Dim,
                        modifier = Modifier
                            .padding(8.dp)
                            .size(16.dp),
                    )
                }
            }
        }
    }
}

/** Full Depth View with Decoupled Bottom Controls */
@Composable
private fun DepthView(
    state: PerceptionUiState,
    onTouch: (Offset, Float, Float) -> Unit,
    onToggleHeatmap: () -> Unit,
    onBack: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        // Main Depth Canvas
        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            DepthTile(
                state = state,
                isExpanded = true,
                onTouch = onTouch,
                onSelect = {},
                onBack = onBack,
            )
        }

        Spacer(Modifier.height(8.dp))

        // External Bottom Control Card (Distance Readout & Mode Switcher)
        Surface(
            color = Panel,
            shape = RoundedCornerShape(8.dp),
            border = androidx.compose.foundation.BorderStroke(0.8.dp, LineSoft),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // Distance probe readout
                val dist = state.depthProbeDistance
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Default.Adjust,
                        contentDescription = null,
                        tint = if (dist != null) Cyan else Muted,
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        if (dist != null) t("perception.depth.probe", dist) else t("perception.depth.hint"),
                        color = if (dist != null) Cyan else Muted,
                        fontSize = 12.sp,
                        fontWeight = if (dist != null) FontWeight.Bold else FontWeight.Normal,
                    )
                }

                // Heatmap mode toggle button
                Surface(
                    color = if (state.depthHeatmapEnabled) Cyan.copy(alpha = 0.18f) else Panel2,
                    shape = RoundedCornerShape(5.dp),
                    border = androidx.compose.foundation.BorderStroke(
                        0.8.dp,
                        if (state.depthHeatmapEnabled) Cyan.copy(alpha = 0.5f) else LineSoft,
                    ),
                    modifier = Modifier.clickable { onToggleHeatmap() },
                ) {
                    Text(
                        if (state.depthHeatmapEnabled) "TURBO" else "GRAYSCALE",
                        color = if (state.depthHeatmapEnabled) Cyan else Muted,
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    )
                }
            }
        }
    }
}

/** 2D Semantic Scene / Occupancy Grid Map Tile (World-aligned coordinate projection) */
@Composable
private fun SceneMapTile(
    state: PerceptionUiState,
    isExpanded: Boolean,
    onSelect: () -> Unit,
    onBack: (() -> Unit)? = null,
) {
    CyberCard(
        showBrackets = false,
        cornerRadius = 10.dp,
        borderColor = LineSoft,
        modifier = Modifier
            .fillMaxSize()
            .clickable(enabled = !isExpanded) { onSelect() },
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val w = size.width
                val h = size.height

                val occ = state.occupancyGrid
                val boundsMinX: Float
                val boundsMaxX: Float
                val boundsMinY: Float
                val boundsMaxY: Float
                if (occ != null) {
                    boundsMinX = occ.originX
                    boundsMaxX = occ.originX + occ.width * occ.resolution
                    boundsMinY = occ.originY
                    boundsMaxY = occ.originY + occ.height * occ.resolution
                } else {
                    val rx = state.robotPose?.x ?: 0f
                    val ry = state.robotPose?.y ?: 0f
                    boundsMinX = rx - 2.5f
                    boundsMaxX = rx + 2.5f
                    boundsMinY = ry - 2.5f
                    boundsMaxY = ry + 2.5f
                }

                val spanX = (boundsMaxX - boundsMinX).coerceAtLeast(0.5f)
                val spanY = (boundsMaxY - boundsMinY).coerceAtLeast(0.5f)
                val centerX = (boundsMinX + boundsMaxX) / 2f
                val centerY = (boundsMinY + boundsMaxY) / 2f

                val pad = if (isExpanded) 20.dp.toPx() else 12.dp.toPx()
                val scale = minOf((w - pad * 2) / spanX, (h - pad * 2) / spanY)

                fun toX(x: Float): Float = w / 2f + (x - centerX) * scale
                fun toY(y: Float): Float = h / 2f - (y - centerY) * scale

                // Layer 0: Subtle background grid
                val gridSizeMeters = 1.0f
                val startGridX = (kotlin.math.floor(boundsMinX / gridSizeMeters) * gridSizeMeters).toInt()
                val endGridX = (kotlin.math.ceil(boundsMaxX / gridSizeMeters) * gridSizeMeters).toInt()
                for (gx in startGridX..endGridX) {
                    val px = toX(gx.toFloat())
                    drawLine(Color(0x0F5FCDD8), Offset(px, 0f), Offset(px, h), 1f)
                }
                val startGridY = (kotlin.math.floor(boundsMinY / gridSizeMeters) * gridSizeMeters).toInt()
                val endGridY = (kotlin.math.ceil(boundsMaxY / gridSizeMeters) * gridSizeMeters).toInt()
                for (gy in startGridY..endGridY) {
                    val py = toY(gy.toFloat())
                    drawLine(Color(0x0F5FCDD8), Offset(0f, py), Offset(w, py), 1f)
                }

                // Layer 1: Base Occupancy Grid Map Image (properly positioned and scaled in world coords)
                if (state.sceneLayers.map && occ != null) {
                    val left = toX(occ.originX).roundToInt()
                    val top = toY(occ.originY + occ.height * occ.resolution).roundToInt()
                    val dstW = (occ.width * occ.resolution * scale).roundToInt()
                    val dstH = (occ.height * occ.resolution * scale).roundToInt()
                    drawImage(
                        image = occ.bitmap.asImageBitmap(),
                        dstOffset = IntOffset(left, top),
                        dstSize = IntSize(dstW, dstH),
                        filterQuality = FilterQuality.None,
                    )
                }

                // Layer 2: Room Regions
                if (state.sceneLayers.regions) {
                    val regionPalette = listOf(
                        Color(0x225B8DEF),
                        Color(0x2235E0A0),
                        Color(0x22C58BF2),
                        Color(0x225AD1E6),
                    )
                    val strokePalette = listOf(
                        Color(0xFF5B8DEF),
                        Color(0xFF35E0A0),
                        Color(0xFFC58BF2),
                        Color(0xFF5AD1E6),
                    )

                    state.regions.forEachIndexed { i, region ->
                        if (region.points.size >= 3) {
                            val path = Path()
                            region.points.forEachIndexed { pIdx, pt ->
                                val sx = toX(pt.x)
                                val sy = toY(pt.y)
                                if (pIdx == 0) path.moveTo(sx, sy) else path.lineTo(sx, sy)
                            }
                            path.close()
                            drawPath(path, color = regionPalette[i % regionPalette.size])
                            drawPath(path, color = strokePalette[i % strokePalette.size], style = Stroke(width = 1.5f))
                        }
                    }
                }

                // Layer 3: Laser scan overlay on map (connected contours + hit dots matching Web client)
                if (state.sceneLayers.lidar && state.lidarPoints.isNotEmpty()) {
                    val rPose = state.robotPose
                    val rx = rPose?.x ?: 0f
                    val ry = rPose?.y ?: 0f
                    val rHeading = rPose?.headingRad ?: 0f

                    val hitPoints = state.lidarPoints.map { pt ->
                        val scanAngle = Math.toRadians(pt.angleDeg.toDouble()).toFloat() + rHeading
                        val wx = rx + pt.distanceMeters * cos(scanAngle.toDouble()).toFloat()
                        val wy = ry + pt.distanceMeters * sin(scanAngle.toDouble()).toFloat()
                        Triple(toX(wx), toY(wy), pt.distanceMeters)
                    }

                    // Connected obstacle barrier wall lines
                    val wallPath = Path()
                    var connected = false
                    val maxConnectDist = 20.dp.toPx()
                    for (i in hitPoints.indices) {
                        val (hx, hy, _) = hitPoints[i]
                        val prev = if (i > 0) hitPoints[i - 1] else null
                        if (prev != null && kotlin.math.hypot(hx - prev.first, hy - prev.second) < maxConnectDist) {
                            if (!connected) {
                                wallPath.moveTo(prev.first, prev.second)
                                connected = true
                            }
                            wallPath.lineTo(hx, hy)
                        } else {
                            connected = false
                        }
                    }
                    drawPath(
                        path = wallPath,
                        color = Color(0x9935E0A0),
                        style = Stroke(width = 1.8.dp.toPx(), cap = StrokeCap.Round),
                    )

                    // Laser hit dots
                    val dotRadius = if (isExpanded) 2.2.dp.toPx() else 1.5.dp.toPx()
                    hitPoints.forEach { (hx, hy, dist) ->
                        val c = when {
                            dist < 0.6f -> Color(0xFFF2726F)
                            dist < 1.2f -> Color(0xFFFFD166)
                            else -> Color(0xFF35E0A0)
                        }
                        drawCircle(color = c, radius = dotRadius, center = Offset(hx, hy))
                    }
                }

                // Layer 4: Detected Objects
                if (state.sceneLayers.objects) {
                    val objColors = listOf(
                        Color(0xFF5B8DEF),
                        Color(0xFF35E0A0),
                        Color(0xFFFFD166),
                        Color(0xFFF2726F),
                        Color(0xFFC58BF2),
                    )
                    state.objects.forEachIndexed { i, obj ->
                        val ox = toX(obj.x)
                        val oy = toY(obj.y)
                        val ow = maxOf(10f, obj.width * scale)
                        val oh = maxOf(10f, obj.height * scale)
                        val color = objColors[i % objColors.size]

                        drawRect(
                            color = color.copy(alpha = 0.25f),
                            topLeft = Offset(ox - ow / 2f, oy - oh / 2f),
                            size = Size(ow, oh),
                        )
                        drawRect(
                            color = color,
                            topLeft = Offset(ox - ow / 2f, oy - oh / 2f),
                            size = Size(ow, oh),
                            style = Stroke(width = 1.5f),
                        )
                    }
                }

                // Layer 5: Robot Pose Marker & Heading Arrow
                if (state.sceneLayers.robot) {
                    val rPose = state.robotPose
                    val rx = toX(rPose?.x ?: 0f)
                    val ry = toY(rPose?.y ?: 0f)
                    val rYaw = rPose?.headingRad ?: 0f

                    drawCircle(color = Cyan.copy(alpha = 0.25f), radius = 14.dp.toPx(), center = Offset(rx, ry))
                    drawCircle(color = Cyan, radius = 6.dp.toPx(), center = Offset(rx, ry))

                    val arrowLen = if (isExpanded) 20.dp.toPx() else 14.dp.toPx()
                    val arrowEndX = rx + (arrowLen * cos(rYaw.toDouble())).toFloat()
                    val arrowEndY = ry - (arrowLen * sin(rYaw.toDouble())).toFloat()

                    drawLine(
                        color = Color(0xFFFFD166),
                        start = Offset(rx, ry),
                        end = Offset(arrowEndX, arrowEndY),
                        strokeWidth = 2.5f,
                        cap = StrokeCap.Round,
                    )
                }
            }

            // Top Header: Badge on left, Expand/Back on right
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.TopStart),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SensorBadge(
                    name = "SCENE MAP",
                    isOnline = state.occupancyGrid != null || state.mapBitmap != null || state.robotPose != null,
                )

                if (isExpanded && onBack != null) {
                    // 48dp tap target (via IconButton's own size) around the
                    // same compact 24dp visual chip, so the back/collapse
                    // action meets the touch-target minimum on narrow screens.
                    IconButton(
                        onClick = onBack,
                        modifier = Modifier
                            .padding(4.dp)
                            .size(48.dp),
                    ) {
                        Box(
                            modifier = Modifier
                                .size(24.dp)
                                .clip(RoundedCornerShape(4.dp))
                                .background(Panel.copy(alpha = 0.85f)),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(Icons.Default.FullscreenExit, "Collapse", tint = Cyan, modifier = Modifier.size(15.dp))
                        }
                    }
                } else if (!isExpanded) {
                    Icon(
                        Icons.Default.Fullscreen,
                        "Expand",
                        tint = Dim,
                        modifier = Modifier
                            .padding(8.dp)
                            .size(16.dp),
                    )
                }
            }
        }
    }
}

/** Full Scene Map View with Decoupled Bottom Layer Toolbar */
@Composable
private fun SceneMapView(
    state: PerceptionUiState,
    onToggleLayer: (String) -> Unit,
    onBack: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            SceneMapTile(
                state = state,
                isExpanded = true,
                onSelect = {},
                onBack = onBack,
            )
        }

        Spacer(Modifier.height(8.dp))

        // External Layer Toggle Toolbar (Never blocks map view)
        Surface(
            color = Panel,
            shape = RoundedCornerShape(8.dp),
            border = androidx.compose.foundation.BorderStroke(0.8.dp, LineSoft),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val layers = state.sceneLayers
                LayerPill(label = t("perception.layer.map"), active = layers.map, onClick = { onToggleLayer("map") }, modifier = Modifier.weight(1f))
                LayerPill(label = t("perception.layer.regions"), active = layers.regions, onClick = { onToggleLayer("regions") }, modifier = Modifier.weight(1f))
                LayerPill(label = t("perception.layer.objects"), active = layers.objects, onClick = { onToggleLayer("objects") }, modifier = Modifier.weight(1f))
                LayerPill(label = t("perception.layer.robot"), active = layers.robot, onClick = { onToggleLayer("robot") }, modifier = Modifier.weight(1f))
                LayerPill(label = t("perception.layer.lidar"), active = layers.lidar, onClick = { onToggleLayer("lidar") }, modifier = Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun LayerPill(
    label: String,
    active: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Clickable area meets the 48dp touch-target minimum even though the
    // visible pill stays compact at 26dp.
    Box(
        modifier = modifier
            .heightIn(min = 48.dp)
            .clickable { onClick() },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(26.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(if (active) Cyan.copy(alpha = 0.18f) else Panel2)
                .then(
                    if (active) Modifier.border(0.8.dp, Cyan.copy(alpha = 0.6f), RoundedCornerShape(4.dp))
                    else Modifier.border(0.6.dp, LineSoft, RoundedCornerShape(4.dp))
                ),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                label,
                color = if (active) Cyan else Muted,
                fontSize = 11.sp,
                fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
            )
        }
    }
}
