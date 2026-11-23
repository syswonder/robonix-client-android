package com.robonix.client.data.audio

import com.robonix.client.AppLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.min

/**
 * Client end of the robot's reverse audio bridge
 * (robonix primitive `audio_client_bridge`, `ws://<robot>:60002/client`).
 *
 * The bridge is the only way the robot can reach this phone's microphone and
 * speaker: the app dials out, then obeys the control protocol
 *
 *   robot → client : {"type":"mic_start","stream_id":…}   start pushing PCM
 *                    {"type":"mic_stop","stream_id":…}    stop pushing PCM
 *                    {"type":"speaker_stop"}              cut TTS (barge-in)
 *                    {"type":"speaker_end"}               TTS finished
 *                    {"type":"control_request",…}         answer with control_response
 *                    <binary>                             TTS PCM (16k mono s16le)
 *   client → robot : <binary>                             mic PCM (16k mono s16le)
 *                    {"type":"mic_end","stream_id":…}     mic stream ended early
 *                    {"type":"hello","role":"robonix-client"}
 *
 * Without this the robot's speech service opens a mic stream, receives zero
 * frames and fails with "No audio data received".
 */
@Singleton
class AudioBridge @Inject constructor(
    private val audioRecorder: AudioRecorder,
    private val audioPlayer: AudioPlayer,
) {
    private companion object {
        const val TAG = "AUDIO"
        const val RECONNECT_DELAY_MS = 1000L
        const val MAX_RECONNECT_DELAY_MS = 15000L

        /** Below this, a session was kicked out rather than used, so back off. */
        const val SESSION_ALIVE_MS = 5000L
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .pingInterval(20, TimeUnit.SECONDS)
        .build()

    /** Connection lifecycle is owned here, not by whoever asked to connect. */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _events = MutableSharedFlow<AudioBridgeEvent>(extraBufferCapacity = 64)
    val events: SharedFlow<AudioBridgeEvent> = _events.asSharedFlow()

    private val _status = MutableStateFlow(AudioBridgeStatus())

    /** Whether this client holds the robot's reverse-audio slot, or lost it to another client. */
    val status: StateFlow<AudioBridgeStatus> = _status.asStateFlow()

    @Volatile
    private var socket: WebSocket? = null

    @Volatile
    private var active = false

    private var connectJob: Job? = null

    /** A pending lookup or callback can act only on the generation it started with. */
    @Volatile
    private var generation = 0L
    private var openedGeneration = -1L

    fun connectionGeneration(): Long = generation

    private var micJob: Job? = null

    /** One TTS utterance: frames queued by OkHttp, drained onto the speaker. */
    private class SpeakerSession {
        val channel: Channel<ByteArray> = Channel(Channel.UNLIMITED)

        @Volatile
        var interrupted = false

        /** AudioTrack would not start: drop this utterance's frames until speaker_end. */
        @Volatile
        var dropped = false
    }

    private var speakerSession: SpeakerSession? = null

    @Synchronized
    fun connect(
        route: AudioBridgeRoute,
        url: String,
        expectedGeneration: Long,
        takeOver: Boolean,
        occupied: suspend () -> Boolean?,
    ): Long? {
        if (generation != expectedGeneration) return null
        if (active && _status.value.route == route && _status.value.url == url &&
            (_status.value.connected || (!takeOver && _status.value.phase == AudioBridgePhase.Connecting))
        ) return generation

        val request = Request.Builder().url(url).build()
        stopConnection()
        val attempt = ++generation
        active = true
        _status.value = AudioBridgeStatus(AudioBridgePhase.Connecting, route, url, attempt)
        connectJob = scope.launch { connectionLoop(request, attempt, occupied) }
        AppLog.write(TAG, "AudioBridge connect requested: $url")
        return attempt
    }

    @Synchronized
    fun reportAvailability(route: AudioBridgeRoute, expectedGeneration: Long, phase: AudioBridgePhase) {
        val current = _status.value
        if (generation != expectedGeneration || current.connected || current.takenOver) return
        if (active && current.route != route) return
        _status.value = current.copy(phase = phase, route = route, generation = generation)
    }

    suspend fun awaitConnected(attempt: Long, timeoutMs: Long = 3000): Boolean =
        withTimeoutOrNull(timeoutMs) {
            status.first {
                it.generation != attempt || it.connected || it.takenOver
            }.let { it.generation == attempt && it.connected }
        } ?: false

    @Synchronized
    fun abandonHandshake(attempt: Long) {
        if (generation == attempt && openedGeneration != attempt && !_status.value.takenOver) disconnect()
    }

    @Synchronized
    fun disconnect(route: AudioBridgeRoute? = null) {
        if (route != null && _status.value.route != route) return
        generation++
        stopConnection()
        _status.value = AudioBridgeStatus(generation = generation)
        AppLog.write(TAG, "AudioBridge disconnected")
    }

    // All socket and audio teardown runs under the same lock as adoption of a new connection.
    private fun stopConnection() {
        active = false
        connectJob?.cancel()
        connectJob = null
        if (socket != null) {
            stopMic()
            stopSpeaker()
            socket?.cancel()
            socket = null
        }
    }

    private suspend fun connectionLoop(request: Request, attempt: Long, occupied: suspend () -> Boolean?) {
        var kicked = 0
        var first = true
        while (true) {
            if (!first) {
                val phase = reconnectPhase(occupied())
                synchronized(this) {
                    if (!isCurrent(attempt)) return
                    _status.value = _status.value.copy(phase = phase)
                }
                if (phase != AudioBridgePhase.Connecting) {
                    delay(MAX_RECONNECT_DELAY_MS)
                    continue
                }
            }
            first = false
            val closed = Channel<Unit>(Channel.CONFLATED)
            val listener = Listener(request.url.toString(), closed, attempt)
            val ws = synchronized(this) {
                if (!isCurrent(attempt)) return
                client.newWebSocket(request, listener).also { socket = it }
            }
            try {
                closed.receive()
            } finally {
                synchronized(this) {
                    if (isCurrent(attempt) && socket === ws) {
                        socket = null
                        stopMic()
                        stopSpeaker()
                        val phase = if (listener.replaced) AudioBridgePhase.TakenOver else AudioBridgePhase.Connecting
                        _status.value = _status.value.copy(phase = phase)
                        if (listener.replaced) {
                            active = false
                            connectJob = null
                            AppLog.write(TAG, "AudioBridge replaced by another client")
                        }
                    }
                }
                ws.cancel()
            }
            currentCoroutineContext().ensureActive()
            if (listener.replaced) return
            val aliveMs = listener.openTimeMs.takeIf { it > 0 }?.let { System.currentTimeMillis() - it } ?: 0
            kicked = if (aliveMs > SESSION_ALIVE_MS) 0 else kicked + 1
            delay(minOf(RECONNECT_DELAY_MS shl minOf(kicked, 4), MAX_RECONNECT_DELAY_MS))
        }
    }

    private fun isCurrent(attempt: Long): Boolean = active && attempt == generation

    private inner class Listener(
        private val target: String,
        private val closed: Channel<Unit>,
        private val gen: Long,
    ) : WebSocketListener() {
        @Volatile
        var openTimeMs = 0L

        @Volatile
        var replaced = false

        override fun onOpen(webSocket: WebSocket, response: Response) {
            synchronized(this@AudioBridge) {
                if (!isCurrent(gen) || socket !== webSocket) {
                    webSocket.cancel()
                    return
                }
                openedGeneration = gen
                openTimeMs = System.currentTimeMillis()
                _status.value = _status.value.copy(phase = AudioBridgePhase.Connected)
                AppLog.write(TAG, "AudioBridge connected: $target")
                _events.tryEmit(AudioBridgeEvent.Connected(target))
                webSocket.send("""{"type":"hello","role":"robonix-client"}""")
            }
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            synchronized(this@AudioBridge) {
                if (isCurrent(gen) && socket === webSocket) handleText(text)
            }
        }

        override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
            synchronized(this@AudioBridge) {
                if (isCurrent(gen) && socket === webSocket) enqueueSpeaker(bytes.toByteArray())
            }
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            if (isReplacedClose(code, reason)) replaced = true
            webSocket.close(1000, null)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            if (isReplacedClose(code, reason)) replaced = true
            AppLog.write(TAG, "AudioBridge closed: code=$code reason=$reason")
            _events.tryEmit(AudioBridgeEvent.Disconnected(reason))
            closed.trySend(Unit)
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            AppLog.write(TAG, "AudioBridge failure: ${t.message}")
            _events.tryEmit(AudioBridgeEvent.Error(t.message ?: "bridge error"))
            closed.trySend(Unit)
        }
    }

    // ── robot → client control messages ────────────────────────────────────

    private fun handleText(text: String) {
        val body = try {
            JSONObject(text)
        } catch (_: Exception) {
            return
        }
        when (body.optString("type")) {
            "mic_start" -> {
                AppLog.write(TAG, "bridge mic_start stream=${body.optString("stream_id")}")
                startMic()
            }
            "mic_stop", "mic_end" -> {
                AppLog.write(TAG, "bridge ${body.optString("type")}")
                stopMic()
            }
            "speaker_stop" -> {
                AppLog.write(TAG, "bridge speaker_stop (barge-in)")
                stopSpeaker()
            }
            "speaker_end" -> {
                AppLog.write(TAG, "bridge speaker_end")
                endSpeaker()
            }
            "control_request" -> handleControlRequest(body)
        }
    }

    /**
     * The robot forwards its ListAudioDevices/SelectAudioDevice calls through
     * here, so answer with this phone's own capture/playback route. Android
     * exposes no per-device routing for apps, hence the fixed default entry.
     */
    private fun handleControlRequest(body: JSONObject) {
        val op = body.optString("op")
        val result = JSONObject()
        when (op) {
            "list_devices" -> {
                result.put("ok", true)
                result.put(
                    "devices",
                    JSONArray()
                        .put(
                            JSONObject()
                                .put("id", 0)
                                .put("name", "Android microphone")
                                .put("max_input_channels", 1)
                                .put("max_output_channels", 0)
                        )
                        .put(
                            JSONObject()
                                .put("id", 1)
                                .put("name", "Android speaker")
                                .put("max_input_channels", 0)
                                .put("max_output_channels", 2)
                        )
                )
                result.put("input_default", 0)
                result.put("output_default", 1)
                result.put("input_current", 0)
                result.put("output_current", 1)
            }
            "select_device" -> result.put("ok", true)
            "health" -> result.put("ok", true)
            else -> {
                result.put("ok", false)
                result.put("error", "unsupported op: $op")
            }
        }
        val response = JSONObject()
            .put("type", "control_response")
            .put("id", body.optString("id"))
        result.keys().forEach { key -> response.put(key, result.get(key)) }
        socket?.send(response.toString())
        AppLog.write(TAG, "bridge control op=$op ok=${result.optBoolean("ok")}")
    }

    // ── microphone: PCM captured here → robot gRPC mic stream ──────────────

    private fun startMic() {
        stopMic()
        val ws = socket ?: return
        micJob = scope.launch {
            try {
                audioRecorder.startRecording().collect { pcm ->
                    if (pcm.isNotEmpty()) ws.send(ByteString.of(*pcm))
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLog.write(TAG, "mic stream failed: ${e.message}")
            } finally {
                AppLog.write(TAG, "mic stream stopped")
            }
        }
    }

    private fun stopMic() {
        val job = micJob ?: return
        micJob = null
        // Release the AudioRecord first so the blocking read loop can exit.
        audioRecorder.stop()
        job.cancel()
    }

    // ── speaker: robot TTS PCM → AudioTrack ────────────────────────────────

    private fun enqueueSpeaker(pcm: ByteArray) {
        if (pcm.isEmpty()) return
        val session = synchronized(this) {
            val existing = speakerSession
            if (existing != null && !existing.channel.isClosedForSend) {
                existing
            } else {
                SpeakerSession().also { created ->
                    speakerSession = created
                    scope.launch { drainSpeaker(created) }
                }
            }
        }
        session.channel.trySend(pcm)
    }

    private suspend fun drainSpeaker(session: SpeakerSession) {
        var started = false
        try {
            for (pcm in session.channel) {
                if (session.interrupted) break
                // Keep consuming so later frames of this utterance do not start a new session.
                if (session.dropped) continue
                if (!started) {
                    if (!audioPlayer.beginStream()) {
                        session.dropped = true
                        AppLog.write(TAG, "speaker stream: AudioTrack failed to initialise, robot audio dropped until speaker_end")
                        continue
                    }
                    started = true
                }
                audioPlayer.writeStream(pcm)
            }
        } catch (e: CancellationException) {
            session.interrupted = true
            throw e
        } catch (e: Exception) {
            session.interrupted = true
            AppLog.write(TAG, "speaker stream failed: ${e.message}")
        } finally {
            if (started) {
                if (session.interrupted) audioPlayer.stopStream()
                else audioPlayer.endStream() // drains what is already queued
            }
            synchronized(this) {
                if (speakerSession === session) speakerSession = null
            }
        }
    }

    /** speaker_end: no more TTS frames — let the queued audio play out. */
    private fun endSpeaker() {
        synchronized(this) { speakerSession }?.channel?.close()
    }

    /** speaker_stop / teardown: cut playback immediately. */
    private fun stopSpeaker() {
        val session = synchronized(this) {
            speakerSession.also {
                speakerSession = null
            }
        } ?: return
        session.interrupted = true
        session.channel.close()
        audioPlayer.stopStream()
    }
}

data class AudioBridgeRoute(val atlasEndpoint: String, val providerId: String)

enum class AudioBridgePhase { Disconnected, Connecting, Connected, Occupied, TakenOver, OccupancyUnknown }

data class AudioBridgeStatus(
    val phase: AudioBridgePhase = AudioBridgePhase.Disconnected,
    val route: AudioBridgeRoute? = null,
    val url: String? = null,
    val generation: Long = 0,
) {
    val connected: Boolean get() = phase == AudioBridgePhase.Connected
    val takenOver: Boolean get() = phase == AudioBridgePhase.TakenOver
}

internal fun reconnectPhase(occupied: Boolean?): AudioBridgePhase = when (occupied) {
    false -> AudioBridgePhase.Connecting
    true -> AudioBridgePhase.Occupied
    null -> AudioBridgePhase.OccupancyUnknown
}

internal const val REPLACED_CLOSE_CODE = 1012
internal const val REPLACED_CLOSE_REASON = "replaced by newer client"

/** The bridge closes the old client with this code/reason when a new client connects. */
internal fun isReplacedClose(code: Int, reason: String?): Boolean =
    code == REPLACED_CLOSE_CODE &&
        reason?.contains(REPLACED_CLOSE_REASON, ignoreCase = true) == true

sealed class AudioBridgeEvent {
    data class Connected(val endpoint: String) : AudioBridgeEvent()
    data class AudioData(val pcm: ByteArray) : AudioBridgeEvent()
    data class TextMessage(val text: String) : AudioBridgeEvent()
    data class Disconnected(val reason: String) : AudioBridgeEvent()
    data class Error(val message: String) : AudioBridgeEvent()
}
