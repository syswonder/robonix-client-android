package com.robonix.client.domain

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import androidx.compose.ui.geometry.Offset
import com.robonix.client.AppLog
import com.robonix.client.data.grpc.AtlasClient
import com.robonix.client.ui.perception.LidarScanPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

data class SceneObject(
    val id: String,
    val label: String,
    val x: Float,
    val y: Float,
    val width: Float = 0.5f,
    val height: Float = 0.5f,
    val yaw: Float = 0f,
)

data class SceneRegion(
    val name: String,
    val kind: String = "room",
    val colorHex: String = "#35e0a0",
    val points: List<Offset> = emptyList(),
)

data class RobotPose(
    val x: Float = 0f,
    val y: Float = 0f,
    val headingRad: Float = 0f,
    val roomName: String = "",
)

data class OccupancyGridData(
    val bitmap: Bitmap,
    val originX: Float,
    val originY: Float,
    val resolution: Float,
    val width: Int,
    val height: Int,
)

data class PerceptionDataSnapshot(
    val cameraBitmap: Bitmap? = null,
    val depthBitmap: Bitmap? = null,
    val lidarPoints: List<LidarScanPoint> = emptyList(),
    val mapBitmap: Bitmap? = null,
    val occupancyGrid: OccupancyGridData? = null,
    val robotPose: RobotPose? = null,
    val objects: List<SceneObject> = emptyList(),
    val regions: List<SceneRegion> = emptyList(),
    val isAvailable: Boolean = false,
    val latencyMs: Int = 0,
    val cameraInfo: String = "",
    val depthInfo: String = "",
    val lidarInfo: String = "",
    val sceneInfo: String = "",
)

@Singleton
class PerceptionRepository @Inject constructor(
    private val atlasClient: AtlasClient,
) {
    companion object {
        private const val MCP_PROTOCOL_VERSION = "2024-11-05"
        private const val MAP_UI_PORT = 50107
        private const val MAPPING_PORT = 8091
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }

    /**
     * Cache of discovered capabilities keyed by Atlas endpoint string.
     * Cleared for a given endpoint whenever Atlas reports no provider for a contract
     * (e.g. after a robot restart), so the next call re-probes automatically.
     */
    private val capabilityCache = ConcurrentHashMap<String, RobotCapabilitySet>()

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .followRedirects(true)
        .addInterceptor { chain ->
            val request = chain.request()
            val response = chain.proceed(request)
            // Handle redirects (301, 302, 307, 308) where the server advertises a loopback Location header
            if (response.isRedirect) {
                val location = response.header("Location")
                if (!location.isNullOrBlank()) {
                    val originalHost = request.url.host
                    val originalPort = request.url.port
                    val locUri = java.net.URI(location)
                    val locHost = locUri.host
                    val locPort = if (locUri.port != -1) locUri.port else originalPort
                    if (locHost == "127.0.0.1" || locHost == "localhost" || locHost == "0.0.0.0") {
                        val newUrl = "${locUri.scheme ?: "http"}://$originalHost:$locPort${locUri.rawPath ?: ""}"
                        val newReq = request.newBuilder()
                            .url(newUrl)
                            .build()
                        response.close()
                        return@addInterceptor chain.proceed(newReq)
                    }
                }
            }
            response
        }
        .build()

    private data class McpSession(
        val endpoint: String,
        val hostHeader: String?,
        var sessionId: String?,
    )

    // Keyed by "$atlasEndpoint::$contractId"
    private val sessionCache = ConcurrentHashMap<String, McpSession>()

    /**
     * Finds LiDAR capability using semantic wildcard matching.
     * Prioritizes snapshot tools first, then scan tools.
     */
    private fun findLidarContract(contracts: Set<String>): String? {
        contracts.firstOrNull { id ->
            val lower = id.lowercase()
            (lower.contains("lidar") || lower.contains("laser")) &&
                (lower.endsWith("snapshot") || lower.contains("snapshot"))
        }?.let { return it }

        contracts.firstOrNull { id ->
            val lower = id.lowercase()
            (lower.contains("lidar") || lower.contains("laser")) &&
                (lower.endsWith("scan") || lower.contains("scan"))
        }?.let { return it }

        return contracts.firstOrNull { id ->
            val lower = id.lowercase()
            lower.contains("lidar") || lower.contains("laser")
        }
    }

    /**
     * Finds RGB camera capability using semantic wildcard matching.
     * Strictly prioritizes snapshot tools over raw topic/stream names like "rgb" or "image".
     */
    private fun findCameraRgbContract(contracts: Set<String>): String? {
        // Priority 1: explicitly a snapshot contract (e.g. robonix/primitive/camera/snapshot)
        contracts.firstOrNull { id ->
            val lower = id.lowercase()
            lower.contains("camera") && !lower.contains("depth") &&
                (lower.endsWith("snapshot") || lower.contains("snapshot"))
        }?.let { return it }

        // Priority 2: explicitly an image contract
        contracts.firstOrNull { id ->
            val lower = id.lowercase()
            lower.contains("camera") && !lower.contains("depth") &&
                (lower.contains("image") || lower.contains("rgb"))
        }?.let { return it }

        // Priority 3: any remaining non-depth camera contract
        return contracts.firstOrNull { id ->
            val lower = id.lowercase()
            lower.contains("camera") && !lower.contains("depth")
        }
    }

    /**
     * Finds depth camera capability using semantic wildcard matching.
     * Strictly prioritizes snapshot tools over raw topic/stream names.
     */
    private fun findCameraDepthContract(contracts: Set<String>): String? {
        // Priority 1: explicitly a depth snapshot contract
        contracts.firstOrNull { id ->
            val lower = id.lowercase()
            (lower.contains("camera") || lower.contains("depth")) && lower.contains("depth") &&
                (lower.endsWith("snapshot") || lower.contains("snapshot"))
        }?.let { return it }

        // Priority 2: image/raw depth
        return contracts.firstOrNull { id ->
            val lower = id.lowercase()
            (lower.contains("camera") || lower.contains("depth")) && lower.contains("depth") &&
                (lower.contains("image") || lower.contains("raw"))
        }
    }

    private fun findSceneContract(allContracts: Set<String>, keyword: String): String? {
        return allContracts.firstOrNull { id ->
            val lower = id.lowercase()
            lower.contains("scene") && lower.contains(keyword)
        }
    }

    /**
     * Discovers robot capabilities dynamically using fast semantic wildcard matching.
     * Strictly separates MCP tool capabilities (for one-shot sensor RPCs) from general contracts.
     */
    private suspend fun discoverCapabilities(atlasEndpoint: String): RobotCapabilitySet {
        capabilityCache[atlasEndpoint]?.let { return it }

        return try {
            val providers = atlasClient.queryProviders(atlasEndpoint)
            
            // Contracts registered specifically under TRANSPORT_MCP (for sensor snapshots)
            val mcpContracts = providers
                .flatMap { it.capabilities }
                .filter { it.transport == "TRANSPORT_MCP" || it.transport.endsWith("MCP") }
                .map { it.contractId }
                .toSet()

            // All contracts across all transports (e.g. for scene, vitals, etc.)
            val allContracts = providers
                .flatMap { it.capabilities }
                .map { it.contractId }
                .toSet()

            // For MCP sensor calls, search MCP-capable contracts first, falling back to allContracts if empty
            val sensorContracts = if (mcpContracts.isNotEmpty()) mcpContracts else allContracts

            RobotCapabilitySet(
                cameraRgb     = findCameraRgbContract(sensorContracts),
                cameraDepth   = findCameraDepthContract(sensorContracts),
                lidarSnapshot = findLidarContract(sensorContracts),
                sceneRobot    = findSceneContract(allContracts, "robot"),
                sceneObjects  = findSceneContract(allContracts, "object"),
                sceneRegions  = findSceneContract(allContracts, "region"),
            ).also {
                capabilityCache[atlasEndpoint] = it
                AppLog.write(
                    "PERCEPTION",
                    "[$atlasEndpoint] discovered caps: " +
                        "cam=${it.cameraRgb?.substringAfterLast("/")} " +
                        "depth=${it.cameraDepth?.substringAfterLast("/")} " +
                        "lidar=${it.lidarSnapshot?.substringAfterLast("/")} " +
                        "scene=${it.sceneRobot != null}",
                )
            }
        } catch (e: Exception) {
            AppLog.write("PERCEPTION", "[$atlasEndpoint] capability discovery failed: ${e.message}")
            RobotCapabilitySet.EMPTY
        }
    }

    suspend fun fetchPerceptionSnapshot(robotHost: String, atlasPort: Int = 50051): PerceptionDataSnapshot =
        withContext(Dispatchers.IO) {
            val started = System.currentTimeMillis()
            val host = robotHost.trim()
            if (host.isBlank()) {
                return@withContext PerceptionDataSnapshot()
            }
            val atlasEndpoint = "$host:$atlasPort"

            // Discover (or retrieve from cache) what this robot body actually supports.
            val caps = discoverCapabilities(atlasEndpoint)

            var cameraBitmap: Bitmap? = null
            var cameraInfo = ""
            var depthBitmap: Bitmap? = null
            var depthInfo = ""
            var lidarPoints = emptyList<LidarScanPoint>()
            var lidarInfo = ""
            var robotPose: RobotPose? = null
            var objects = emptyList<SceneObject>()
            var regions = emptyList<SceneRegion>()
            var occupancyGrid: OccupancyGridData? = null
            var mapBitmap: Bitmap? = null
            var anySuccess = false

            // Fetch sensors in parallel directly from the configured robot host.
            // Each deferred is skipped when the corresponding capability is absent.
            coroutineScope {
                // 1. Camera RGB
                val cameraDeferred = async {
                    fetchCameraRgb(atlasEndpoint, host, caps)
                }

                // 2. Depth Camera
                val depthDeferred = async {
                    fetchCameraDepth(atlasEndpoint, host, caps)
                }

                // 3. LiDAR Scan
                val lidarDeferred = async {
                    fetchLidar(atlasEndpoint, host, caps)
                }

                // 4. Scene Context
                val sceneDeferred = async {
                    fetchScene(atlasEndpoint, host, caps)
                }

                // 5. Occupancy Map
                val mapDeferred = async {
                    fetchOccupancyMap(host)
                }

                // 1. Await Scene first so robotPose is available for lidar coordinate transformations
                try {
                    val res = sceneDeferred.await()
                    if (res != null) {
                        robotPose = res.robotPose
                        objects = res.objects
                        regions = res.regions
                        if (robotPose != null || objects.isNotEmpty() || regions.isNotEmpty()) {
                            anySuccess = true
                        }
                    }
                } catch (e: Exception) {
                    AppLog.write("PERCEPTION", "Scene fetch failed: ${e.message}")
                }

                // 2. Await Camera
                try {
                    val res = cameraDeferred.await()
                    if (res != null) {
                        cameraBitmap = res.first
                        cameraInfo = res.second
                        anySuccess = true
                    }
                } catch (e: Exception) {
                    AppLog.write("PERCEPTION", "Camera fetch failed: ${e.message}")
                }

                // 3. Await Depth
                try {
                    val res = depthDeferred.await()
                    if (res != null) {
                        depthBitmap = res.first
                        depthInfo = res.second
                        anySuccess = true
                    }
                } catch (e: Exception) {
                    AppLog.write("PERCEPTION", "Depth fetch failed: ${e.message}")
                }

                // 4. Camera Fallback: if either RGB or Depth wasn't fetched via MCP,
                // fall back to scene HTTP service (http://$host:50107/api/camera)
                if (cameraBitmap == null || depthBitmap == null) {
                    try {
                        val fallback = fetchCameraFallback(host)
                        if (cameraBitmap == null && fallback.first != null) {
                            cameraBitmap = fallback.first!!.first
                            cameraInfo = fallback.first!!.second
                            anySuccess = true
                        }
                        if (depthBitmap == null && fallback.second != null) {
                            depthBitmap = fallback.second!!.first
                            depthInfo = fallback.second!!.second
                            anySuccess = true
                        }
                    } catch (e: Exception) {
                        AppLog.write("PERCEPTION", "Camera HTTP fallback failed: ${e.message}")
                    }
                }

                // 5. Await LiDAR
                try {
                    val res = lidarDeferred.await()
                    if (res != null) {
                        lidarPoints = res.first
                        lidarInfo = res.second
                        anySuccess = true
                    }
                } catch (e: Exception) {
                    AppLog.write("PERCEPTION", "LiDAR fetch failed: ${e.message}")
                }

                // 6. LiDAR Fallback: if MCP lidar snapshot is missing or failed (e.g. on Lite3 / MID-360),
                // fall back to mapping service range endpoint (http://$host:8091/api/range)
                if (lidarPoints.isEmpty()) {
                    try {
                        val fallback = fetchLidarFallback(host, robotPose)
                        if (fallback != null) {
                            lidarPoints = fallback.first
                            lidarInfo = fallback.second
                            anySuccess = true
                        }
                    } catch (e: Exception) {
                        AppLog.write("PERCEPTION", "LiDAR 8091 fallback failed: ${e.message}")
                    }
                }

                // 7. Await Map
                try {
                    val res = mapDeferred.await()
                    if (res != null) {
                        occupancyGrid = res
                        mapBitmap = res.bitmap
                        anySuccess = true
                    }
                } catch (e: Exception) {
                    AppLog.write("PERCEPTION", "Map fetch failed: ${e.message}")
                }
            }

            val elapsed = (System.currentTimeMillis() - started).toInt()
            val sceneParts = mutableListOf<String>()
            if (objects.isNotEmpty()) sceneParts.add("${objects.size} objects")
            if (regions.isNotEmpty()) sceneParts.add("${regions.size} regions")
            if (!robotPose?.roomName.isNullOrBlank()) sceneParts.add(robotPose!!.roomName)
            val sceneInfo = sceneParts.joinToString(" · ")

            PerceptionDataSnapshot(
                cameraBitmap = cameraBitmap,
                depthBitmap = depthBitmap,
                lidarPoints = lidarPoints,
                mapBitmap = mapBitmap,
                occupancyGrid = occupancyGrid,
                robotPose = robotPose,
                objects = objects,
                regions = regions,
                isAvailable = anySuccess,
                latencyMs = elapsed.coerceAtLeast(1),
                cameraInfo = cameraInfo,
                depthInfo = depthInfo,
                lidarInfo = lidarInfo,
                sceneInfo = sceneInfo,
            )
        }

    // ─────────────────────────────────────────────────────────────────────────────
    // Direct Sensor Fetching (Strictly using configured robot IP)
    // ─────────────────────────────────────────────────────────────────────────────

    /**
     * Fetches a one-shot RGB camera image using whichever contract the robot advertises.
     * Returns null silently when the robot has no camera capability.
     */
    private suspend fun fetchCameraRgb(
        atlasEndpoint: String,
        host: String,
        caps: RobotCapabilitySet,
    ): Pair<Bitmap, String>? {
        val contract = caps.cameraRgb ?: return null
        val tool = contract.substringAfterLast("/") // e.g. "snapshot"
        try {
            val args = JSONObject().apply {
                put("camera_name", "head_camera")
                put("format", "jpeg")
            }
            val res = mcpCall(atlasEndpoint, contract, tool, args)
            val b64 = extractImageBase64(res)
            if (b64.isNotBlank()) {
                val bytes = Base64.decode(b64, Base64.DEFAULT)
                val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                if (bmp != null) {
                    val w = res.optInt("width", bmp.width)
                    val h = res.optInt("height", bmp.height)
                    return Pair(bmp, "${w}×${h}")
                }
            }
        } catch (e: Exception) {
            if (isNoProviderError(e)) capabilityCache.remove(atlasEndpoint)
            AppLog.write("PERCEPTION", "Camera [$contract] error: ${e.message}")
        }
        return null
    }

    /**
     * Fetches a one-shot depth image using whichever contract the robot advertises.
     * Returns null silently when the robot has no depth camera capability.
     */
    private suspend fun fetchCameraDepth(
        atlasEndpoint: String,
        host: String,
        caps: RobotCapabilitySet,
    ): Pair<Bitmap, String>? {
        val contract = caps.cameraDepth ?: return null
        val tool = contract.substringAfterLast("/") // e.g. "depth_snapshot"
        try {
            val args = JSONObject().apply {
                put("camera_name", "head_camera")
                put("format", "png")
                put("colormap", "turbo")
            }
            val res = mcpCall(atlasEndpoint, contract, tool, args)
            val b64 = extractImageBase64(res)
            if (b64.isNotBlank()) {
                val bytes = Base64.decode(b64, Base64.DEFAULT)
                val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                if (bmp != null) {
                    val w = res.optInt("width", bmp.width)
                    val h = res.optInt("height", bmp.height)
                    return Pair(bmp, "${w}×${h}")
                }
            }
        } catch (e: Exception) {
            if (isNoProviderError(e)) capabilityCache.remove(atlasEndpoint)
            AppLog.write("PERCEPTION", "Depth [$contract] error: ${e.message}")
        }
        return null
    }

    /**
     * Fetches a one-shot lidar scan using whichever contract the robot advertises.
     *
     * Handles both contract variants transparently:
     * - `lidar/snapshot`       → 2D LaserScan (Webots / Hokuyo)    tool="snapshot"
     * - `lidar/lidar_snapshot` → 3D→2D scan (MID-360 / Lite3)      tool="lidar_snapshot"
     *
     * Returns null silently when the robot has no lidar capability.
     */
    private suspend fun fetchLidar(
        atlasEndpoint: String,
        host: String,
        caps: RobotCapabilitySet,
    ): Pair<List<LidarScanPoint>, String>? {
        val contract = caps.lidarSnapshot ?: return null
        // Tool name is always the last path segment of the contract.
        // "lidar/snapshot" → tool="snapshot"
        // "lidar/lidar_snapshot" → tool="lidar_snapshot"
        val tool = contract.substringAfterLast("/")
        try {
            val args = JSONObject().apply {
                put("lidar_name", "main_lidar")
            }
            val res = mcpCall(atlasEndpoint, contract, tool, args)
            val scan = if (res.has("scan")) res.getJSONObject("scan") else res
            val pts = parseLidarScan(scan)
            if (pts.isNotEmpty()) {
                val fov = Math.round(
                    Math.abs(
                        (scan.optDouble("angle_max", Math.PI) - scan.optDouble("angle_min", -Math.PI)) *
                            (180.0 / Math.PI)
                    )
                )
                val info = "${fov}° · ${pts.size} rays"
                return Pair(pts, info)
            }
        } catch (e: Exception) {
            if (isNoProviderError(e)) capabilityCache.remove(atlasEndpoint)
            AppLog.write("PERCEPTION", "LiDAR [$contract] error: ${e.message}")
        }
        return null
    }

    private data class SceneResult(
        val robotPose: RobotPose?,
        val objects: List<SceneObject>,
        val regions: List<SceneRegion>,
    )

    /**
     * Fetches scene context (robot pose, objects, regions) using contracts the robot advertises.
     * Falls back gracefully when individual contracts are absent.
     */
    private suspend fun fetchScene(
        atlasEndpoint: String,
        host: String,
        caps: RobotCapabilitySet,
    ): SceneResult {
        var robotPose: RobotPose? = null
        val objects = mutableListOf<SceneObject>()
        val regions = mutableListOf<SceneRegion>()

        // 1. Direct query to robot map HTTP service (http://$host:50107/api/state)
        try {
            val url = "http://$host:$MAP_UI_PORT/api/state"
            val req = Request.Builder().url(url).build()
            httpClient.newCall(req).execute().use { resp ->
                if (resp.isSuccessful) {
                    val json = JSONObject(resp.body?.string() ?: "")
                    val rob = json.optJSONObject("robot")
                    val pose = rob?.optJSONObject("pose")
                    if (pose != null) {
                        robotPose = RobotPose(
                            x = pose.optDouble("x", 0.0).toFloat(),
                            y = pose.optDouble("y", 0.0).toFloat(),
                            headingRad = pose.optDouble("yaw", 0.0).toFloat(),
                            roomName = rob.optString("room_name", ""),
                        )
                    } else if (rob != null && (rob.has("x") || rob.has("yaw"))) {
                        robotPose = RobotPose(
                            x = rob.optDouble("x", 0.0).toFloat(),
                            y = rob.optDouble("y", 0.0).toFloat(),
                            headingRad = rob.optDouble("yaw", 0.0).toFloat(),
                            roomName = rob.optString("room_name", ""),
                        )
                    }

                    val objArr = json.optJSONArray("objects")
                    if (objArr != null) {
                        for (i in 0 until objArr.length()) {
                            val o = objArr.optJSONObject(i) ?: continue
                            val oPose = o.optJSONObject("pose")
                            val ox = (oPose?.optDouble("x") ?: o.optDouble("x", 0.0)).toFloat()
                            val oy = (oPose?.optDouble("y") ?: o.optDouble("y", 0.0)).toFloat()
                            val oyaw = (oPose?.optDouble("yaw") ?: o.optDouble("yaw", 0.0)).toFloat()
                            objects.add(
                                SceneObject(
                                    id = o.optString("short_id", o.optString("id", "$i")),
                                    label = o.optString("cls", o.optString("label", "object")),
                                    x = ox,
                                    y = oy,
                                    width = o.optDouble("size_x", 0.5).toFloat(),
                                    height = o.optDouble("size_y", 0.5).toFloat(),
                                    yaw = oyaw,
                                )
                            )
                        }
                    }
                }
            }
        } catch (e: Exception) {
            AppLog.write("PERCEPTION", "Map HTTP state error: ${e.message}")
        }

        // 2. Fallback to direct MCP scene contracts if pose or objects missing
        if (robotPose == null && caps.sceneRobot != null) {
            try {
                val contract = caps.sceneRobot
                val res = mcpCall(atlasEndpoint, contract, contract.substringAfterLast("/"))
                val rob = res.optJSONObject("robot") ?: res
                if (rob.has("x") || rob.has("pose")) {
                    val pose = rob.optJSONObject("pose") ?: rob
                    robotPose = RobotPose(
                        x = pose.optDouble("x", 0.0).toFloat(),
                        y = pose.optDouble("y", 0.0).toFloat(),
                        headingRad = pose.optDouble("yaw", 0.0).toFloat(),
                        roomName = rob.optString("room_name", ""),
                    )
                }
            } catch (e: Exception) {
                if (isNoProviderError(e)) capabilityCache.remove(atlasEndpoint)
            }
        }

        if (objects.isEmpty() && caps.sceneObjects != null) {
            try {
                val contract = caps.sceneObjects
                val res = mcpCall(atlasEndpoint, contract, contract.substringAfterLast("/"))
                val arr = res.optJSONArray("objects")
                if (arr != null) {
                    for (i in 0 until arr.length()) {
                        val o = arr.optJSONObject(i) ?: continue
                        objects.add(
                            SceneObject(
                                id = o.optString("id", "$i"),
                                label = o.optString("label", "object"),
                                x = o.optDouble("x", 0.0).toFloat(),
                                y = o.optDouble("y", 0.0).toFloat(),
                                width = o.optDouble("size_x", 0.5).toFloat(),
                                height = o.optDouble("size_y", 0.5).toFloat(),
                                yaw = o.optDouble("yaw", 0.0).toFloat(),
                            )
                        )
                    }
                }
            } catch (e: Exception) {
                if (isNoProviderError(e)) capabilityCache.remove(atlasEndpoint)
            }
        }

        if (regions.isEmpty() && caps.sceneRegions != null) {
            try {
                val contract = caps.sceneRegions
                val res = mcpCall(atlasEndpoint, contract, contract.substringAfterLast("/"))
                val arr = res.optJSONArray("regions") ?: res.optJSONObject("regions")?.optJSONArray("regions")
                if (arr != null) {
                    for (i in 0 until arr.length()) {
                        val r = arr.optJSONObject(i) ?: continue
                        val ptsArr = r.optJSONArray("points_xy")
                        val pts = mutableListOf<Offset>()
                        if (ptsArr != null && ptsArr.length() >= 6) {
                            for (j in 0 until ptsArr.length() - 1 step 2) {
                                pts.add(Offset(ptsArr.optDouble(j, 0.0).toFloat(), ptsArr.optDouble(j + 1, 0.0).toFloat()))
                            }
                        }
                        regions.add(
                            SceneRegion(
                                name = r.optString("name", r.optString("id", "Region $i")),
                                kind = r.optString("kind", "room"),
                                colorHex = r.optString("color", "#35e0a0"),
                                points = pts,
                            )
                        )
                    }
                }
            } catch (e: Exception) {
                if (isNoProviderError(e)) capabilityCache.remove(atlasEndpoint)
            }
        }

        return SceneResult(robotPose, objects, regions)
    }

    private fun fetchOccupancyMap(host: String): OccupancyGridData? {
        // 1. Direct query to robot map HTTP service (http://$host:50107/api/state)
        try {
            val url = "http://$host:$MAP_UI_PORT/api/state"
            val req = Request.Builder().url(url).build()
            httpClient.newCall(req).execute().use { resp ->
                if (resp.isSuccessful) {
                    val json = JSONObject(resp.body?.string() ?: "")
                    val occ = json.optJSONObject("occupancy") ?: json.optJSONObject("map")
                    if (occ != null) {
                        val b64 = extractImageBase64(occ)
                        if (b64.isNotBlank()) {
                            val bytes = Base64.decode(b64, Base64.DEFAULT)
                            val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                            if (bmp != null) {
                                val ox = occ.optDouble("origin_x", 0.0).toFloat()
                                val oy = occ.optDouble("origin_y", 0.0).toFloat()
                                val res = occ.optDouble("resolution", 0.05).toFloat()
                                val w = occ.optInt("width", bmp.width)
                                val h = occ.optInt("height", bmp.height)
                                return OccupancyGridData(bmp, ox, oy, res, w, h)
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            AppLog.write("PERCEPTION", "Occupancy map HTTP state error: ${e.message}")
        }

        // 2. Direct query to :50107/map.png fallback
        try {
            val url = "http://$host:$MAP_UI_PORT/map.png"
            val req = Request.Builder().url(url).build()
            httpClient.newCall(req).execute().use { resp ->
                if (resp.isSuccessful) {
                    val bytes = resp.body?.bytes()
                    if (bytes != null && bytes.isNotEmpty()) {
                        val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                        if (bmp != null) {
                            return OccupancyGridData(
                                bitmap = bmp,
                                originX = -bmp.width * 0.05f / 2f,
                                originY = -bmp.height * 0.05f / 2f,
                                resolution = 0.05f,
                                width = bmp.width,
                                height = bmp.height,
                            )
                        }
                    }
                }
            }
        } catch (_: Exception) {}

        return null
    }

    /**
     * Fallback for robots where MCP camera snapshots are not configured or failed:
     * fetches RGB and depth frames directly from the scene HTTP service (port 50107 /api/camera).
     */
    private fun fetchCameraFallback(host: String): Pair<Pair<Bitmap, String>?, Pair<Bitmap, String>?> {
        try {
            val url = "http://$host:$MAP_UI_PORT/api/camera"
            val req = Request.Builder().url(url).build()
            httpClient.newCall(req).execute().use { resp ->
                if (resp.isSuccessful) {
                    val json = JSONObject(resp.body?.string() ?: "")
                    var rgbPair: Pair<Bitmap, String>? = null
                    var depthPair: Pair<Bitmap, String>? = null

                    val rgbObj = json.optJSONObject("rgb")
                    if (rgbObj != null) {
                        val b64 = rgbObj.optString("png_b64")
                        if (b64.isNotBlank()) {
                            val bytes = Base64.decode(b64, Base64.DEFAULT)
                            val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                            if (bmp != null) {
                                val w = rgbObj.optInt("width", bmp.width)
                                val h = rgbObj.optInt("height", bmp.height)
                                rgbPair = Pair(bmp, "${w}×${h}")
                            }
                        }
                    }

                    val depthObj = json.optJSONObject("depth")
                    if (depthObj != null) {
                        val b64 = depthObj.optString("png_b64")
                        if (b64.isNotBlank()) {
                            val bytes = Base64.decode(b64, Base64.DEFAULT)
                            val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                            if (bmp != null) {
                                val w = depthObj.optInt("width", bmp.width)
                                val h = depthObj.optInt("height", bmp.height)
                                depthPair = Pair(bmp, "${w}×${h}")
                            }
                        }
                    }

                    return Pair(rgbPair, depthPair)
                }
            }
        } catch (e: Exception) {
            AppLog.write("PERCEPTION", "Scene /api/camera fallback error: ${e.message}")
        }
        return Pair(null, null)
    }

    /**
     * Fallback for robots where MCP lidar snapshots are not configured or failed (e.g. Lite3 / MID-360):
     * fetches range scan/cloud directly from the mapping HTTP service (port 8091 /api/range).
     * If points are in map frame, transforms them to local robot coordinates using robotPose.
     */
    private fun fetchLidarFallback(host: String, robotPose: RobotPose?): Pair<List<LidarScanPoint>, String>? {
        try {
            val url = "http://$host:$MAPPING_PORT/api/range"
            val req = Request.Builder().url(url).build()
            httpClient.newCall(req).execute().use { resp ->
                if (resp.isSuccessful) {
                    val json = JSONObject(resp.body?.string() ?: "")
                    val scanObj = json.optJSONObject("scan") ?: json.optJSONObject("cloud")
                    val pts = scanObj?.optJSONArray("pts")
                        ?: json.optJSONArray("pts")
                        ?: json.optJSONArray("points")
                    if (pts != null && pts.length() > 0) {
                        val rx = robotPose?.x?.toDouble() ?: 0.0
                        val ry = robotPose?.y?.toDouble() ?: 0.0
                        val yaw = robotPose?.headingRad?.toDouble() ?: 0.0
                        val cosY = Math.cos(-yaw)
                        val sinY = Math.sin(-yaw)

                        val list = ArrayList<LidarScanPoint>(pts.length())
                        for (i in 0 until pts.length()) {
                            val item = pts.optJSONArray(i) ?: continue
                            if (item.length() < 2) continue
                            val mx = item.optDouble(0)
                            val my = item.optDouble(1)
                            if (mx.isNaN() || my.isNaN()) continue

                            // If points are in map frame and we have robot pose, transform to robot frame.
                            // If robot pose is zero or missing, rx/ry/yaw are 0 so dx=mx, dy=my.
                            val dx = mx - rx
                            val dy = my - ry
                            val lx = dx * cosY - dy * sinY
                            val ly = dx * sinY + dy * cosY

                            val r = Math.hypot(lx, ly).toFloat()
                            if (r in 0.05f..30.0f && !r.isInfinite()) {
                                val angleRad = Math.atan2(ly, lx)
                                val angleDeg = Math.toDegrees(angleRad).toFloat()
                                list.add(LidarScanPoint(angleDeg, r, 1.0f))
                            }
                        }

                        if (list.isNotEmpty()) {
                            return Pair(list, "${list.size} pts")
                        }
                    }
                }
            }
        } catch (e: Exception) {
            AppLog.write("PERCEPTION", "Mapping 8091 /api/range fallback error: ${e.message}")
        }
        return null
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // MCP Transport & Handshake (Strictly using configured robot host)
    // ─────────────────────────────────────────────────────────────────────────────

    /**
     * Returns true when an exception indicates that Atlas has no provider for the
     * requested contract — typically after a robot restart or primitive crash.
     * In that case the capability cache for this endpoint should be invalidated so
     * the next poll cycle re-discovers which contracts are actually available.
     */
    private fun isNoProviderError(e: Exception): Boolean =
        e.message?.contains("no provider", ignoreCase = true) == true ||
            e.message?.contains("no MCP provider", ignoreCase = true) == true

    private suspend fun mcpCall(
        atlasEndpoint: String,
        contractId: String,
        toolName: String? = null,
        arguments: JSONObject = JSONObject(),
    ): JSONObject {
        val cacheKey = "$atlasEndpoint::$contractId"
        var session = sessionCache[cacheKey]

        if (session == null) {
            val endpointInfo = atlasClient.discoverMcpEndpoint(atlasEndpoint, contractId)
            session = performMcpHandshake(endpointInfo.url, endpointInfo.hostHeader)
            sessionCache[cacheKey] = session
        }

        val tool = toolName ?: contractId.substringAfterLast("/")
        return try {
            callMcpTool(session, tool, arguments)
        } catch (e: Exception) {
            // Invalidate MCP session on error so next call re-handshakes.
            sessionCache.remove(cacheKey)
            throw e
        }
    }

    private fun performMcpHandshake(endpoint: String, hostHeader: String?): McpSession {
        val cleanEndpoint = endpoint.trimEnd('/')
        val initPayload = JSONObject().apply {
            put("jsonrpc", "2.0")
            put("id", 1)
            put("method", "initialize")
            put("params", JSONObject().apply {
                put("protocolVersion", MCP_PROTOCOL_VERSION)
                put("capabilities", JSONObject())
                put("clientInfo", JSONObject().apply {
                    put("name", "robonix-client-android")
                    put("version", "1.0.0")
                })
            })
        }

        val initBuilder = Request.Builder()
            .url(cleanEndpoint)
            .post(initPayload.toString().toRequestBody(JSON_MEDIA_TYPE))
            .header("Accept", "application/json, text/event-stream")

        if (!hostHeader.isNullOrBlank()) {
            initBuilder.header("Host", hostHeader)
        }

        var sessionId: String? = null
        httpClient.newCall(initBuilder.build()).execute().use { resp ->
            if (!resp.isSuccessful) {
                throw RuntimeException("MCP initialize failed: HTTP ${resp.code}")
            }
            sessionId = resp.header("Mcp-Session-Id")
        }

        // Send notifications/initialized
        try {
            val notifPayload = JSONObject().apply {
                put("jsonrpc", "2.0")
                put("method", "notifications/initialized")
            }
            val notifBuilder = Request.Builder()
                .url(cleanEndpoint)
                .post(notifPayload.toString().toRequestBody(JSON_MEDIA_TYPE))
                .header("Accept", "application/json, text/event-stream")

            if (!hostHeader.isNullOrBlank()) {
                notifBuilder.header("Host", hostHeader)
            }
            if (sessionId != null) {
                notifBuilder.header("Mcp-Session-Id", sessionId!!)
            }
            httpClient.newCall(notifBuilder.build()).execute().close()
        } catch (_: Exception) {}

        return McpSession(endpoint = cleanEndpoint, hostHeader = hostHeader, sessionId = sessionId)
    }

    private fun callMcpTool(session: McpSession, tool: String, arguments: JSONObject): JSONObject {
        val cleanEndpoint = session.endpoint.trimEnd('/')
        val payload = JSONObject().apply {
            put("jsonrpc", "2.0")
            put("id", 2)
            put("method", "tools/call")
            put("params", JSONObject().apply {
                put("name", tool)
                put("arguments", arguments)
            })
        }

        val builder = Request.Builder()
            .url(cleanEndpoint)
            .post(payload.toString().toRequestBody(JSON_MEDIA_TYPE))
            .header("Accept", "application/json, text/event-stream")

        if (!session.hostHeader.isNullOrBlank()) {
            builder.header("Host", session.hostHeader)
        }
        if (session.sessionId != null) {
            builder.header("Mcp-Session-Id", session.sessionId!!)
        }

        val body = httpClient.newCall(builder.build()).execute().use { resp ->
            if (!resp.isSuccessful) {
                throw RuntimeException("MCP tools/call $tool failed: HTTP ${resp.code}")
            }
            resp.body?.string() ?: "{}"
        }

        return parseMcpResult(body, tool)
    }

    private fun parseMcpResult(body: String, tool: String): JSONObject {
        // Body can be SSE (data: {...}) or raw JSON
        val messages = mutableListOf<JSONObject>()

        if (body.contains("data:")) {
            // Parse SSE chunks
            val blocks = body.split("\n\n")
            for (block in blocks) {
                for (line in block.lines()) {
                    val trimmed = line.trim()
                    if (trimmed.startsWith("data:")) {
                        val jsonStr = trimmed.removePrefix("data:").trim()
                        if (jsonStr.isNotBlank()) {
                            try {
                                messages.add(JSONObject(jsonStr))
                            } catch (_: Exception) {}
                        }
                    }
                }
            }
        } else {
            try {
                messages.add(JSONObject(body.trim()))
            } catch (_: Exception) {}
        }

        for (msg in messages) {
            val result = msg.optJSONObject("result") ?: if (msg.has("content")) msg else null
            if (result != null) {
                if (result.optBoolean("isError", false)) {
                    throw RuntimeException("MCP $tool isError: ${result.opt("content")}")
                }
                val content = result.optJSONArray("content")
                if (content != null) {
                    for (i in 0 until content.length()) {
                        val item = content.optJSONObject(i) ?: continue
                        val type = item.optString("type")
                        if (type == "text") {
                            val text = item.optString("text", "")
                            return try {
                                JSONObject(text)
                            } catch (_: Exception) {
                                JSONObject().put("text", text)
                            }
                        } else if (type == "image") {
                            return item
                        }
                    }
                }
                val structured = result.optJSONObject("structuredContent")
                if (structured != null) return structured
                return result
            }
        }

        throw RuntimeException("MCP $tool: no valid result in response")
    }

    private fun extractImageBase64(json: JSONObject): String {
        if (json.has("data")) {
            val d = json.optString("data")
            if (d.isNotBlank()) return cleanBase64(d)
        }
        if (json.has("png_b64")) {
            val d = json.optString("png_b64")
            if (d.isNotBlank()) return cleanBase64(d)
        }
        if (json.has("image_base64")) {
            val d = json.optString("image_base64")
            if (d.isNotBlank()) return cleanBase64(d)
        }
        val img = json.optJSONObject("image")
        if (img != null) {
            val d = extractImageBase64(img)
            if (d.isNotBlank()) return d
        }
        val occ = json.optJSONObject("occupancy")
        if (occ != null) {
            val d = extractImageBase64(occ)
            if (d.isNotBlank()) return d
        }
        return ""
    }

    private fun cleanBase64(raw: String): String {
        var s = raw.trim()
        if (s.startsWith("data:image", ignoreCase = true) && s.contains(",")) {
            s = s.substringAfter(",")
        }
        return s.replace("\n", "").replace("\r", "").trim()
    }

    private fun parseLidarScan(scan: JSONObject): List<LidarScanPoint> {
        // Mode 1: 2D LaserScan (ranges array)
        val ranges = scan.optJSONArray("ranges")
        if (ranges != null && ranges.length() > 0) {
            val angleMin = scan.optDouble("angle_min", -Math.PI)
            val angleInc = scan.optDouble("angle_increment", 0.017)
            val rangeMin = scan.optDouble("range_min", 0.05).toFloat()
            val rangeMax = scan.optDouble("range_max", 12.0).toFloat()

            val list = ArrayList<LidarScanPoint>(ranges.length())
            for (i in 0 until ranges.length()) {
                val r = ranges.optDouble(i, 0.0).toFloat()
                if (r in rangeMin..rangeMax && !r.isNaN() && !r.isInfinite()) {
                    val angleRad = angleMin + i * angleInc
                    val angleDeg = Math.toDegrees(angleRad).toFloat()
                    list.add(LidarScanPoint(angleDeg, r, 1.0f))
                }
            }
            if (list.isNotEmpty()) return list
        }

        // Mode 2: Cartesian Points array (e.g. 3D/2D point clouds, points: [[x,y], ...] or [{x,y}, ...])
        val points = scan.optJSONArray("points") ?: scan.optJSONArray("cloud")
        if (points != null && points.length() > 0) {
            val list = ArrayList<LidarScanPoint>(points.length())
            for (i in 0 until points.length()) {
                var x = 0.0f
                var y = 0.0f
                var z = 0.0f
                var hasZ = false

                val item = points.opt(i)
                if (item is JSONArray) {
                    if (item.length() >= 2) {
                        x = item.optDouble(0, 0.0).toFloat()
                        y = item.optDouble(1, 0.0).toFloat()
                        if (item.length() >= 3) {
                            z = item.optDouble(2, 0.0).toFloat()
                            hasZ = true
                        }
                    }
                } else if (item is JSONObject) {
                    x = item.optDouble("x", 0.0).toFloat()
                    y = item.optDouble("y", 0.0).toFloat()
                    if (item.has("z")) {
                        z = item.optDouble("z", 0.0).toFloat()
                        hasZ = true
                    }
                }

                val r = Math.hypot(x.toDouble(), y.toDouble()).toFloat()
                if (r in 0.05f..30.0f && !r.isNaN() && !r.isInfinite()) {
                    // Filter floor / ceiling if 3D height is present
                    if (hasZ && (z < -0.6f || z > 2.0f)) {
                        continue
                    }
                    val angleRad = Math.atan2(y.toDouble(), x.toDouble())
                    val angleDeg = Math.toDegrees(angleRad).toFloat()
                    list.add(LidarScanPoint(angleDeg, r, 1.0f))
                }
            }
            if (list.isNotEmpty()) return list
        }

        return emptyList()
    }
}
