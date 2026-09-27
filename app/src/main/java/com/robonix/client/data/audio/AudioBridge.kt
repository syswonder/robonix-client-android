package com.robonix.client.data.audio

import com.robonix.client.AppLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
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

    @Volatile
    private var socket: WebSocket? = null

    @Volatile
    private var connected = false

    @Volatile
    private var active = false

    private var endpoint: String = ""
    private var connectJob: Job? = null

    private var micJob: Job? = null

    /** One TTS utterance: frames queued by OkHttp, drained onto the speaker. */
    private class SpeakerSession {
        val channel: Channel<ByteArray> = Channel(Channel.UNLIMITED)

        @Volatile
        var interrupted = false
    }

    private var speakerSession: SpeakerSession? = null

    /**
     * Dial the robot and keep the socket open (reconnecting on drops) until
     * [disconnect]. Re-calling with the same endpoint while active is a no-op.
     */
    fun connect(newEndpoint: String) {
        if (newEndpoint.isBlank()) return
        synchronized(this) {
            if (active && endpoint == newEndpoint) return
            endpoint = newEndpoint
            active = true
        }
        connectJob?.cancel()
        connectJob = scope.launch { connectionLoop(newEndpoint) }
        AppLog.write(TAG, "AudioBridge connect requested: $newEndpoint")
    }

    /** True once the WebSocket handshake completed and stays true until it drops. */
    fun isConnected(): Boolean = connected

    /** Suspend until the socket is open (or [timeoutMs] elapses). */
    suspend fun awaitConnected(timeoutMs: Long = 3000): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (connected) return true
            delay(50)
        }
        return connected
    }

    fun disconnect() {
        val job: Job?
        synchronized(this) {
            active = false
            job = connectJob
            connectJob = null
        }
        job?.cancel()
        stopMic()
        stopSpeaker()
        socket?.cancel()
        socket = null
        connected = false
        AppLog.write(TAG, "AudioBridge disconnected")
    }

    /** Connect, and on failure/close retry while still active. */
    private suspend fun connectionLoop(target: String) {
        var kicked = 0
        while (active) {
            val closed = Channel<Unit>(Channel.CONFLATED)
            val listener = Listener(target, closed)
            val ws = client.newWebSocket(Request.Builder().url(target).build(), listener)
            socket = ws
            try {
                closed.receive()
            } catch (_: CancellationException) {
                // Cancelled by disconnect(): fall through to cleanup below.
            } finally {
                if (socket === ws) socket = null
                connected = false
                stopMic()
                stopSpeaker()
                ws.cancel()
            }
            if (!active) break
            // Another client (e.g. a desktop robonix-client) owns the single
            // reverse-audio slot and keeps kicking us out. Retrying every second
            // would just ping-pong, so back off while that is the case.
            val aliveMs = listener.openTimeMs.takeIf { it > 0 }?.let { System.currentTimeMillis() - it } ?: 0
            kicked = if (aliveMs > SESSION_ALIVE_MS) 0 else kicked + 1
            val backoff = minOf(
                RECONNECT_DELAY_MS shl minOf(kicked, 4),
                MAX_RECONNECT_DELAY_MS,
            )
            if (kicked > 0) AppLog.write(TAG, "AudioBridge retry in ${backoff}ms (kicked $kicked time(s))")
            delay(backoff)
        }
    }

    private inner class Listener(
        private val target: String,
        private val closed: Channel<Unit>,
    ) : WebSocketListener() {
        @Volatile
        var openTimeMs = 0L

        override fun onOpen(webSocket: WebSocket, response: Response) {
            socket = webSocket
            connected = true
            openTimeMs = System.currentTimeMillis()
            AppLog.write(TAG, "AudioBridge connected: $target")
            _events.tryEmit(AudioBridgeEvent.Connected(target))
            webSocket.send("""{"type":"hello","role":"robonix-client"}""")
        }

        override fun onMessage(webSocket: WebSocket, text: String) = handleText(text)

        override fun onMessage(webSocket: WebSocket, bytes: ByteString) =
            enqueueSpeaker(bytes.toByteArray())

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(1000, null)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            connected = false
            AppLog.write(TAG, "AudioBridge closed: code=$code reason=$reason")
            _events.tryEmit(AudioBridgeEvent.Disconnected(reason))
            closed.trySend(Unit)
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            connected = false
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
        val job = micJob
        micJob = null
        // Release the AudioRecord first so the blocking read loop can exit.
        audioRecorder.stop()
        job?.cancel()
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
                if (!started) {
                    audioPlayer.beginStream()
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

sealed class AudioBridgeEvent {
    data class Connected(val endpoint: String) : AudioBridgeEvent()
    data class AudioData(val pcm: ByteArray) : AudioBridgeEvent()
    data class TextMessage(val text: String) : AudioBridgeEvent()
    data class Disconnected(val reason: String) : AudioBridgeEvent()
    data class Error(val message: String) : AudioBridgeEvent()
}
