package com.robonix.client.ui.audio

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.robonix.client.data.audio.AudioBridge
import com.robonix.client.data.audio.AudioBridgeEvent
import com.robonix.client.data.audio.AudioBridgePhase
import com.robonix.client.data.audio.AudioBridgeStatus
import com.robonix.client.data.model.*
import com.robonix.client.domain.ActiveDevice
import com.robonix.client.domain.AudioRepository
import com.robonix.client.domain.BridgeConnectResult
import com.robonix.client.domain.CaptureVerdict
import com.robonix.client.domain.hasSameAudioRoute
import com.robonix.client.domain.judgeCapture
import com.robonix.client.domain.peakDbfs
import com.robonix.client.domain.selectionPending
import com.robonix.client.ui.components.BridgeTakeoverDialog
import com.robonix.client.ui.i18n.AppStrings
import com.robonix.client.ui.i18n.t
import com.robonix.client.ui.i18n.tStatus
import com.robonix.client.ui.navigation.SharedViewModel
import com.robonix.client.ui.theme.*
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*
import javax.inject.Inject

data class AudioUiState(
    val micProviders: List<AudioProvider> = emptyList(),
    val speakerProviders: List<AudioProvider> = emptyList(),
    val bridgeProviders: List<AudioProvider> = emptyList(),
    val selectedMicProvider: String = "",
    val selectedSpeakerProvider: String = "",
    val selectedMicDevice: String = "",
    val selectedSpeakerDevice: String = "",
    val micDevices: List<AudioDevice> = emptyList(),
    val speakerDevices: List<AudioDevice> = emptyList(),
    val currentMicDevice: String = "",
    val currentSpeakerDevice: String = "",
    val devicesBusy: Boolean = false,
    val applyStatus: String = "",
    val applyOk: Boolean? = null,
    val vuLevel: Float = 0f,
    val audioLog: List<String> = emptyList(),
    val routeLoaded: Boolean = false,
    val providersFound: Int = 0,
    val routeError: String? = null,
    val testStatus: String = "",
    val testResultClass: TestResultClass = TestResultClass.None,
    val isBusy: Boolean = false,
    val bridgeTakeoverPrompt: Boolean = false,
    val bridgeBusy: Boolean = false,
    val bridgeMessage: String = "",
    val isRecording: Boolean = false,
    val enrollUserId: String = "",
    val enrollStatus: String = "",
    val enrollBusy: Boolean = false,
)

enum class TestResultClass { None, Success, Warning, Error }

private const val SPEAKER_TEST_PHRASE = "Robonix speaker test. 如果你听到这句话，语音播放链路正常。"

@HiltViewModel
class AudioViewModel @Inject constructor(
    val audioRepository: AudioRepository,
    val handsfree: com.robonix.client.domain.HandsfreeStateHolder,
    private val audioBridge: AudioBridge,
) : ViewModel() {

    private val _uiState = MutableStateFlow(AudioUiState())
    val uiState: StateFlow<AudioUiState> = _uiState.asStateFlow()

    private var lang: String = AppStrings.SYSTEM

    /** Keep baked status strings in sync with the selected language. */
    fun setLanguage(value: String) { lang = value }

    /** Whether this phone holds the robot's audio bridge, or was replaced by another client. */
    val bridgeStatus: StateFlow<AudioBridgeStatus> get() = audioRepository.bridgeStatus

    private data class PendingTakeover(
        val settings: ClientSettings,
        val micNodeId: String,
        val speakerNodeId: String,
        val resume: (suspend () -> Unit)?,
    )

    private var pendingTakeover: PendingTakeover? = null
    private var handsfreeToggleJob: Job? = null

    fun connectBridge(settings: ClientSettings) {
        viewModelScope.launch { ensureBridge(settings) }
    }

    fun disconnectBridge() {
        dismissBridgeTakeover()
        audioRepository.disconnectBridge()
        _uiState.update { it.copy(bridgeMessage = "") }
    }

    fun dismissBridgeTakeover() {
        pendingTakeover = null
        _uiState.update { it.copy(bridgeTakeoverPrompt = false) }
    }

    fun confirmBridgeTakeover(settings: ClientSettings) {
        val pending = pendingTakeover ?: return
        dismissBridgeTakeover()
        if (!pending.settings.hasSameAudioRoute(settings)) return
        viewModelScope.launch {
            if (ensureBridge(pending.settings, pending.micNodeId, pending.speakerNodeId, takeOver = true)) {
                pending.resume?.invoke()
            }
        }
    }

    private suspend fun ensureBridge(
        settings: ClientSettings,
        micNodeId: String = settings.micNodeId,
        speakerNodeId: String = settings.speakerNodeId,
        takeOver: Boolean = false,
        resume: (suspend () -> Unit)? = null,
    ): Boolean {
        if (settings.atlasEndpoint.isBlank() || _uiState.value.bridgeBusy) return false
        dismissBridgeTakeover()
        _uiState.update { it.copy(bridgeBusy = true, bridgeMessage = "") }
        val result = try {
            audioRepository.connectBridge(settings.atlasEndpoint, micNodeId, speakerNodeId, takeOver)
        } finally {
            _uiState.update { it.copy(bridgeBusy = false) }
        }
        if (result is BridgeConnectResult.Busy) {
            pendingTakeover = PendingTakeover(settings, micNodeId, speakerNodeId, resume)
        }
        _uiState.update {
            it.copy(
                bridgeTakeoverPrompt = result is BridgeConnectResult.Busy,
                bridgeMessage = (result as? BridgeConnectResult.Failed)
                    ?.let { f -> AppStrings.format(lang, "audio.bridge.failed", f.reason) } ?: "",
            )
        }
        addLog("bridge: $result")
        return result is BridgeConnectResult.Connected || result is BridgeConnectResult.NotNeeded
    }

    fun toggleHandsfree(settings: ClientSettings) {
        if (handsfree.busy.value || handsfree.voiceBusy.value || _uiState.value.bridgeBusy ||
            handsfreeToggleJob?.isActive == true || settings.atlasEndpoint.isBlank()
        ) return
        handsfreeToggleJob = viewModelScope.launch {
            val enabled = handsfree.refreshOnce(settings.atlasEndpoint)?.enabled ?: return@launch
            if (enabled) {
                updateHandsfree(settings, enabled = false)
            } else {
                val enable: suspend () -> Unit = { updateHandsfree(settings, enabled = true) }
                if (ensureBridge(settings, resume = enable)) enable()
            }
        }
    }

    private suspend fun updateHandsfree(settings: ClientSettings, enabled: Boolean) {
        val target = settings.atlasEndpoint
        val changed = if (enabled) {
            handsfree.setEnabled(target, true, settings.micNodeId, settings.speakerNodeId)
        } else {
            handsfree.setEnabled(target, false, "", "")
        }
        if (changed) {
            val status = handsfree.refreshOnce(target)
            audioRepository.releaseBridgeOutsideAppliedRoute(target, status)
        }
    }

    fun refreshAudioRoute(
        target: String,
        preferredMicNodeId: String = "",
        preferredSpeakerNodeId: String = "",
        preferredMicDeviceId: String = "",
        preferredSpeakerDeviceId: String = "",
    ) {
        if (target.isBlank()) {
            _uiState.update { it.copy(routeLoaded = false, providersFound = 0, routeError = null) }
            return
        }
        _uiState.update { it.copy(isBusy = true) }
        viewModelScope.launch {
            try {
                val (mic, speaker, bridge) = audioRepository.getAudioProviders(target)
                val micId = _uiState.value.selectedMicProvider.ifBlank {
                    preferredMicNodeId.takeIf { p -> mic.any { it.id == p } } ?: ""
                }
                val spkId = _uiState.value.selectedSpeakerProvider.ifBlank {
                    preferredSpeakerNodeId.takeIf { p -> speaker.any { it.id == p } } ?: ""
                }
                _uiState.update {
                    it.copy(
                        micProviders = mic,
                        speakerProviders = speaker,
                        bridgeProviders = bridge,
                        selectedMicProvider = micId,
                        selectedSpeakerProvider = spkId,
                        routeLoaded = true,
                        providersFound = mic.size + speaker.size,
                        routeError = null,
                        isBusy = false,
                    )
                }
                addLog("route refreshed: ${mic.size}mic ${speaker.size}spk ${bridge.size}bridge")
                if (micId.isNotBlank()) loadMicDevices(target, micId, preferredMicDeviceId)
                if (spkId.isNotBlank()) loadSpeakerDevices(target, spkId, preferredSpeakerDeviceId)
            } catch (e: Exception) {
                _uiState.update { it.copy(routeError = e.message ?: "error", isBusy = false) }
            }
        }
    }

    private suspend fun loadMicDevices(target: String, providerId: String, preferredDeviceId: String = "") {
        _uiState.update { it.copy(devicesBusy = true) }
        try {
            val list = audioRepository.listAudioDevices(target, providerId)
            val inputs = list.devices.filter { d -> d.kind.equals("input", ignoreCase = true) || d.kind.equals("duplex", ignoreCase = true) }
            val preferred = listOf(preferredDeviceId, list.currentInputId)
                .firstOrNull { p -> p.isNotBlank() && inputs.any { it.id == p } } ?: ""
            _uiState.update {
                it.copy(
                    micDevices = inputs,
                    currentMicDevice = list.currentInputId,
                    selectedMicDevice = it.selectedMicDevice.ifBlank { preferred },
                    devicesBusy = false,
                )
            }
            addLog("mic devices (${providerId}): ${list.devices.size}")
        } catch (e: Exception) {
            _uiState.update { it.copy(micDevices = emptyList(), currentMicDevice = "", devicesBusy = false) }
            addLog("mic devices failed: ${e.message}")
        }
    }

    private suspend fun loadSpeakerDevices(target: String, providerId: String, preferredDeviceId: String = "") {
        _uiState.update { it.copy(devicesBusy = true) }
        try {
            val list = audioRepository.listAudioDevices(target, providerId)
            val outputs = list.devices.filter { d -> d.kind.equals("output", ignoreCase = true) || d.kind.equals("duplex", ignoreCase = true) }
            val preferred = listOf(preferredDeviceId, list.currentOutputId)
                .firstOrNull { p -> p.isNotBlank() && outputs.any { it.id == p } } ?: ""
            _uiState.update {
                it.copy(
                    speakerDevices = outputs,
                    currentSpeakerDevice = list.currentOutputId,
                    selectedSpeakerDevice = it.selectedSpeakerDevice.ifBlank { preferred },
                    devicesBusy = false,
                )
            }
            addLog("speaker devices (${providerId}): ${list.devices.size}")
        } catch (e: Exception) {
            _uiState.update { it.copy(speakerDevices = emptyList(), currentSpeakerDevice = "", devicesBusy = false) }
            addLog("speaker devices failed: ${e.message}")
        }
    }

    fun selectMicProvider(target: String, id: String) {
        _uiState.update { it.copy(selectedMicProvider = id, selectedMicDevice = "", micDevices = emptyList(), currentMicDevice = "") }
        if (id.isNotBlank()) viewModelScope.launch { loadMicDevices(target, id) }
    }

    fun selectSpeakerProvider(target: String, id: String) {
        _uiState.update { it.copy(selectedSpeakerProvider = id, selectedSpeakerDevice = "", speakerDevices = emptyList(), currentSpeakerDevice = "") }
        if (id.isNotBlank()) viewModelScope.launch { loadSpeakerDevices(target, id) }
    }

    fun selectMicDevice(id: String) { _uiState.update { it.copy(selectedMicDevice = id) } }
    fun selectSpeakerDevice(id: String) { _uiState.update { it.copy(selectedSpeakerDevice = id) } }

    /** Push the configured mic/speaker route to the robot (web Apply button). */
    fun applyRoute(settings: ClientSettings, onApplied: (ClientSettings) -> Unit) {
        val target = settings.atlasEndpoint
        if (target.isBlank()) {
            _uiState.update { it.copy(applyStatus = AppStrings.format(lang, "chat.configure.first"), applyOk = false) }
            return
        }
        _uiState.update {
            it.copy(isBusy = true, applyOk = null, applyStatus = AppStrings.format(lang, "audio.route.applying"))
        }
        viewModelScope.launch {
            try {
                val count = audioRepository.applyAudioRoute(
                    atlasEndpoint = target,
                    micNodeId = settings.micNodeId, micDeviceId = settings.micDeviceId,
                    speakerNodeId = settings.speakerNodeId, speakerDeviceId = settings.speakerDeviceId,
                )
                onApplied(settings)
                val holdsOldRoute = audioRepository.updateAppliedBridgeRoute(settings, handsfree.refreshOnce(target))
                _uiState.update {
                    it.copy(
                        isBusy = false, applyOk = true,
                        applyStatus = AppStrings.format(
                            lang, if (holdsOldRoute) "audio.route.applied.handsfree" else "audio.route.applied", count,
                        ),
                    )
                }
                addLog("route applied: $count device(s)")
                // Re-read what the providers now use, so the in-use marks follow the apply.
                if (settings.micNodeId.isNotBlank()) loadMicDevices(target, settings.micNodeId)
                if (settings.speakerNodeId.isNotBlank()) loadSpeakerDevices(target, settings.speakerNodeId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        isBusy = false, applyOk = false,
                        applyStatus = AppStrings.format(lang, "audio.route.apply.failed", e.message ?: ""),
                    )
                }
            }
        }
    }

    fun updateEnrollUserId(id: String) { _uiState.update { it.copy(enrollUserId = id) } }

    /** Record ~6s locally and enroll as the user's voiceprint. */
    fun enrollVoiceprint(settings: ClientSettings) {
        val state = _uiState.value
        if (state.enrollBusy) return
        val target = settings.atlasEndpoint
        if (target.isBlank()) {
            _uiState.update { it.copy(enrollStatus = AppStrings.format(lang, "chat.configure.first")) }
            return
        }
        _uiState.update { it.copy(enrollBusy = true, enrollStatus = AppStrings.format(lang, "audio.vp.recording")) }
        viewModelScope.launch {
            try {
                val outcome = audioRepository.enrollVoiceprint(
                    atlasEndpoint = target,
                    voiceprintNodeId = settings.voiceprintNodeId,
                    userId = state.enrollUserId,
                    userName = state.enrollUserId,
                )
                _uiState.update {
                    it.copy(enrollBusy = false, enrollStatus = AppStrings.format(lang, "audio.vp.enrolled", outcome))
                }
                addLog("voiceprint: $outcome")
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(enrollBusy = false, enrollStatus = AppStrings.format(lang, "audio.vp.failed", e.message ?: ""))
                }
            }
        }
    }

    /**
     * Capture ~1 s through the robot's current mic route, the path ASR and voiceprint use.
     * A bridge route needs this phone to hold the bridge first, or the robot hears another client.
     */
    fun testMicrophone(settings: ClientSettings) {
        if (_uiState.value.isBusy) return
        val target = settings.atlasEndpoint
        if (target.isBlank()) {
            setTestResult(AppStrings.format(lang, "chat.configure.first"), TestResultClass.Error)
            return
        }
        val hf = handsfree.status.value
        if (hf?.enabled == true && hf.micProviderId == settings.micNodeId) {
            setTestResult(AppStrings.format(lang, "audio.diag.mic.hf"), TestResultClass.Error)
            return
        }
        _uiState.update {
            it.copy(isBusy = true, testStatus = AppStrings.format(lang, "audio.diag.capturing"), testResultClass = TestResultClass.None)
        }
        viewModelScope.launch {
            try {
                if (!holdBridgeForTest(settings, micNodeId = settings.micNodeId) { testMicrophone(settings) }) {
                    return@launch
                }
                val result = audioRepository.testMicRoute(target, settings.micNodeId, 1f)
                val device = audioRepository.activeDevice(target, settings.micNodeId, "input")
                val stats = result.stats
                val verdict = judgeCapture(stats)
                val measured = AppStrings.format(
                    lang, "audio.diag.mic.ok",
                    result.bytes, result.captureMs, "%.4f".format(Locale.US, stats.rms),
                    peakDbfs(stats.peak)?.toString() ?: "-∞",
                )
                val (text, level) = when (verdict) {
                    CaptureVerdict.Empty -> AppStrings.format(lang, "audio.diag.mic.empty") to TestResultClass.Error
                    CaptureVerdict.Silent -> AppStrings.format(lang, "audio.diag.mic.silent") to TestResultClass.Error
                    CaptureVerdict.NearSilence ->
                        "$measured · ${AppStrings.format(lang, "audio.diag.mic.near_silence")}" to TestResultClass.Warning
                    CaptureVerdict.Ok -> measured to TestResultClass.Success
                }
                setTestResult(text + deviceSuffix(device), level)
                addLog("mic test: $verdict ${result.bytes}B in ${result.captureMs}ms peak=${stats.peak} device=${device?.id}")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                setTestResult(AppStrings.format(lang, "audio.diag.failed", e.message ?: ""), TestResultClass.Error)
                addLog("mic test failed: ${e.message}")
            }
        }
    }

    /**
     * Synthesize a phrase and play it through the robot's current speaker route. Completing
     * only means the speaker accepted the audio: the robot-side driver does not report playback
     * failures, so the result asks the user whether they heard it.
     */
    fun testSpeaker(settings: ClientSettings) {
        if (_uiState.value.isBusy) return
        val target = settings.atlasEndpoint
        if (target.isBlank()) {
            setTestResult(AppStrings.format(lang, "chat.configure.first"), TestResultClass.Error)
            return
        }
        _uiState.update {
            it.copy(isBusy = true, testStatus = AppStrings.format(lang, "audio.diag.spk.sending"), testResultClass = TestResultClass.None)
        }
        viewModelScope.launch {
            try {
                if (!holdBridgeForTest(settings, speakerNodeId = settings.speakerNodeId) { testSpeaker(settings) }) {
                    return@launch
                }
                val result = audioRepository.testSpeakerRoute(
                    atlasEndpoint = target,
                    speakerNodeId = settings.speakerNodeId,
                    ttsNodeId = settings.ttsNodeId,
                    text = SPEAKER_TEST_PHRASE,
                )
                val device = audioRepository.activeDevice(target, settings.speakerNodeId, "output")
                setTestResult(
                    AppStrings.format(lang, "audio.diag.spk.sent", result.bytes) + deviceSuffix(device) +
                        " · " + AppStrings.format(lang, "audio.diag.spk.heard"),
                    TestResultClass.Success,
                )
                addLog("speaker test: sent ${result.bytes}B at ${result.sampleRateHz}Hz device=${device?.id}")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                setTestResult(AppStrings.format(lang, "audio.diag.failed", e.message ?: ""), TestResultClass.Error)
                addLog("speaker test failed: ${e.message}")
            }
        }
    }

    /**
     * False when the test cannot run yet. If another client holds the bridge, the takeover
     * prompt is shown and [retry] runs after the user confirms.
     */
    private suspend fun holdBridgeForTest(
        settings: ClientSettings,
        micNodeId: String = "",
        speakerNodeId: String = "",
        retry: () -> Unit,
    ): Boolean {
        if (ensureBridge(settings, micNodeId, speakerNodeId, resume = retry)) return true
        val state = _uiState.value
        if (state.bridgeTakeoverPrompt) {
            _uiState.update { it.copy(isBusy = false, testStatus = "", testResultClass = TestResultClass.None) }
        } else {
            val reason = state.bridgeMessage.ifBlank { AppStrings.format(lang, "audio.bridge.failed", "") }
            setTestResult(reason, TestResultClass.Error)
        }
        return false
    }

    private fun setTestResult(text: String, level: TestResultClass) {
        _uiState.update { it.copy(isBusy = false, testStatus = text, testResultClass = level) }
    }

    private fun deviceSuffix(device: ActiveDevice?): String {
        if (device == null) return ""
        val name = device.name.ifBlank { AppStrings.format(lang, "audio.device.os_default") }
        return " · " + AppStrings.format(lang, "audio.diag.device", name)
    }

    fun startRecording() {
        _uiState.update { it.copy(isRecording = true) }
        viewModelScope.launch {
            audioRepository.recordAudio().collect { bytes ->
                val rms = pcmRms(bytes)
                _uiState.update { it.copy(vuLevel = (rms * 3f).coerceIn(0f, 1f)) }
            }
        }
    }

    fun stopRecording() {
        audioRepository.stopRecording()
        _uiState.update { it.copy(isRecording = false, vuLevel = 0f) }
    }

    private fun addLog(line: String) {
        val ts = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        _uiState.update {
            it.copy(audioLog = (it.audioLog + "[$ts] $line").takeLast(120))
        }
    }

    private fun pcmRms(pcm: ByteArray): Float {
        var sum = 0.0; var n = 0
        for (i in 0 until pcm.size - 1 step 2) {
            val s = ((pcm[i + 1].toInt() shl 8) or (pcm[i].toInt() and 0xff)).toShort().toInt()
            sum += s.toDouble() * s; n++
        }
        return if (n > 0) (Math.sqrt(sum / n) / 32768.0).toFloat() else 0f
    }
}

@Composable
fun AudioScreen(
    viewModel: AudioViewModel = hiltViewModel(),
    sharedViewModel: SharedViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsState()
    val settings by sharedViewModel.settings.collectAsState()
    val handsfreeStatus by viewModel.handsfree.status.collectAsState()
    val handsfreeBusy by viewModel.handsfree.busy.collectAsState()
    val handsfreeError by viewModel.handsfree.error.collectAsState()
    val voiceBusy by viewModel.handsfree.voiceBusy.collectAsState()
    val bridge by viewModel.bridgeStatus.collectAsState()
    val context = LocalContext.current
    val micCurrent = state.currentDevice(settings, bridge, state.selectedMicProvider, state.currentMicDevice)
    val speakerCurrent = state.currentDevice(settings, bridge, state.selectedSpeakerProvider, state.currentSpeakerDevice)

    // Permission launchers
    val permLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) viewModel.testMicrophone(settings)
    }
    val enrollPermLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) viewModel.enrollVoiceprint(sharedViewModel.settings.value)
    }

    LaunchedEffect(settings.atlasEndpoint) {
        viewModel.refreshAudioRoute(
            settings.atlasEndpoint, settings.micNodeId, settings.speakerNodeId,
            settings.micDeviceId, settings.speakerDeviceId)
    }

    LaunchedEffect(settings.language) {
        viewModel.setLanguage(settings.language)
    }

    // Hands-free status polling lives only while this screen is visible.
    // Explicit stop → start so a changed endpoint restarts with the new target.
    LaunchedEffect(settings.atlasEndpoint) {
        viewModel.handsfree.stopPolling()
        viewModel.handsfree.startPolling(settings.atlasEndpoint)
    }
    DisposableEffect(Unit) {
        onDispose { viewModel.handsfree.stopPolling() }
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        // Audio Route
        item {
            PanelCard(t("audio.route.title"), subtitle = t("audio.route.subtitle")) {
                // Input provider
                Text(t("audio.route.input"), color = Muted, fontSize = 11.sp)
                Dropdown(
                    state.micProviders.associate { it.id to "${it.id} (${it.namespace})" },
                    state.selectedMicProvider,
                    { id ->
                        viewModel.selectMicProvider(settings.atlasEndpoint, id)
                        sharedViewModel.updateSettings { it.copy(micNodeId = id, micDeviceId = "") }
                    },
                    t("audio.route.select.input"),
                )
                Spacer(Modifier.height(6.dp))

                // Input device (from the selected provider)
                Text(t("audio.route.device.input"), color = Muted, fontSize = 11.sp)
                Dropdown(
                    deviceOptions(state.micDevices, micCurrent),
                    state.selectedMicDevice,
                    { id ->
                        viewModel.selectMicDevice(id)
                        sharedViewModel.updateSettings { it.copy(micDeviceId = id) }
                    },
                    if (state.devicesBusy) t("audio.route.device.loading") else t("audio.route.device.none"),
                )
                DevicePendingHint(state.micDevices, state.selectedMicDevice, micCurrent)
                Spacer(Modifier.height(6.dp))

                // Output provider
                Text(t("audio.route.output"), color = Muted, fontSize = 11.sp)
                Dropdown(
                    state.speakerProviders.associate { it.id to "${it.id} (${it.namespace})" },
                    state.selectedSpeakerProvider,
                    { id ->
                        viewModel.selectSpeakerProvider(settings.atlasEndpoint, id)
                        sharedViewModel.updateSettings { it.copy(speakerNodeId = id, speakerDeviceId = "") }
                    },
                    t("audio.route.select.output"),
                )
                Spacer(Modifier.height(6.dp))

                // Output device
                Text(t("audio.route.device.output"), color = Muted, fontSize = 11.sp)
                Dropdown(
                    deviceOptions(state.speakerDevices, speakerCurrent),
                    state.selectedSpeakerDevice,
                    { id ->
                        viewModel.selectSpeakerDevice(id)
                        sharedViewModel.updateSettings { it.copy(speakerDeviceId = id) }
                    },
                    if (state.devicesBusy) t("audio.route.device.loading") else t("audio.route.device.none"),
                )
                DevicePendingHint(state.speakerDevices, state.selectedSpeakerDevice, speakerCurrent)
                Spacer(Modifier.height(10.dp))

                BridgeControls(
                    settings, state, bridge,
                    onConnect = { viewModel.connectBridge(sharedViewModel.settings.value) },
                    onDisconnect = viewModel::disconnectBridge,
                )

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = {
                            viewModel.refreshAudioRoute(
                                settings.atlasEndpoint, settings.micNodeId, settings.speakerNodeId,
                                settings.micDeviceId, settings.speakerDeviceId)
                        },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(8.dp),
                    ) {
                        Icon(Icons.Default.Refresh, null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(t("action.refresh"), fontSize = 12.sp)
                    }
                    Button(
                        onClick = {
                            viewModel.applyRoute(sharedViewModel.settings.value, sharedViewModel::saveSettings)
                        },
                        enabled = !state.isBusy,
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(8.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Blue, contentColor = Bg),
                    ) {
                        Icon(Icons.Default.Check, null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(t("audio.route.apply"), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                }
                val routeError = state.routeError
                val routeStatus = when {
                    state.applyStatus.isNotBlank() -> state.applyStatus
                    routeError != null -> t("audio.route.status.error", routeError)
                    state.routeLoaded -> t("audio.route.status.found", state.providersFound)
                    settings.atlasEndpoint.isBlank() -> t("audio.route.status.first")
                    else -> t("audio.route.status.initial")
                }
                Text(
                    routeStatus,
                    color = when (state.applyOk) {
                        true -> Green
                        false -> Red
                        else -> Muted
                    },
                    fontSize = 11.sp,
                )
            }
        }

        // Hands-free mode
        item {
            PanelCard(t("audio.hf.title"), subtitle = t("audio.hf.subtitle")) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            when {
                                handsfreeStatus?.enabled == true ->
                                    handsfreeStatus?.state?.takeIf { it.isNotBlank() }?.let { s -> t("audio.hf.on") + " · " + tStatus(s) }
                                        ?: t("audio.hf.on")
                                else -> t("audio.hf.off")
                            },
                            color = if (handsfreeStatus?.enabled == true) Green else Muted,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                        )
                        if (handsfreeStatus?.keyword?.isNotBlank() == true) {
                            Text(
                                t("audio.hf.keyword", handsfreeStatus?.keyword ?: ""),
                                color = Cyan, fontSize = 11.sp,
                            )
                        }
                        if (handsfreeStatus?.lastTranscript?.isNotBlank() == true) {
                            Text(
                                t("audio.hf.last.transcript", handsfreeStatus?.lastTranscript ?: ""),
                                color = Muted, fontSize = 11.sp, maxLines = 1,
                            )
                        }
                    }
                    Switch(
                        checked = handsfreeStatus?.enabled == true,
                        onCheckedChange = { viewModel.toggleHandsfree(sharedViewModel.settings.value) },
                        enabled = !handsfreeBusy && !voiceBusy && !state.bridgeBusy,
                        colors = SwitchDefaults.colors(
                            checkedTrackColor = Green,
                            checkedThumbColor = Bg,
                        ),
                    )
                }
                if (voiceBusy) {
                    Spacer(Modifier.height(4.dp))
                    Text(t("audio.hf.voicebusy"), color = Amber, fontSize = 11.sp)
                }
                if (handsfreeError != null) {
                    Spacer(Modifier.height(4.dp))
                    Text(t("audio.hf.error", handsfreeError ?: ""), color = Red, fontSize = 11.sp)
                }
            }
        }

        // Diagnostics
        item {
            PanelCard(t("audio.diag.title"), subtitle = t("audio.diag.subtitle")) {
                val hasPerm = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                    PackageManager.PERMISSION_GRANTED

                if (!hasPerm) {
                    Button(
                        onClick = { permLauncher.launch(Manifest.permission.RECORD_AUDIO) },
                        colors = ButtonDefaults.buttonColors(containerColor = Amber),
                        shape = RoundedCornerShape(8.dp),
                    ) {
                        Text(t("audio.diag.grant"), fontSize = 12.sp)
                    }
                    Spacer(Modifier.height(8.dp))
                }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilledTonalButton(
                        onClick = {
                            if (hasPerm) viewModel.testMicrophone(settings)
                            else permLauncher.launch(Manifest.permission.RECORD_AUDIO)
                        },
                        enabled = !state.isBusy,
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(8.dp),
                    ) {
                        Icon(Icons.Default.Mic, null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(t("audio.diag.mic"), fontSize = 12.sp)
                    }
                    FilledTonalButton(
                        onClick = { viewModel.testSpeaker(settings) },
                        enabled = !state.isBusy,
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(8.dp),
                    ) {
                        Icon(Icons.Default.VolumeUp, null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(t("audio.diag.spk"), fontSize = 12.sp)
                    }
                }

                if (state.testStatus.isNotBlank()) {
                    Spacer(Modifier.height(8.dp))
                    Surface(
                        color = when (state.testResultClass) {
                            TestResultClass.Success -> Green.copy(alpha = 0.1f)
                            TestResultClass.Warning -> Amber.copy(alpha = 0.1f)
                            TestResultClass.Error -> Red.copy(alpha = 0.1f)
                            TestResultClass.None -> Panel2
                        },
                        shape = RoundedCornerShape(6.dp),
                    ) {
                        Row(modifier = Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                when (state.testResultClass) {
                                    TestResultClass.Success -> Icons.Default.CheckCircle
                                    TestResultClass.Warning -> Icons.Default.Warning
                                    TestResultClass.Error -> Icons.Default.Error
                                    TestResultClass.None -> Icons.Default.Info
                                },
                                null,
                                tint = when (state.testResultClass) {
                                    TestResultClass.Success -> Green
                                    TestResultClass.Warning -> Amber
                                    TestResultClass.Error -> Red
                                    TestResultClass.None -> Muted
                                },
                                modifier = Modifier.size(16.dp),
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(state.testStatus, color = Text, fontSize = 12.sp)
                        }
                    }
                }
            }
        }

        // VU Meter
        item {
            PanelCard(t("audio.vu.title"), subtitle = t("audio.vu.subtitle")) {
                if (state.isRecording) {
                    VuMeter(state.vuLevel)
                    Text("${(state.vuLevel * 100).toInt()}%", color = Text, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                    Button(
                        onClick = { viewModel.stopRecording() },
                        colors = ButtonDefaults.buttonColors(containerColor = Red),
                        shape = RoundedCornerShape(8.dp),
                    ) { Text(t("audio.vu.stop"), fontSize = 12.sp) }
                } else {
                    Text(t("audio.vu.hint"), color = Muted, fontSize = 12.sp)
                    Spacer(Modifier.height(6.dp))
                    OutlinedButton(
                        onClick = { viewModel.startRecording() },
                        shape = RoundedCornerShape(8.dp),
                    ) { Text(t("audio.vu.start"), fontSize = 12.sp) }
                }
            }
        }

        // Voiceprint
        item {
            PanelCard(t("audio.vp.title"), subtitle = t("audio.vp.subtitle")) {
                OutlinedTextField(
                    value = state.enrollUserId,
                    onValueChange = { viewModel.updateEnrollUserId(it) },
                    label = { Text(t("audio.vp.user"), fontSize = 12.sp) },
                    modifier = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = Text, unfocusedTextColor = Text,
                        focusedBorderColor = Cyan, unfocusedBorderColor = Line,
                        focusedLabelColor = Cyan, unfocusedLabelColor = Muted,
                        cursorColor = Cyan,
                        focusedContainerColor = Panel2, unfocusedContainerColor = Panel2,
                    ),
                    singleLine = true,
                )
                Spacer(Modifier.height(8.dp))
                Button(
                    onClick = {
                        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                            PackageManager.PERMISSION_GRANTED
                        ) {
                            viewModel.enrollVoiceprint(sharedViewModel.settings.value)
                        } else {
                            enrollPermLauncher.launch(Manifest.permission.RECORD_AUDIO)
                        }
                    },
                    enabled = state.enrollUserId.isNotBlank() && !state.enrollBusy,
                    colors = ButtonDefaults.buttonColors(containerColor = Blue),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(
                        Icons.Default.Mic, null,
                        tint = if (state.enrollBusy) Red else Bg,
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        if (state.enrollBusy) t("audio.vp.recording") else t("audio.vp.enroll"),
                        fontSize = 12.sp, fontWeight = FontWeight.Bold,
                    )
                }
                if (state.enrollStatus.isNotBlank()) {
                    Spacer(Modifier.height(6.dp))
                    Text(state.enrollStatus, color = Muted, fontSize = 11.sp)
                }
            }
        }

        // Log
        item {
            PanelCard(t("audio.log.title"), subtitle = t("audio.log.subtitle")) {
                val logs = state.audioLog.takeLast(20)
                if (logs.isEmpty()) {
                    Text(t("audio.log.empty"), color = Muted, fontSize = 11.sp)
                } else {
                    logs.forEach { line ->
                        Text(
                            line,
                            color = Dim,
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace,
                            lineHeight = 14.sp,
                        )
                    }
                }
            }
        }

        item { Spacer(Modifier.height(32.dp)) }
    }

    if (state.bridgeTakeoverPrompt) {
        BridgeTakeoverDialog(
            onConfirm = { viewModel.confirmBridgeTakeover(sharedViewModel.settings.value) },
            onDismiss = { viewModel.dismissBridgeTakeover() },
        )
    }
}

@Composable
private fun PanelCard(title: String, subtitle: String = "", content: @Composable ColumnScope.() -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = Panel),
        shape = RoundedCornerShape(10.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Text(title, color = Text, fontSize = 14.sp, fontWeight = FontWeight.Bold)
            if (subtitle.isNotBlank()) {
                Text(subtitle, color = Muted, fontSize = 11.sp)
            }
            Spacer(Modifier.height(10.dp))
            content()
        }
    }
}

internal fun AudioUiState.currentDevice(
    settings: ClientSettings,
    bridge: AudioBridgeStatus,
    providerId: String,
    deviceId: String,
): String? {
    if (bridgeProviders.none { it.id == providerId }) return deviceId
    return deviceId.takeIf {
        bridge.connected && bridge.route?.atlasEndpoint == settings.atlasEndpoint &&
            bridge.route.providerId == providerId
    }
}

@Composable
internal fun BridgeControls(
    settings: ClientSettings,
    audioState: AudioUiState,
    bridge: AudioBridgeStatus,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
) {
    val selectedProvider = audioState.bridgeProviders.firstOrNull {
        it.id == settings.micNodeId
    } ?: audioState.bridgeProviders.firstOrNull { it.id == settings.speakerNodeId }
    val localBridge = bridge.takeIf { it.route?.atlasEndpoint == settings.atlasEndpoint }
    if (selectedProvider == null && localBridge == null) return

    val phase = localBridge?.phase ?: AudioBridgePhase.Disconnected
    val status = when (phase) {
        AudioBridgePhase.Disconnected -> "audio.bridge.status.off"
        AudioBridgePhase.Connecting -> "audio.bridge.status.connecting"
        AudioBridgePhase.Connected -> "audio.bridge.status.connected"
        AudioBridgePhase.Occupied -> "audio.bridge.status.occupied"
        AudioBridgePhase.TakenOver -> "audio.bridge.status.taken"
        AudioBridgePhase.OccupancyUnknown -> "audio.bridge.status.unknown"
    }
    val canDisconnect = localBridge != null && phase in listOf(
        AudioBridgePhase.Connected, AudioBridgePhase.Connecting,
        AudioBridgePhase.Occupied, AudioBridgePhase.OccupancyUnknown,
    )
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(t("audio.bridge.title"), color = Muted, fontSize = 11.sp)
            Text(t(status), color = if (localBridge?.connected == true) Green else Muted, fontSize = 12.sp)
            if (audioState.bridgeMessage.isNotBlank()) {
                Text(audioState.bridgeMessage, color = Red, fontSize = 11.sp)
            }
        }
        if (selectedProvider != null && (localBridge?.connected != true || localBridge.route?.providerId != selectedProvider.id)) {
            TextButton(onClick = onConnect, enabled = !audioState.bridgeBusy) {
                Text(t(if (phase == AudioBridgePhase.TakenOver) "audio.bridge.takeback" else "action.connect"))
            }
        }
        if (canDisconnect) {
            TextButton(onClick = onDisconnect) { Text(t("audio.bridge.disconnect")) }
        }
    }
    Spacer(Modifier.height(8.dp))
}

/**
 * Dropdown labels for a provider's devices, with the one in use marked. Without that mark
 * the list says what could be selected but not what the robot is recording/playing through.
 */
@Composable
fun deviceOptions(devices: List<AudioDevice>, current: String?): Map<String, String> {
    val inUse = t("audio.device.in_use")
    return devices.associate { d ->
        val name = d.name.ifBlank { d.id }
        d.id to if (d.id == current) "● $name · $inUse" else name
    }
}

/**
 * Warn that the dropdown holds a device the robot is not using yet, so a test that just
 * failed is not blamed on the device shown here. Apply is what makes the selection take effect.
 */
@Composable
fun DevicePendingHint(devices: List<AudioDevice>, selected: String, current: String?) {
    if (current == null || !selectionPending(selected, current, devices.size)) return
    val inUse = devices.firstOrNull { it.id == current }?.name?.takeIf { it.isNotBlank() }
        ?: current.ifBlank { t("audio.device.os_default") }
    Text(t("audio.device.pending", inUse), color = Amber, fontSize = 11.sp)
}

@Composable
fun Dropdown(
    options: Map<String, String>,
    selected: String,
    onSelect: (String) -> Unit,
    placeholder: String,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(
            onClick = { expanded = true },
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(8.dp),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = Text),
        ) {
            Text(
                options[selected] ?: placeholder,
                modifier = Modifier.weight(1f),
                fontSize = 12.sp,
                textAlign = androidx.compose.ui.text.style.TextAlign.Start,
            )
            Icon(Icons.Default.ArrowDropDown, null)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            if (options.isEmpty()) {
                DropdownMenuItem(
                    text = { Text(t("audio.route.none"), fontSize = 12.sp, color = Muted) },
                    onClick = { expanded = false },
                )
            }
            options.forEach { (id, name) ->
                DropdownMenuItem(
                    text = { Text(name, fontSize = 12.sp) },
                    onClick = { onSelect(id); expanded = false },
                )
            }
        }
    }
}

@Composable
fun VuMeter(level: Float) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(14.dp)
            .clip(RoundedCornerShape(999.dp))
            .background(Panel2),
    ) {
        Box(
            modifier = Modifier
                .fillMaxHeight()
                .fillMaxWidth(level.coerceIn(0f, 1f))
                .clip(RoundedCornerShape(999.dp))
                .background(
                    when {
                        level < 0.3f -> Green
                        level < 0.7f -> Amber
                        else -> Red
                    }
                ),
        )
    }
}
