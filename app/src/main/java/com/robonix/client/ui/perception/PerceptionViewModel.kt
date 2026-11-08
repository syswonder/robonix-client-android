package com.robonix.client.ui.perception

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.SystemClock
import kotlin.math.roundToInt
import dagger.hilt.android.qualifiers.ApplicationContext
import androidx.compose.ui.geometry.Offset
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.robonix.client.domain.OccupancyGridData
import com.robonix.client.domain.PerceptionRepository
import com.robonix.client.domain.RobotPose
import com.robonix.client.domain.SceneObject
import com.robonix.client.domain.SceneRegion
import com.robonix.client.domain.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.inject.Inject
import kotlin.math.sin

data class LidarScanPoint(
    val angleDeg: Float,
    val distanceMeters: Float,
    val intensity: Float = 1.0f,
)

data class SceneLayers(
    val map: Boolean = true,
    val regions: Boolean = true,
    val objects: Boolean = true,
    val robot: Boolean = true,
    val lidar: Boolean = true,
)

data class PerceptionUiState(
    val isConnected: Boolean = false,
    val selectedChannel: Int = 0, // 0: All (Matrix), 1: Camera, 2: Depth, 3: Map/Scene
    val layoutMode: String = "matrix", // "matrix", "split"
    val fps: Int = 30,
    val latencyMs: Int = 16,
    val cameraStandby: Boolean = false,
    val isPaused: Boolean = false,
    val depthHeatmapEnabled: Boolean = true,
    val depthProbeOffset: Offset? = null,
    val depthProbeDistance: Float? = null,
    val lidarPoints: List<LidarScanPoint> = emptyList(),
    val lidarSweepAngle: Float = 0f,
    val robotHeadingDeg: Float = 0f,
    val snapshotNotice: String? = null,
    val isExportingSnapshot: Boolean = false,
    val mapResolutionMeters: Float = 0.05f,
    val cameraBitmap: Bitmap? = null,
    val cameraInfo: String = "",
    val depthBitmap: Bitmap? = null,
    val depthInfo: String = "",
    val mapBitmap: Bitmap? = null,
    val occupancyGrid: OccupancyGridData? = null,
    val lidarInfo: String = "",
    val sceneInfo: String = "",
    val robotPose: RobotPose? = null,
    val objects: List<SceneObject> = emptyList(),
    val regions: List<SceneRegion> = emptyList(),
    val sceneLayers: SceneLayers = SceneLayers(),
    val hasLiveFeed: Boolean = false,
    val activeStreamCount: Int = 0,
)

@HiltViewModel
class PerceptionViewModel @Inject constructor(
    private val perceptionRepository: PerceptionRepository,
    private val settingsRepository: SettingsRepository,
    @ApplicationContext private val applicationContext: Context,
) : ViewModel() {

    private val _uiState = MutableStateFlow(PerceptionUiState())
    val uiState: StateFlow<PerceptionUiState> = _uiState.asStateFlow()

    private var pendingExport: Pair<Bitmap, PerceptionExportChannel>? = null
    private var snapshotNoticeJob: Job? = null
    private var animationJob: Job? = null
    private var livePollJob: Job? = null
    private var currentHost: String = ""
    private var currentPort: Int = 50051

    init {
        startAnimationLoop()
        observeSettingsAndPoll()
    }

    private fun observeSettingsAndPoll() {
        viewModelScope.launch {
            settingsRepository.observeSettings().collect { s ->
                currentHost = s.robotHost
                currentPort = s.atlasPort
                restartLivePoll()
            }
        }
    }

    fun restartLivePoll() {
        livePollJob?.cancel()
        if (currentHost.isBlank()) return

        livePollJob = viewModelScope.launch {
            var lastFrameTime = 0L
            while (isActive) {
                if (!_uiState.value.isPaused) {
                    try {
                        val snapshot = perceptionRepository.fetchPerceptionSnapshot(currentHost, currentPort)
                        if (snapshot.isAvailable) {
                            val now = SystemClock.uptimeMillis()
                            val calculatedFps = if (lastFrameTime > 0L) {
                                val delta = now - lastFrameTime
                                if (delta > 0) ((1000f / delta).roundToInt()).coerceIn(1, 60) else _uiState.value.fps
                            } else {
                                if (snapshot.cameraBitmap != null) 1 else _uiState.value.fps
                            }
                            lastFrameTime = now

                            var streams = 0
                            if (snapshot.cameraBitmap != null) streams++
                            if (snapshot.depthBitmap != null) streams++
                            if (snapshot.lidarPoints.isNotEmpty()) streams++
                            if (snapshot.robotPose != null || snapshot.objects.isNotEmpty() || snapshot.mapBitmap != null) streams++

                            _uiState.update { current ->
                                current.copy(
                                    cameraBitmap = snapshot.cameraBitmap ?: current.cameraBitmap,
                                    cameraInfo = if (snapshot.cameraInfo.isNotBlank()) snapshot.cameraInfo else current.cameraInfo,
                                    depthBitmap = snapshot.depthBitmap ?: current.depthBitmap,
                                    depthInfo = if (snapshot.depthInfo.isNotBlank()) snapshot.depthInfo else current.depthInfo,
                                    mapBitmap = snapshot.occupancyGrid?.bitmap ?: snapshot.mapBitmap ?: current.mapBitmap,
                                    occupancyGrid = snapshot.occupancyGrid ?: current.occupancyGrid,
                                    lidarPoints = if (snapshot.lidarPoints.isNotEmpty()) snapshot.lidarPoints else current.lidarPoints,
                                    lidarInfo = if (snapshot.lidarInfo.isNotBlank()) snapshot.lidarInfo else current.lidarInfo,
                                    robotPose = snapshot.robotPose ?: current.robotPose,
                                    objects = if (snapshot.objects.isNotEmpty()) snapshot.objects else current.objects,
                                    regions = if (snapshot.regions.isNotEmpty()) snapshot.regions else current.regions,
                                    sceneInfo = if (snapshot.sceneInfo.isNotBlank()) snapshot.sceneInfo else current.sceneInfo,
                                    hasLiveFeed = true,
                                    isConnected = true,
                                    activeStreamCount = maxOf(streams, 1),
                                    latencyMs = snapshot.latencyMs,
                                    fps = calculatedFps,
                                    robotHeadingDeg = snapshot.robotPose?.let {
                                        Math.toDegrees(it.headingRad.toDouble()).toFloat()
                                    } ?: current.robotHeadingDeg,
                                )
                            }
                        } else {
                            _uiState.update { it.copy(hasLiveFeed = false, activeStreamCount = 0) }
                        }
                    } catch (_: Exception) {
                        _uiState.update { it.copy(hasLiveFeed = false, activeStreamCount = 0) }
                    }
                }
                delay(1000L)
            }
        }
    }

    private fun startAnimationLoop() {
        animationJob?.cancel()
        animationJob = viewModelScope.launch {
            var sweep = 0f
            while (isActive) {
                delay(33) // ~30 FPS UI animation
                sweep = (sweep + 4.5f) % 360f

                _uiState.update { current ->
                    current.copy(lidarSweepAngle = sweep)
                }
            }
        }
    }

    fun selectChannel(channel: Int) {
        _uiState.update { it.copy(selectedChannel = channel) }
    }

    fun togglePause() {
        _uiState.update { it.copy(isPaused = !it.isPaused) }
    }

    fun toggleHeatmap() {
        _uiState.update { it.copy(depthHeatmapEnabled = !it.depthHeatmapEnabled) }
    }

    fun toggleLayer(layer: String) {
        _uiState.update { current ->
            val l = current.sceneLayers
            val updated = when (layer) {
                "map" -> l.copy(map = !l.map)
                "regions" -> l.copy(regions = !l.regions)
                "objects" -> l.copy(objects = !l.objects)
                "robot" -> l.copy(robot = !l.robot)
                "lidar" -> l.copy(lidar = !l.lidar)
                else -> l
            }
            current.copy(sceneLayers = updated)
        }
    }

    fun onDepthTouch(offset: Offset, canvasWidth: Float, canvasHeight: Float) {
        if (canvasWidth <= 0 || canvasHeight <= 0) return
        val currentBitmap = _uiState.value.depthBitmap
        if (currentBitmap == null || currentBitmap.isRecycled) {
            _uiState.update { it.copy(depthProbeOffset = null, depthProbeDistance = null) }
            return
        }
        val bmpX = ((offset.x / canvasWidth) * currentBitmap.width).toInt().coerceIn(0, currentBitmap.width - 1)
        val bmpY = ((offset.y / canvasHeight) * currentBitmap.height).toInt().coerceIn(0, currentBitmap.height - 1)
        val pixel = currentBitmap.getPixel(bmpX, bmpY)
        val r = (pixel shr 16) and 0xFF
        val g = (pixel shr 8) and 0xFF
        val b = pixel and 0xFF
        // Grayscale / brightness: nearer = brighter (255 ~ 0.3m, 0 ~ 5.0m)
        val brightness = (r * 0.299f + g * 0.587f + b * 0.114f) / 255f
        val d = 0.3f + (1.0f - brightness) * 4.7f
        val distance = (d * 100).toInt() / 100f
        _uiState.update {
            it.copy(
                depthProbeOffset = offset,
                depthProbeDistance = distance,
            )
        }
    }

    fun clearDepthProbe() {
        _uiState.update { it.copy(depthProbeOffset = null, depthProbeDistance = null) }
    }

    internal fun snapshotBitmap(channel: Int = _uiState.value.selectedChannel): Pair<Bitmap, PerceptionExportChannel>? {
        val state = _uiState.value
        val exportChannel = when (channel) {
            1 -> PerceptionExportChannel.CAMERA
            2 -> PerceptionExportChannel.DEPTH
            3 -> PerceptionExportChannel.MAP
            else -> return null
        }
        val bitmap = exportBitmapForChannel(state, exportChannel) ?: return null
        return bitmap to exportChannel
    }

    internal fun beginSnapshot(channel: Int): String? {
        if (_uiState.value.isExportingSnapshot) return null
        val export = snapshotBitmap(channel)
        if (export == null) {
            reportSnapshotResult(false, unavailable = true)
            return null
        }
        _uiState.update { it.copy(isExportingSnapshot = true, snapshotNotice = null) }
        if (requiresDocumentPicker()) {
            pendingExport = export
            return "robonix_${export.second.fileStem}_${System.currentTimeMillis()}.png"
        }
        viewModelScope.launch {
            try {
                reportSnapshotResult(saveBitmapToGallery(applicationContext.contentResolver, export.first, export.second))
            } finally {
                _uiState.update { it.copy(isExportingSnapshot = false) }
            }
        }
        return null
    }

    internal fun finishDocumentSnapshot(uri: Uri?) {
        val export = pendingExport
        pendingExport = null
        if (uri == null) {
            _uiState.update { it.copy(isExportingSnapshot = false) }
            return
        }
        _uiState.update { it.copy(isExportingSnapshot = true) }
        viewModelScope.launch {
            try {
                val success = if (export != null) {
                    writeBitmapToDocument(applicationContext.contentResolver, uri, export.first)
                } else {
                    deleteSnapshotDocument(applicationContext.contentResolver, uri)
                    false
                }
                reportSnapshotResult(success, document = true)
            } finally {
                _uiState.update { it.copy(isExportingSnapshot = false) }
            }
        }
    }

    internal fun snapshotPickerFailed() {
        pendingExport = null
        _uiState.update { it.copy(isExportingSnapshot = false) }
        reportSnapshotResult(false)
    }

    fun reportSnapshotResult(success: Boolean, document: Boolean = false, unavailable: Boolean = false) {
        _uiState.update { it.copy(snapshotNotice = if (unavailable) "SNAPSHOT_UNAVAILABLE" else if (success) {
            if (document) "SNAPSHOT_DOCUMENT_SAVED" else "SNAPSHOT_TAKEN"
        } else "SNAPSHOT_FAILED") }
        snapshotNoticeJob?.cancel()
        snapshotNoticeJob = viewModelScope.launch {
            delay(3000)
            _uiState.update { current ->
                if (current.snapshotNotice in setOf("SNAPSHOT_TAKEN", "SNAPSHOT_DOCUMENT_SAVED", "SNAPSHOT_FAILED", "SNAPSHOT_UNAVAILABLE")) {
                    current.copy(snapshotNotice = null)
                } else current
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        animationJob?.cancel()
        livePollJob?.cancel()
    }
}
