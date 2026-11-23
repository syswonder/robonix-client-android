package com.robonix.client.domain

import com.robonix.client.AppLog
import com.robonix.client.data.audio.AudioBridge
import com.robonix.client.data.audio.AudioBridgePhase
import com.robonix.client.data.audio.AudioBridgeRoute
import com.robonix.client.data.audio.AudioBridgeStatus
import com.robonix.client.data.audio.AudioPlayer
import com.robonix.client.data.audio.AudioRecorder
import com.robonix.client.data.grpc.AtlasClient
import com.robonix.client.data.grpc.GrpcChannelProvider
import com.robonix.client.data.model.AudioProvider
import com.robonix.client.data.model.ClientSettings
import com.robonix.client.data.model.HandsfreeStatus
import com.robonix.proto.audio.AudioChunk
import com.robonix.proto.audio.GetAudioBridgeInfo_Request
import com.robonix.proto.audio.GetAudioBridgeInfo_Response
import com.robonix.proto.audio.ListAudioDevices_Request
import com.robonix.proto.audio.ListAudioDevices_Response
import com.robonix.proto.audio.SelectAudioDevice_Request
import com.robonix.proto.audio.SelectAudioDevice_Response
import io.grpc.MethodDescriptor
import io.grpc.Status
import io.grpc.StatusRuntimeException
import io.grpc.protobuf.ProtoUtils
import io.grpc.stub.ClientCalls
import io.grpc.stub.StreamObserver
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AudioRepository @Inject constructor(
    val atlasClient: AtlasClient,
    val audioRecorder: AudioRecorder,
    private val audioPlayer: AudioPlayer,
    private val audioBridge: AudioBridge,
    private val channelProvider: GrpcChannelProvider,
) {
    companion object {
        const val CONTRACT_AUDIO_BRIDGE_INFO = "robonix/primitive/audio/bridge_info"
        const val CONTRACT_AUDIO_LIST_DEVICES = "robonix/primitive/audio/list_devices"
        const val CONTRACT_AUDIO_SELECT_DEVICE = "robonix/primitive/audio/select_device"
        const val CONTRACT_MIC = "robonix/primitive/audio/mic"
        const val CONTRACT_SPEAKER = "robonix/primitive/audio/speaker"
        const val CONTRACT_TTS = "robonix/service/speech/tts"
        const val CONTRACT_VOICEPRINT_ENROLL = "robonix/service/voiceprint/enroll"
    }

    // gRPC method descriptor for GetAudioBridgeInfo
    private val BRIDGE_INFO_METHOD: MethodDescriptor<GetAudioBridgeInfo_Request, GetAudioBridgeInfo_Response> =
        MethodDescriptor.newBuilder<GetAudioBridgeInfo_Request, GetAudioBridgeInfo_Response>()
            .setType(MethodDescriptor.MethodType.UNARY)
            .setFullMethodName("robonix.contracts.RobonixPrimitiveAudioBridgeInfo/GetAudioBridgeInfo")
            .setRequestMarshaller(ProtoUtils.marshaller(GetAudioBridgeInfo_Request.getDefaultInstance()))
            .setResponseMarshaller(ProtoUtils.marshaller(GetAudioBridgeInfo_Response.getDefaultInstance()))
            .build()

    private val LIST_DEVICES_METHOD: MethodDescriptor<ListAudioDevices_Request, ListAudioDevices_Response> =
        MethodDescriptor.newBuilder<ListAudioDevices_Request, ListAudioDevices_Response>()
            .setType(MethodDescriptor.MethodType.UNARY)
            .setFullMethodName("robonix.contracts.RobonixPrimitiveAudioListDevices/ListAudioDevices")
            .setRequestMarshaller(ProtoUtils.marshaller(ListAudioDevices_Request.getDefaultInstance()))
            .setResponseMarshaller(ProtoUtils.marshaller(ListAudioDevices_Response.getDefaultInstance()))
            .build()

    private val SELECT_DEVICE_METHOD: MethodDescriptor<SelectAudioDevice_Request, SelectAudioDevice_Response> =
        MethodDescriptor.newBuilder<SelectAudioDevice_Request, SelectAudioDevice_Response>()
            .setType(MethodDescriptor.MethodType.UNARY)
            .setFullMethodName("robonix.contracts.RobonixPrimitiveAudioSelectDevice/SelectAudioDevice")
            .setRequestMarshaller(ProtoUtils.marshaller(SelectAudioDevice_Request.getDefaultInstance()))
            .setResponseMarshaller(ProtoUtils.marshaller(SelectAudioDevice_Response.getDefaultInstance()))
            .build()

    private val VOICEPRINT_ENROLL_METHOD: MethodDescriptor<com.robonix.proto.voiceprint.Enroll_Request, com.robonix.proto.voiceprint.Enroll_Response> =
        MethodDescriptor.newBuilder<com.robonix.proto.voiceprint.Enroll_Request, com.robonix.proto.voiceprint.Enroll_Response>()
            .setType(MethodDescriptor.MethodType.UNARY)
            .setFullMethodName("robonix.contracts.RobonixServiceVoiceprintEnroll/Enroll")
            .setRequestMarshaller(ProtoUtils.marshaller(com.robonix.proto.voiceprint.Enroll_Request.getDefaultInstance()))
            .setResponseMarshaller(ProtoUtils.marshaller(com.robonix.proto.voiceprint.Enroll_Response.getDefaultInstance()))
            .build()

    private val MIC_METHOD: MethodDescriptor<com.google.protobuf.Empty, AudioChunk> =
        MethodDescriptor.newBuilder<com.google.protobuf.Empty, AudioChunk>()
            .setType(MethodDescriptor.MethodType.SERVER_STREAMING)
            .setFullMethodName("robonix.contracts.RobonixPrimitiveAudioMic/Mic")
            .setRequestMarshaller(ProtoUtils.marshaller(com.google.protobuf.Empty.getDefaultInstance()))
            .setResponseMarshaller(ProtoUtils.marshaller(AudioChunk.getDefaultInstance()))
            .build()

    private val SPEAKER_METHOD: MethodDescriptor<AudioChunk, com.google.protobuf.Empty> =
        MethodDescriptor.newBuilder<AudioChunk, com.google.protobuf.Empty>()
            .setType(MethodDescriptor.MethodType.CLIENT_STREAMING)
            .setFullMethodName("robonix.contracts.RobonixPrimitiveAudioSpeaker/Speaker")
            .setRequestMarshaller(ProtoUtils.marshaller(AudioChunk.getDefaultInstance()))
            .setResponseMarshaller(ProtoUtils.marshaller(com.google.protobuf.Empty.getDefaultInstance()))
            .build()

    private val TTS_SYNTHESIZE_METHOD: MethodDescriptor<com.robonix.proto.tts.Synthesize_Request, com.robonix.proto.tts.Synthesize_Response> =
        MethodDescriptor.newBuilder<com.robonix.proto.tts.Synthesize_Request, com.robonix.proto.tts.Synthesize_Response>()
            .setType(MethodDescriptor.MethodType.UNARY)
            .setFullMethodName("robonix.contracts.RobonixServiceSpeechTts/Synthesize")
            .setRequestMarshaller(ProtoUtils.marshaller(com.robonix.proto.tts.Synthesize_Request.getDefaultInstance()))
            .setResponseMarshaller(ProtoUtils.marshaller(com.robonix.proto.tts.Synthesize_Response.getDefaultInstance()))
            .build()

    suspend fun getAudioProviders(target: String): Triple<List<AudioProvider>, List<AudioProvider>, List<AudioProvider>> =
        atlasClient.listAudioProviders(target)

    /**
     * List the physical input/output devices behind one audio provider
     * (web client's list_audio_devices). `providerId` is the mic or speaker
     * node id from settings.
     */
    suspend fun listAudioDevices(atlasEndpoint: String, providerId: String): com.robonix.client.data.model.AudioDeviceList {
        if (providerId.isBlank()) return com.robonix.client.data.model.AudioDeviceList()
        // Asking a bridge provider would list the devices of whichever client holds the
        // slot (possibly the Web client's PC), and would fail when no client is connected.
        if (providerId in bridgeProviderIds(atlasEndpoint)) return LOCAL_BRIDGE_DEVICES
        return withContext(Dispatchers.IO) {
            val endpoint = atlasClient.connectCapability(atlasEndpoint, providerId, CONTRACT_AUDIO_LIST_DEVICES)
            val channel = channelProvider.getChannel(endpoint)
            val resp = ClientCalls.blockingUnaryCall(
                channel.newCall(LIST_DEVICES_METHOD, io.grpc.CallOptions.DEFAULT),
                ListAudioDevices_Request.getDefaultInstance(),
            )
            com.robonix.client.data.model.AudioDeviceList(
                devices = resp.devicesList.map { d ->
                    com.robonix.client.data.model.AudioDevice(
                        id = d.id, name = d.name, kind = d.kind,
                        isDefault = d.isDefault, channels = d.channels, note = d.note,
                    )
                },
                currentInputId = resp.currentInputId,
                currentOutputId = resp.currentOutputId,
            )
        }
    }

    /** Select a concrete device on a provider (kind: "input" | "output"). */
    suspend fun selectAudioDevice(atlasEndpoint: String, providerId: String, kind: String, deviceId: String) {
        withContext(Dispatchers.IO) {
            val endpoint = atlasClient.connectCapability(atlasEndpoint, providerId, CONTRACT_AUDIO_SELECT_DEVICE)
            val channel = channelProvider.getChannel(endpoint)
            val resp = ClientCalls.blockingUnaryCall(
                channel.newCall(SELECT_DEVICE_METHOD, io.grpc.CallOptions.DEFAULT),
                SelectAudioDevice_Request.newBuilder().setKind(kind).setId(deviceId).build(),
            )
            if (!resp.ok) throw RuntimeException(resp.error.ifBlank { "$providerId rejected $kind device" })
        }
    }

    /**
     * Push the configured route to the robot (web client's /api/audio-route/apply):
     * select mic/speaker devices when both node id and device id are set. Does not
     * touch the reverse audio bridge, so applying never takes it from another client.
     * A bridge provider has only this phone's fixed devices and nothing to select
     * on the robot side, so it is skipped. Returns how many devices were applied.
     */
    suspend fun applyAudioRoute(
        atlasEndpoint: String,
        micNodeId: String, micDeviceId: String,
        speakerNodeId: String, speakerDeviceId: String,
    ): Int {
        val bridgeIds = bridgeProviderIds(atlasEndpoint)
        var selected = 0
        if (micNodeId.isNotBlank() && micDeviceId.isNotBlank() && micNodeId !in bridgeIds) {
            selectAudioDevice(atlasEndpoint, micNodeId, "input", micDeviceId); selected++
        }
        if (speakerNodeId.isNotBlank() && speakerDeviceId.isNotBlank() && speakerNodeId !in bridgeIds) {
            selectAudioDevice(atlasEndpoint, speakerNodeId, "output", speakerDeviceId); selected++
        }
        return selected
    }

    /**
     * Record ~`seconds` of local audio and enroll it as the user's voiceprint
     * (web client's enroll_voiceprint). Returns a human-readable outcome.
     */
    suspend fun enrollVoiceprint(
        atlasEndpoint: String,
        voiceprintNodeId: String,
        userId: String,
        userName: String,
        seconds: Float = 6f,
    ): String {
        val cleanUserId = userId.trim()
        if (cleanUserId.isBlank()) throw RuntimeException("voiceprint user id is required")
        val pcm = recordForDuration(seconds)
        if (pcm.size < 16000 * 2) throw RuntimeException("recorded only ${pcm.size} bytes; need about 1 second")

        return withContext(Dispatchers.IO) {
            val endpoint = try {
                atlasClient.discoverEndpoint(atlasEndpoint, CONTRACT_VOICEPRINT_ENROLL, voiceprintNodeId)
            } catch (e: Exception) {
                throw RuntimeException("voiceprint enroll unavailable: ${e.message}", e)
            }
            val channel = channelProvider.getChannel(endpoint)
            val resp = ClientCalls.blockingUnaryCall(
                channel.newCall(VOICEPRINT_ENROLL_METHOD, io.grpc.CallOptions.DEFAULT),
                com.robonix.proto.voiceprint.Enroll_Request.newBuilder()
                    .setUserId(cleanUserId)
                    .setUserName(userName.ifBlank { cleanUserId })
                    .setAudioData(com.google.protobuf.ByteString.copyFrom(pcm))
                    .setEncoding("pcm_s16le")
                    .setSampleRateHz(16000)
                    .build(),
            )
            if (resp.success) {
                "enrolled $cleanUserId (${pcm.size}B)"
            } else {
                val err = resp.error.ifBlank { "voiceprint enroll failed" }
                // The server reports "already enrolled" as an error; treat as ok.
                if (err.contains("already", ignoreCase = true)) "already enrolled: $err"
                else throw RuntimeException(err)
            }
        }
    }

    fun recordAudio(): Flow<ByteArray> = audioRecorder.startRecording()

    fun stopRecording() = audioRecorder.stop()

    fun hasRecordPermission(): Boolean = audioRecorder.hasPermission()

    suspend fun recordForDuration(seconds: Float): ByteArray = audioRecorder.recordForDuration(seconds)

    fun playTtsAudio(pcm: ByteArray) {
        audioPlayer.play(pcm)
    }

    /**
     * The device a provider is using right now, or null when it cannot say: list_devices is an
     * optional contract, so a provider without it must not fail the test built around it.
     */
    suspend fun activeDevice(atlasEndpoint: String, providerId: String, kind: String): ActiveDevice? {
        if (providerId.isBlank()) return null
        return try {
            activeDeviceFrom(listAudioDevices(atlasEndpoint, providerId), kind)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Capture ~[seconds] from the robot's mic primitive, the same path ASR and voiceprint use
     * (web client's record_pcm). An empty or silent capture is returned, not thrown: judging it
     * is [judgeCapture]'s job.
     */
    suspend fun testMicRoute(atlasEndpoint: String, micNodeId: String, seconds: Float = 1f): MicTestResult =
        withContext(Dispatchers.IO) {
            val endpoint = atlasClient.discoverEndpoint(atlasEndpoint, CONTRACT_MIC, micNodeId)
            val channel = channelProvider.getChannel(endpoint)
            val startedAt = System.nanoTime()
            val deadline = startedAt + (seconds * 1e9).toLong()
            val call = channel.newCall(
                MIC_METHOD,
                io.grpc.CallOptions.DEFAULT.withDeadlineAfter((seconds + 5).toLong(), TimeUnit.SECONDS),
            )
            val out = ByteArrayOutputStream()
            try {
                // Interruptible, so leaving the screen does not leave this thread blocked on the stream.
                runInterruptible {
                    val chunks = ClientCalls.blockingServerStreamingCall(call, com.google.protobuf.Empty.getDefaultInstance())
                    while (chunks.hasNext()) {
                        chunks.next().data.writeTo(out)
                        if (System.nanoTime() >= deadline) break
                    }
                }
            } catch (e: StatusRuntimeException) {
                // No chunk arrived at all before the deadline: that is an empty capture, not a failure.
                if (e.status.code != Status.Code.DEADLINE_EXCEEDED || out.size() > 0) throw e
            } finally {
                call.cancel("mic test finished", null)
            }
            val pcm = out.toByteArray()
            MicTestResult(pcm.size, (System.nanoTime() - startedAt) / 1_000_000, pcm16Stats(pcm))
        }

    /**
     * Synthesize [text] and play it through the robot's speaker primitive (web client's
     * play_tts_test, unary TTS path). Success means the speaker accepted the stream; it cannot
     * prove sound came out, because the robot-side driver does not report playback failures.
     */
    suspend fun testSpeakerRoute(
        atlasEndpoint: String,
        speakerNodeId: String,
        ttsNodeId: String,
        text: String,
    ): SpeakerTestResult = withContext(Dispatchers.IO) {
        val ttsEndpoint = atlasClient.discoverEndpoint(atlasEndpoint, CONTRACT_TTS, ttsNodeId)
        val speakerEndpoint = atlasClient.discoverEndpoint(atlasEndpoint, CONTRACT_SPEAKER, speakerNodeId)
        val synth = ClientCalls.blockingUnaryCall(
            channelProvider.getChannel(ttsEndpoint).newCall(
                TTS_SYNTHESIZE_METHOD,
                io.grpc.CallOptions.DEFAULT.withDeadlineAfter(15, TimeUnit.SECONDS),
            ),
            com.robonix.proto.tts.Synthesize_Request.newBuilder()
                // The app's language setting is "system"/"zh"/"en", not the BCP-47 tag this field takes.
                .setText(text).setLanguage("").setVoice("").setSpeed(1.0f).build(),
        )
        if (synth.error.isNotBlank()) throw RuntimeException(synth.error)
        val audio = synth.audioData.toByteArray()
        if (audio.isEmpty()) throw RuntimeException("TTS returned no audio")
        val rate = synth.sampleRateHz.takeIf { it > 0 } ?: 16000
        sendToSpeaker(channelProvider.getChannel(speakerEndpoint), speakerChunks(audio, rate))
        SpeakerTestResult(audio.size, rate)
    }

    private suspend fun sendToSpeaker(channel: io.grpc.Channel, chunks: List<AudioChunk>) {
        val done = CompletableFuture<Unit>()
        val call = channel.newCall(
            SPEAKER_METHOD,
            io.grpc.CallOptions.DEFAULT.withDeadlineAfter(30, TimeUnit.SECONDS),
        )
        val requests = ClientCalls.asyncClientStreamingCall(call, object : StreamObserver<com.google.protobuf.Empty> {
            override fun onNext(value: com.google.protobuf.Empty) {}
            override fun onError(t: Throwable) { done.completeExceptionally(t) }
            override fun onCompleted() { done.complete(Unit) }
        })
        try {
            try {
                chunks.forEach { requests.onNext(it) }
                requests.onCompleted()
            } catch (e: RuntimeException) {
                // A call the server already failed rejects further frames; its status is the real error.
                if (!done.isCompletedExceptionally) throw e
            }
            runInterruptible { done.get(35, TimeUnit.SECONDS) }
        } catch (e: ExecutionException) {
            throw e.cause ?: e
        } catch (e: TimeoutException) {
            throw RuntimeException("speaker did not finish within 35 s", e)
        } finally {
            call.cancel("speaker test finished", null)
        }
    }

    /** Whether this phone holds the robot's reverse-audio slot, or lost it to another client. */
    val bridgeStatus: StateFlow<AudioBridgeStatus> get() = audioBridge.status

    private val bridgeConnectMutex = Mutex()

    suspend fun connectBridge(
        atlasEndpoint: String,
        micNodeId: String,
        speakerNodeId: String,
        takeOver: Boolean = false,
    ): BridgeConnectResult {
        if (micNodeId.isBlank() && speakerNodeId.isBlank()) return BridgeConnectResult.NotNeeded
        val expectedGeneration = audioBridge.connectionGeneration()
        return bridgeConnectMutex.withLock {
            var attempt: Long? = null
            try {
                val providerId = pickBridgeProvider(micNodeId, speakerNodeId, bridgeProviderIds(atlasEndpoint))
                    ?: return@withLock BridgeConnectResult.NotNeeded
                val route = AudioBridgeRoute(atlasEndpoint, providerId)
                val grpcEndpoint = atlasClient.connectCapability(atlasEndpoint, providerId, CONTRACT_AUDIO_BRIDGE_INFO)
                val info = try {
                    bridgeInfo(grpcEndpoint)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    audioBridge.reportAvailability(route, expectedGeneration, AudioBridgePhase.OccupancyUnknown)
                    throw e
                }
                if (!info.reverse || info.endpoint.isBlank()) return@withLock BridgeConnectResult.NotNeeded
                val url = rewriteWsEndpoint(info.endpoint, atlasEndpoint)
                val status = bridgeStatus.value
                val heldByUs = status.connected && status.route == route && status.url == url
                if (!mayConnectBridge(info.connected, heldByUs, takeOver)) {
                    audioBridge.reportAvailability(route, expectedGeneration, AudioBridgePhase.Occupied)
                    return@withLock BridgeConnectResult.Busy(providerId)
                }

                // Reusing the same connection is safe even if another request connected it while we waited.
                val generation = if (heldByUs) status.generation else expectedGeneration
                if (audioBridge.connectionGeneration() != generation) {
                    return@withLock BridgeConnectResult.Failed("bridge request was superseded")
                }
                attempt = audioBridge.connect(route, url, generation, takeOver) {
                    try {
                        bridgeInfo(grpcEndpoint).connected
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        null
                    }
                } ?: return@withLock BridgeConnectResult.Failed("bridge request was superseded")
                if (audioBridge.awaitConnected(attempt)) {
                    BridgeConnectResult.Connected(url)
                } else {
                    BridgeConnectResult.Failed("bridge handshake did not complete")
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLog.write("AUDIO", "Bridge connection failed", e)
                BridgeConnectResult.Failed(e.message ?: "bridge connection failed")
            } finally {
                // A successful connection stays open. A failed initial handshake may not connect later.
                attempt?.let(audioBridge::abandonHandshake)
            }
        }
    }

    private suspend fun bridgeInfo(grpcEndpoint: String): GetAudioBridgeInfo_Response =
        withContext(Dispatchers.IO) {
            ClientCalls.blockingUnaryCall(
                channelProvider.getChannel(grpcEndpoint).newCall(
                    BRIDGE_INFO_METHOD,
                    io.grpc.CallOptions.DEFAULT.withDeadlineAfter(5, TimeUnit.SECONDS),
                ),
                GetAudioBridgeInfo_Request.newBuilder().build(),
            )
        }

    /** Provider ids that are reverse bridges (their devices live on whichever client holds the slot). */
    suspend fun bridgeProviderIds(atlasEndpoint: String): Set<String> =
        atlasClient.listAudioProviders(atlasEndpoint).third.map { it.id }.toSet()

    /** Release the robot's slot: the robot host changed, or the route no longer uses a bridge. */
    fun disconnectBridge() {
        audioBridge.disconnect()
    }

    @Volatile
    private var appliedRoute: ClientSettings? = null

    fun updateAppliedBridgeRoute(settings: ClientSettings, handsfree: HandsfreeStatus?): Boolean {
        appliedRoute = settings
        return releaseBridgeOutsideAppliedRoute(settings.atlasEndpoint, handsfree)
    }

    fun releaseBridgeOutsideAppliedRoute(atlasEndpoint: String, handsfree: HandsfreeStatus?): Boolean {
        val settings = appliedRoute?.takeIf { it.atlasEndpoint == atlasEndpoint } ?: return false
        return releaseBridgeOutsideRoute(atlasEndpoint, settings.micNodeId, settings.speakerNodeId, handsfree)
    }

    private fun releaseBridgeOutsideRoute(
        atlasEndpoint: String,
        micNodeId: String,
        speakerNodeId: String,
        handsfree: HandsfreeStatus?,
    ): Boolean {
        val route = bridgeStatus.value.route ?: return false
        if (route.atlasEndpoint != atlasEndpoint || route.providerId == micNodeId || route.providerId == speakerNodeId) return false
        // Unknown hands-free settings may still refer to this bridge.
        if (handsfree == null) return false
        if (handsfree.enabled &&
            (route.providerId == handsfree.micProviderId || route.providerId == handsfree.speakerProviderId)
        ) return true
        audioBridge.disconnect(route)
        return false
    }

    /**
     * Rewrite loopback WebSocket URLs to use the actual robot host.
     * E.g. ws://127.0.0.1:60002/client → ws://192.168.61.5:60002/client
     */
    private fun rewriteWsEndpoint(raw: String, atlasEndpoint: String): String {
        val atlasHost = atlasEndpoint.substringBeforeLast(":")
        // Parse ws://host:port/path
        val protoEnd = raw.indexOf("://")
        if (protoEnd < 0) return raw
        val proto = raw.substring(0, protoEnd)
        val rest = raw.substring(protoEnd + 3)
        val hostEnd = rest.indexOf('/')
        val hostPort = if (hostEnd >= 0) rest.substring(0, hostEnd) else rest
        val path = if (hostEnd >= 0) rest.substring(hostEnd) else "/client"

        val colonIdx = hostPort.lastIndexOf(':')
        val host = if (colonIdx >= 0) hostPort.substring(0, colonIdx) else hostPort
        val port = if (colonIdx >= 0) hostPort.substring(colonIdx + 1) else "60002"

        val isLoopback = host.isEmpty() || host == "127.0.0.1" ||
            host.equals("localhost", ignoreCase = true) || host == "::1" || host == "0.0.0.0"

        return if (isLoopback && atlasHost.isNotBlank()) {
            "$proto://$atlasHost:$port$path"
        } else {
            raw
        }
    }
}
