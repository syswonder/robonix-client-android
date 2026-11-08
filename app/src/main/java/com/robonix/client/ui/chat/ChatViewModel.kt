package com.robonix.client.ui.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.robonix.client.data.model.BatchResult
import com.robonix.client.data.model.ChatMessage
import com.robonix.client.data.model.ClientSettings
import com.robonix.client.data.model.MessageRole
import com.robonix.client.data.model.PilotEvent
import com.robonix.client.data.model.RtdlNodeState
import com.robonix.client.data.model.RtdlPlan
import com.robonix.client.data.model.TaskState
import com.robonix.client.data.model.TimelineEvent
import com.robonix.client.data.local.Conversation
import com.robonix.client.data.local.ConversationStore
import com.robonix.client.data.local.PlanHistoryCodec
import kotlinx.coroutines.CancellationException
import com.robonix.client.data.model.VoiceEvent
import com.robonix.client.domain.AudioRepository
import com.robonix.client.domain.ChatRepository
import com.robonix.client.domain.RtdlStateHolder
import com.robonix.client.domain.createTimelineEvent
import com.robonix.client.ui.i18n.AppStrings
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import javax.inject.Inject

data class ChatUiState(
    val messages: List<ChatMessage> = emptyList(),
    val timeline: List<TimelineEvent> = emptyList(),
    val plan: RtdlPlan? = null,
    val planRecords: List<PlanRecord> = emptyList(),
    val nodeStates: Map<Int, RtdlNodeState> = emptyMap(),
    val batches: List<BatchResult> = emptyList(),
    val taskState: TaskState? = null,
    val isBusy: Boolean = false,
    val isTaskRunning: Boolean = false,
    val activeTurnId: String = "",
    val activePilotSessionId: String = "",
    val voiceActive: Boolean = false,
    val ttsPlaying: Boolean = false,
    val activeAgentId: String? = null,
    val sessionId: String = UUID.randomUUID().toString(),
    val sessionTitle: String = "",
    val settings: ClientSettings = ClientSettings(),
    val isRecording: Boolean = false,
    /** Text of the most recent live event while a turn is busy; null when idle. */
    val liveStatus: String? = null,
    /** Incremented on every token chunk arrival so Compose can auto-scroll accurately */
    val streamTokenCount: Long = 0L,
)

data class PlanRecord(
    val key: String,
    val plan: RtdlPlan,
    val nodeStates: Map<Int, RtdlNodeState> = emptyMap(),
    val batches: List<BatchResult> = emptyList(),
    val updatedAt: Long = System.currentTimeMillis(),
)

@HiltViewModel
class ChatViewModel @Inject constructor(
    private val chatRepository: ChatRepository,
    private val rtdlState: RtdlStateHolder,
    private val handsfreeState: com.robonix.client.domain.HandsfreeStateHolder,
    private val audioRepository: AudioRepository,
    private val conversationStore: ConversationStore,
) : ViewModel() {

    private val _uiState = MutableStateFlow(ChatUiState())
    val uiState: StateFlow<ChatUiState> = _uiState.asStateFlow()

    private val _sessions = MutableStateFlow<List<Conversation>>(emptyList())
    val sessions: StateFlow<List<Conversation>> = _sessions.asStateFlow()

    private var submitJob: Job? = null
    private val steerJobs = mutableListOf<Job>()
    private var voiceJob: Job? = null
    private var persistJob: Job? = null
    @Volatile private var sessionEpoch = 0L
    private var sessionTransition = false
    private var handsfreeEpoch = 0L

    /** True while a FinishVoiceCapture call is in flight (mic released early). */
    @Volatile private var finishInFlight = false

    /** True while a task abort request is in flight. */
    @Volatile private var stopInFlight = false

    /**
     * Pre-plan result buffer: when node_state or batch_result arrives before
     * the plan event, buffer them here and apply as soon as the plan lands.
     */
    private val pendingPlanResults = mutableMapOf<String, MutableList<(PlanRecord) -> PlanRecord>>()

    /**
     * The server confirmed recording_started for the running voice session.
     * `isRecording` alone is optimistic (set on press), so releasing before
     * this flag means the hold was too brief → cancel the session instead of
     * asking for an early submit.
     */
    @Volatile private var recordingConfirmed = false

    /** Localize a string baked into state at emission time. */
    private fun l(key: String, vararg args: Any?): String =
        AppStrings.format(_uiState.value.settings.language, key, *args)

    init {
        viewModelScope.launch {
            val epoch = sessionEpoch
            loadSessions()
            restoreLastSession(epoch)
        }
        // Hands-free wake-word sessions arrive through the shared holder and
        // flow into the exact same pipeline as push-to-talk (web parity).
        viewModelScope.launch {
            handsfreeState.events.collect { event ->
                com.robonix.client.AppLog.write("HANDSFREE", "voice event: kind=${event.kind}")
                if (!sessionTransition && event.kind == "session_started") handsfreeEpoch = sessionEpoch
                if (handsfreeEpoch == sessionEpoch) handleVoiceEvent(event, false)
            }
        }
        // Mirror PTT activity so the handsfree toggle can be blocked while busy.
        viewModelScope.launch {
            uiState.collect { handsfreeState.voiceBusy.value = it.voiceActive }
        }
    }

    private suspend fun loadSessions() {
        _sessions.value = conversationStore.loadAll()
    }

    /**
     * Restore the most recent conversation whose messages survived, matching
     * the web client's restoreLastSession (runs once at startup).
     */
    private suspend fun restoreLastSession(epoch: Long) {
        try {
            val all = conversationStore.loadAll()
            val target = all.firstOrNull { parseMessages(it.messagesJson).isNotEmpty() } ?: return
            // Skip only if the user already started or switched a conversation.
            val current = _uiState.value
            if (sessionTransition || epoch != sessionEpoch || current.messages.isNotEmpty() || current.isBusy || current.voiceActive) return
            val restored = restoredConversation(target)
            _uiState.value = restored
            pushRtdlToShared()
            com.robonix.client.AppLog.write("CONV", "restored session ${target.id} (${target.title})")
        } catch (e: Exception) {
            com.robonix.client.AppLog.write("CONV", "restoreLastSession failed: ${e.message}", e)
        }
    }

    /** Delete every locally stored conversation and reset the current chat. */
    fun clearAllSessions() {
        if (sessionTransition) return
        val epoch = ++sessionEpoch
        sessionTransition = true
        submitJob?.cancel()
        submitJob = null
        steerJobs.forEach { it.cancel() }
        steerJobs.clear()
        stopVoiceSession()
        pendingPlanResults.clear()
        viewModelScope.launch {
            try {
                conversationStore.clearAll()
                if (epoch != sessionEpoch) return@launch
                rtdlState.clear()
                _uiState.update { ChatUiState(settings = it.settings) }
                loadSessions()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                com.robonix.client.AppLog.write("CONV", "clearAll failed: ${e.message}", e)
            } finally {
                if (epoch == sessionEpoch) sessionTransition = false
            }
        }
    }

    fun updateSettings(settings: ClientSettings) {
        _uiState.update { it.copy(settings = settings) }
    }

    fun updateSession(sessionId: String, title: String = "") {
        if (sessionTransition) return
        if (sessionId != _uiState.value.sessionId) sessionEpoch++
        _uiState.update { it.copy(sessionId = sessionId, sessionTitle = title) }
        persistCurrentConversation()
    }

    private fun requireEndpoint(): String {
        val ep = _uiState.value.settings.atlasEndpoint
        if (ep.isBlank()) {
            addMessage(ChatMessage(
                id = chatRepository.generateMessageId(),
                role = MessageRole.Error,
                text = l("chat.configure.first"),
            ))
        }
        return ep
    }

    fun sendTask(text: String) {
        val state = _uiState.value
        val wasBusy = hasActiveTurn(state)
        if (text.isBlank() || (state.isBusy && !wasBusy) || sessionTransition || state.voiceActive) return

        val target = requireEndpoint()
        if (target.isBlank()) return

        addMessage(ChatMessage(
            id = chatRepository.generateMessageId(),
            role = MessageRole.User,
            text = text,
            meta = if (wasBusy) "steer" else "",
        ))
        addTimeline(createTimelineEvent(
            if (wasBusy) "steer" else "task",
            if (wasBusy) l("tl.steer", text) else l("tl.task", text),
        ))

        _uiState.update { it.copy(isBusy = true, isTaskRunning = true, liveStatus = null) }

        val sessionToSend = if (wasBusy && state.activePilotSessionId.isNotBlank()) {
            state.activePilotSessionId
        } else {
            state.sessionId
        }

        val epoch = sessionEpoch
        if (wasBusy) {
            // Steer mode: do NOT cancel the active submitJob (main task stream remains open).
            // Launch steer stream in an independent coroutine.
            val steerJob = viewModelScope.launch {
                try {
                    chatRepository.submitTextTask(
                        atlasEndpoint = target,
                        text = text,
                        sessionId = sessionToSend,
                        userId = state.settings.userId.ifBlank { "local:robonix-android" },
                        steer = true,
                        expectedTurnId = state.activeTurnId,
                    ).collect { event -> if (epoch == sessionEpoch) handlePilotEvent(event) }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    if (epoch != sessionEpoch) return@launch
                    addMessage(ChatMessage(
                        id = chatRepository.generateMessageId(),
                        role = MessageRole.Error,
                        text = l("chat.task.failed", e.message ?: l("common.unknown.error")),
                    ))
                    addTimeline(createTimelineEvent("error", l("tl.error.task", e.message ?: l("common.unknown.error"))))
                } finally {
                    if (epoch == sessionEpoch) {
                        persistCurrentConversation()
                    }
                }
            }
            steerJobs.add(steerJob)
            steerJob.invokeOnCompletion { steerJobs.remove(steerJob) }
        } else {
            submitJob?.cancel()
            steerJobs.forEach { it.cancel() }
            steerJobs.clear()
            submitJob = viewModelScope.launch {
                try {
                    chatRepository.submitTextTask(
                        atlasEndpoint = target,
                        text = text,
                        sessionId = sessionToSend,
                        userId = state.settings.userId.ifBlank { "local:robonix-android" },
                        steer = false,
                        expectedTurnId = "",
                    ).collect { event -> if (epoch == sessionEpoch) handlePilotEvent(event) }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    if (epoch != sessionEpoch) return@launch
                    addMessage(ChatMessage(
                        id = chatRepository.generateMessageId(),
                        role = MessageRole.Error,
                        text = l("chat.task.failed", e.message ?: l("common.unknown.error")),
                    ))
                    addTimeline(createTimelineEvent("error", l("tl.error.task", e.message ?: l("common.unknown.error"))))
                } finally {
                    if (epoch == sessionEpoch) {
                        _uiState.update { it.copy(isBusy = false, isTaskRunning = false) }
                        // The stream has closed, so every text chunk is in — persist the
                        // full exchange (agent reply included), not just the user turn
                        // that a mid-stream terminal-status snapshot may have captured.
                        persistCurrentConversation()
                    }
                }
            }
        }
    }

    fun stopTask() {
        val state = _uiState.value
        if (!state.isBusy || stopInFlight) return
        stopInFlight = true

        val target = state.settings.atlasEndpoint
        if (target.isBlank()) {
            stopInFlight = false
            _uiState.update { it.copy(isBusy = false, isTaskRunning = false) }
            return
        }

        addTimeline(createTimelineEvent("cancel", l("tl.abort")))
        stopVoiceSession()
        submitJob?.cancel()
        submitJob = null
        steerJobs.forEach { it.cancel() }
        steerJobs.clear()

        val epoch = sessionEpoch
        viewModelScope.launch {
            try {
                chatRepository.submitAbortTask(
                    atlasEndpoint = target,
                    sessionId = state.sessionId,
                    userId = state.settings.userId.ifBlank { "local:robonix-android" },
                    expectedTurnId = state.activeTurnId,
                ).collect { event -> if (epoch == sessionEpoch) handlePilotEvent(event) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (epoch != sessionEpoch) return@launch
                addMessage(ChatMessage(
                    id = chatRepository.generateMessageId(),
                    role = MessageRole.Error,
                    text = l("chat.stop.failed", e.message ?: l("common.unknown.error")),
                ))
                addTimeline(createTimelineEvent("error", l("tl.error.abort", e.message ?: l("common.unknown.error"))))
            } finally {
                stopInFlight = false
                if (epoch == sessionEpoch) {
                    _uiState.update {
                        it.copy(isBusy = false, isTaskRunning = false, activeTurnId = "", activePilotSessionId = "")
                    }
                    persistCurrentConversation(immediate = true)
                }
            }
        }
    }

    fun startVoice(steer: Boolean = false) {
        val state = _uiState.value
        com.robonix.client.AppLog.write("VOICE", "startVoice called, steer=$steer, voiceActive=${state.voiceActive}, atlasEndpoint=${state.settings.atlasEndpoint}")
        if (state.voiceActive || sessionTransition) return

        val target = state.settings.atlasEndpoint
        if (target.isBlank()) {
            addMessage(ChatMessage(
                id = chatRepository.generateMessageId(),
                role = MessageRole.Error,
                text = l("chat.configure.first"),
            ))
            return
        }

        _uiState.update { it.copy(voiceActive = true, isRecording = true, liveStatus = null) }
        recordingConfirmed = false

        val wasBusy = hasActiveTurn(state)
        addTimeline(createTimelineEvent(
            if (wasBusy) "voice steer" else "voice",
            if (wasBusy) l("tl.steer.requested") else l("tl.session.requested"),
        ))

        voiceJob?.cancel()
        val epoch = sessionEpoch
        voiceJob = viewModelScope.launch {
            var completedNormally = false
            try {
                val bridgeUrl = audioRepository.autoConnectBridge(
                    atlasEndpoint = target,
                    micNodeId = state.settings.micNodeId,
                    speakerNodeId = state.settings.speakerNodeId,
                )
                com.robonix.client.AppLog.write("VOICE", "Bridge result: $bridgeUrl")
                com.robonix.client.AppLog.write("VOICE", "Calling startVoiceSession...")

                chatRepository.startVoiceSession(
                    atlasEndpoint = target,
                    sessionId = state.sessionId,
                    userId = state.settings.userId.ifBlank { "local:robonix-android" },
                    settings = state.settings,
                    steer = wasBusy,
                    expectedTurnId = state.activeTurnId,
                ).collect { event ->
                    com.robonix.client.AppLog.write("VOICE", "VoiceEvent: kind=${event.kind} text=${event.text.take(80)} err=${event.error}")
                    if (epoch == sessionEpoch) handleVoiceEvent(event, wasBusy)
                }
                com.robonix.client.AppLog.write("VOICE", "Voice session completed normally")
                completedNormally = true
            } catch (e: kotlinx.coroutines.CancellationException) {
                com.robonix.client.AppLog.write("VOICE", "Voice session cancelled")
            } catch (e: Exception) {
                com.robonix.client.AppLog.write("VOICE", "Voice session error: ${e.message}", e)
                if (epoch != sessionEpoch) return@launch
                addMessage(ChatMessage(id = chatRepository.generateMessageId(), role = MessageRole.Error, text = l("chat.voice.failed", e.message ?: l("common.unknown.error"))))
                addTimeline(createTimelineEvent("error", l("tl.error.voice", e.message ?: l("common.unknown.error"))))
                completedNormally = true
            } finally {
                if (epoch == sessionEpoch) {
                    _uiState.update {
                        it.copy(
                            voiceActive = false, isRecording = false,
                            // A plain voice turn owns the task it started: once the
                            // stream is closed we can no longer track it, so don't
                            // leave the UI stuck busy (e.g. the robot restarted or
                            // the stream dropped without a session_done). A steer
                            // turn leaves the main task running — keep its state.
                            isBusy = if (wasBusy) it.isBusy else false,
                            isTaskRunning = if (wasBusy) it.isTaskRunning else false,
                        )
                    }
                    // Release the robot's single reverse-audio slot once the turn is
                    // over: keeping it open would fight any other robonix-client
                    // (e.g. the desktop one) for the same slot.
                    audioRepository.disconnectBridge()
                    // Only persist a real turn — a discarded/cancelled press
                    // (too-brief release, stop) leaves junk sessions behind.
                    if (completedNormally) persistCurrentConversation()
                }
            }
        }
    }

    /**
     * Mic button released while holding to talk:
     * - already recording → ask Liaison to finish early and submit the
     *   recognized-so-far text (web client's finish_voice_capture);
     * - recording not started yet → the user tapped too briefly, cancel
     *   the whole voice session instead.
     */
    fun onMicReleased() {
        val state = _uiState.value
        if (!state.voiceActive) return
        if (state.isRecording && recordingConfirmed && !finishInFlight) {
            finishInFlight = true
            addTimeline(createTimelineEvent("voice", l("tl.finishing")))
            val target = state.settings.atlasEndpoint
            viewModelScope.launch {
                try {
                    if (target.isNotBlank()) {
                        chatRepository.finishVoiceCapture(target, state.sessionId)
                    }
                } catch (e: Exception) {
                    com.robonix.client.AppLog.write("VOICE", "finishVoiceCapture failed: ${e.message}", e)
                } finally {
                    finishInFlight = false
                }
            }
        } else if (!recordingConfirmed) {
            // Released before the server even started recording — too brief,
            // discard the session entirely (web: stopActiveVoiceSession).
            stopVoiceSession()
        }
    }

    /** Hard-stop the voice session: cancel the stream entirely (discard). */
    fun stopVoiceSession() {
        com.robonix.client.AppLog.write("VOICE", "stopVoiceSession called, voiceActive=${_uiState.value.voiceActive}")
        finishInFlight = false
        recordingConfirmed = false
        // A session transition changes the epoch before cancellation, so the old
        // stream's finally block deliberately cannot release this shared bridge.
        if (voiceJob != null) audioRepository.disconnectBridge()
        voiceJob?.cancel()
        voiceJob = null
        _uiState.update { it.copy(voiceActive = false, isRecording = false) }
    }

    val isVoiceProcessing: Boolean get() = _uiState.value.voiceActive && !_uiState.value.isRecording

    fun addMessage(message: ChatMessage) {
        _uiState.update { state ->
            state.copy(
                messages = state.messages + message,
                activeAgentId = if (message.role == MessageRole.Agent) state.activeAgentId else null,
            )
        }
    }

    fun appendAgentText(text: String) {
        _uiState.update { state ->
            val activeId = state.activeAgentId
            if (activeId != null) {
                state.copy(
                    messages = state.messages.map { msg ->
                        if (msg.id == activeId) msg.copy(text = msg.text + text) else msg
                    },
                    streamTokenCount = state.streamTokenCount + 1,
                )
            } else {
                val newId = chatRepository.generateMessageId()
                state.copy(
                    messages = state.messages + ChatMessage(
                        id = newId, role = MessageRole.Agent,
                        text = text, meta = "Robonix",
                    ),
                    activeAgentId = newId,
                    streamTokenCount = state.streamTokenCount + 1,
                )
            }
        }
    }

    fun finalizeAgentText(text: String) {
        _uiState.update { state ->
            val activeId = state.activeAgentId
            if (activeId != null) {
                state.copy(
                    messages = state.messages.map { msg ->
                        if (msg.id == activeId) msg.copy(text = mergeFinalText(msg.text, text)) else msg
                    },
                    activeAgentId = null,
                )
            } else {
                state.copy(
                    messages = state.messages + ChatMessage(
                        id = chatRepository.generateMessageId(),
                        role = MessageRole.Agent, text = text, meta = "Robonix",
                    ),
                )
            }
        }
    }

    fun clearSession() {
        deleteSession(_uiState.value.sessionId)
    }

    // ---- Session management (matches web client's newSession / openConversation) ----

    fun newSession() {
        val state = _uiState.value
        if (state.isBusy || sessionTransition) return
        sessionTransition = true
        val epoch = ++sessionEpoch
        submitJob?.cancel()
        submitJob = null
        steerJobs.forEach { it.cancel() }
        steerJobs.clear()
        stopVoiceSession()
        pendingPlanResults.clear()
        val revision = conversationStore.revision()
        viewModelScope.launch {
            try {
                persistConversationDirect(state, revision)
                if (epoch != sessionEpoch) return@launch
                _uiState.update { ChatUiState(settings = it.settings) }
                rtdlState.clear()
                loadSessions()
            } finally {
                if (epoch == sessionEpoch) sessionTransition = false
            }
        }
    }

    fun switchToSession(sessionId: String) {
        val state = _uiState.value
        if (state.isBusy || sessionTransition || state.sessionId == sessionId) return
        sessionTransition = true
        val epoch = ++sessionEpoch
        submitJob?.cancel()
        submitJob = null
        steerJobs.forEach { it.cancel() }
        steerJobs.clear()
        stopVoiceSession()
        pendingPlanResults.clear()
        val revision = conversationStore.revision()
        viewModelScope.launch {
            try {
                persistConversationDirect(state, revision)
                val all = conversationStore.loadAll()
                val target = all.find { it.id == sessionId } ?: return@launch
                if (epoch != sessionEpoch) return@launch
                _uiState.value = restoredConversation(target)
                pushRtdlToShared()
                _sessions.value = all
            } finally {
                if (epoch == sessionEpoch) sessionTransition = false
            }
        }
    }

    fun deleteSession(sessionId: String) {
        if (sessionTransition) return
        val deletingCurrent = _uiState.value.sessionId == sessionId
        sessionTransition = true
        // Deleting another history entry must not invalidate the active task/voice stream.
        val epoch = if (deletingCurrent) ++sessionEpoch else sessionEpoch
        if (deletingCurrent) {
            submitJob?.cancel()
            submitJob = null
            steerJobs.forEach { it.cancel() }
            steerJobs.clear()
            stopVoiceSession()
            pendingPlanResults.clear()
            _uiState.update { ChatUiState(settings = it.settings) }
            rtdlState.clear()
        }
        viewModelScope.launch {
            try {
                conversationStore.delete(sessionId)
                if (epoch == sessionEpoch) loadSessions()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                com.robonix.client.AppLog.write("CONV", "delete failed: ${e.message}", e)
            } finally {
                if (epoch == sessionEpoch) sessionTransition = false
            }
        }
    }

    /**
     * Persist the current conversation to local storage, matching the web
     * client's persistCurrentConversation behaviour. Debounced by 300ms
     * unless [immediate] is true.
     */
    private fun persistCurrentConversation(immediate: Boolean = false) {
        val snapshot = _uiState.value
        val epoch = sessionEpoch
        val revision = conversationStore.revision()
        persistJob?.cancel()
        persistJob = viewModelScope.launch {
            if (!immediate) {
                kotlinx.coroutines.delay(300L)
            }
            if (epoch == sessionEpoch) {
                persistConversationDirect(snapshot, revision)
            }
        }
    }

    private suspend fun persistConversationDirect(s: ChatUiState, revision: Long = conversationStore.revision()) {
        try {
            if (s.messages.isEmpty() && s.timeline.isEmpty() && s.plan == null && s.planRecords.isEmpty()) return  // nothing to save

            val title = s.sessionTitle.ifBlank {
                s.messages.firstOrNull { it.role == MessageRole.User }?.text?.take(60) ?: l("session.default.title")
            }

            // Serialize to JSON matching web client's conversation shape
            val msgsJson = JSONArray().apply {
                s.messages.forEach { m ->
                    put(JSONObject().apply {
                        put("id", m.id)
                        put("role", m.role.name)
                        put("text", m.text)
                        put("meta", m.meta)
                        put("timestamp", m.timestamp)
                        put("planRound", m.planRound ?: JSONObject.NULL)
                    })
                }
            }.toString()

            val tlJson = JSONArray().apply {
                s.timeline.forEach { t ->
                    put(JSONObject().apply {
                        put("kind", t.kind)
                        put("text", t.text)
                        put("timestamp", t.timestamp)
                    })
                }
            }.toString()

            val conv = Conversation(
                id = s.sessionId,
                title = title,
                updatedAt = System.currentTimeMillis(),
                messagesJson = msgsJson,
                timelineJson = tlJson,
                planJson = s.plan?.let(PlanHistoryCodec::encodePlan),
                planRecordsJson = PlanHistoryCodec.encodeRecords(s.planRecords),
                batchesJson = PlanHistoryCodec.encodeBatches(s.batches),
                nodeStatesJson = PlanHistoryCodec.encodeStates(s.nodeStates),
                taskStateJson = s.taskState?.let(PlanHistoryCodec::encodeTaskState),
            )

            _sessions.value = conversationStore.upsert(conv, revision)
        } catch (e: Exception) {
            com.robonix.client.AppLog.write("CONV", "persist failed: ${e.message}", e)
        }
    }

    // Called by handlePilotEvent on terminal states
    private fun autoPersistIfTerminal(kind: String) {
        if (kind in listOf("done", "completed", "failed", "cancelled", "canceled", "aborted")) {
            persistCurrentConversation()
        }
    }

    // ---- JSON parsers (restore from ConversationStore) ----

    private fun parseMessages(json: String): List<ChatMessage> {
        try {
            val arr = JSONArray(json)
            return (0 until arr.length()).map { i ->
                val obj = arr.getJSONObject(i)
                val ts = obj.optLong("timestamp", 0L).let { if (it > 0) it else System.currentTimeMillis() }
                ChatMessage(
                    id = obj.optString("id", chatRepository.generateMessageId()),
                    role = try { MessageRole.valueOf(obj.getString("role")) } catch (_: Exception) { MessageRole.Agent },
                    text = obj.optString("text", ""),
                    meta = obj.optString("meta", ""),
                    timestamp = ts,
                    planRound = if (obj.isNull("planRound")) null else obj.optInt("planRound"),
                )
            }
        } catch (_: Exception) { return emptyList() }
    }

    private fun parseTimeline(json: String): List<TimelineEvent> {
        try {
            val arr = JSONArray(json)
            return (0 until arr.length()).map { i ->
                val obj = arr.getJSONObject(i)
                TimelineEvent(
                    kind = obj.optString("kind", ""),
                    text = obj.optString("text", ""),
                    timestamp = obj.optString("timestamp", ""),
                )
            }
        } catch (_: Exception) { return emptyList() }
    }

    private fun restoredConversation(target: Conversation): ChatUiState = ChatUiState(
        settings = _uiState.value.settings,
        sessionId = target.id,
        sessionTitle = target.title,
        messages = parseMessages(target.messagesJson),
        timeline = parseTimeline(target.timelineJson),
        plan = PlanHistoryCodec.decodePlan(target.planJson),
        planRecords = PlanHistoryCodec.decodeRecords(target.planRecordsJson),
        batches = PlanHistoryCodec.decodeBatches(target.batchesJson),
        nodeStates = PlanHistoryCodec.decodeStates(target.nodeStatesJson),
        taskState = PlanHistoryCodec.decodeTaskState(target.taskStateJson),
    )

    private fun handlePilotEvent(event: PilotEvent) {
        when (event.kind) {
            "text_chunk" -> if (event.textChunk.isNotBlank()) appendAgentText(event.textChunk)
            "final_text" -> if (event.finalText.isNotBlank()) finalizeAgentText(event.finalText)
            "plan" -> event.plan?.let { plan ->
                _uiState.update { state ->
                    state.copy(plan = plan, planRecords = upsertPlanRecord(state.planRecords, plan))
                }
                pushRtdlToShared()
                announcePlan(plan)
                addTimeline(createTimelineEvent("plan",
                    l("tl.plan.round", plan.round, plan.nodes.count { it.call != null })))
            }
            "batch_result" -> event.batchResult?.let { batch ->
                _uiState.update { state ->
                    val key = "${batch.planId}:${batch.round}"
                    val hasRecord = state.planRecords.any { it.key == key || (batch.planId.isNotBlank() && it.plan.planId == batch.planId) }
                    if (!hasRecord && batch.planId.isNotBlank()) {
                        bufferPendingResult(batch.planId) { record ->
                            record.copy(
                                nodeStates = record.nodeStates + batch.results.associateBy { it.nodeIndex },
                                batches = listOf(batch) + record.batches,
                                updatedAt = System.currentTimeMillis(),
                            )
                        }
                    }
                    val records = state.planRecords.map { record ->
                        if (record.key != key && record.plan.planId != batch.planId) record else record.copy(
                            nodeStates = record.nodeStates + batch.results.associateBy { it.nodeIndex },
                            batches = listOf(batch) + record.batches,
                            updatedAt = System.currentTimeMillis(),
                        )
                    }
                    // Live view always follows the stream, as before history was recorded.
                    state.copy(batches = listOf(batch) + state.batches,
                        nodeStates = state.nodeStates + batch.results.associateBy { it.nodeIndex }, planRecords = records)
                }
                pushRtdlToShared()
                addTimeline(createTimelineEvent(
                    if (batch.anyFailed) "error" else "result", l("tl.round.result", batch.round)))
            }
            "node_state" -> event.nodeState?.let { ns ->
                _uiState.update { state ->
                    val hasRecord = state.planRecords.any {
                        (ns.planId.isBlank() && it.plan == state.plan || ns.planId.isNotBlank() && it.plan.planId == ns.planId)
                    }
                    if (!hasRecord && ns.planId.isNotBlank()) {
                        bufferPendingResult(ns.planId) { record ->
                            record.copy(
                                nodeStates = record.nodeStates + (ns.nodeIndex to ns),
                                updatedAt = System.currentTimeMillis(),
                            )
                        }
                    }
                    val record = state.planRecords.firstOrNull {
                        (ns.planId.isBlank() && it.plan == state.plan || ns.planId.isNotBlank() && it.plan.planId == ns.planId) &&
                            it.plan.nodes.any { node -> node.index == ns.nodeIndex && (ns.opId.isBlank() || node.opId == ns.opId) }
                    }
                    val records = if (record != null) state.planRecords.map {
                        if (it.key == record.key) it.copy(nodeStates = it.nodeStates + (ns.nodeIndex to ns),
                            updatedAt = System.currentTimeMillis()) else it
                    } else state.planRecords
                    state.copy(nodeStates = state.nodeStates + (ns.nodeIndex to ns), planRecords = records)
                }
                pushRtdlToShared()
                addTimeline(createTimelineEvent(
                    if (ns.state == "FAILED") "error" else "status",
                    "${ns.opId.ifBlank { l("tl.node", ns.nodeIndex) }} ${AppStrings.formatStatus(_uiState.value.settings.language, ns.state)}"))
            }
            "task_state" -> event.taskState?.let { ts ->
                val st = ts.status.lowercase()
                val isRunning = st in listOf("in_progress", "running", "planning", "executing")
                _uiState.update { state ->
                    state.copy(
                        taskState = ts,
                        isTaskRunning = isRunning,
                        isBusy = st !in listOf("done", "completed", "failed", "cancelled", "canceled", "aborted"),
                        activePilotSessionId = if (isRunning) event.sessionId.ifBlank { state.activePilotSessionId } else state.activePilotSessionId,
                    )
                }
                pushRtdlToShared()
                addTimeline(createTimelineEvent("status", AppStrings.formatStatus(_uiState.value.settings.language, ts.status)))
                persistCurrentConversation()
            }
            "status" -> event.status?.message?.let { msg ->
                val m = Regex("^turn_id=(.+)$").find(msg)
                if (m != null) {
                    _uiState.update {
                        it.copy(
                            activeTurnId = m.groupValues[1],
                            activePilotSessionId = event.sessionId.ifBlank { it.activePilotSessionId },
                        )
                    }
                } else if (event.status.state in listOf(1, 2)) {
                    _uiState.update { it.copy(activeTurnId = "", activePilotSessionId = "", isTaskRunning = false) }
                }
                addTimeline(createTimelineEvent("status", msg))
            }
            "error" -> addMessage(ChatMessage(
                id = chatRepository.generateMessageId(),
                role = MessageRole.Error, text = event.textChunk,
            ))
        }
    }

    private fun handleVoiceEvent(event: VoiceEvent, isSteer: Boolean) {
        com.robonix.client.AppLog.write("VOICE", "handleVoiceEvent: kind=${event.kind} text=${event.text.take(60)} err=${event.error}")
        when (event.kind) {
            "asr_final" -> {
                addMessage(ChatMessage(
                    id = chatRepository.generateMessageId(),
                    role = MessageRole.User, text = event.text,
                    meta = if (isSteer) "voice steer" else "voice",
                ))
                _uiState.update { it.copy(voiceActive = false, isRecording = false) }
            }
            "asr_partial" -> {
                // Show partial ASR result in timeline
                addTimeline(createTimelineEvent("voice", l("tl.partial", event.text)))
            }
            "pilot" -> {
                com.robonix.client.AppLog.write("VOICE", "Voice pilot event received")
                event.pilot?.let { handlePilotEvent(it) }
            }
            "tts_started" -> {
                _uiState.update { it.copy(ttsPlaying = true) }
                addMessage(ChatMessage(
                    id = chatRepository.generateMessageId(),
                    role = MessageRole.Status, text = l("chat.tts.started"),
                ))
                addTimeline(createTimelineEvent("voice", l("tl.tts.started")))
            }
            "tts_done" -> {
                _uiState.update { it.copy(ttsPlaying = false) }
                addMessage(ChatMessage(
                    id = chatRepository.generateMessageId(),
                    role = MessageRole.Status, text = l("chat.tts.done"),
                ))
                addTimeline(createTimelineEvent("voice", l("tl.tts.done")))
            }
            "recording_started" -> {
                recordingConfirmed = true
                addTimeline(createTimelineEvent("voice", l("tl.recording.started")))
            }
            "recording_done" -> {
                finishInFlight = false
                recordingConfirmed = false
                _uiState.update { it.copy(isRecording = false) }
                addTimeline(createTimelineEvent("voice", l("tl.recording.done")))
            }
            "session_started" -> addTimeline(createTimelineEvent("voice", l("tl.session.started")))
            "session_done" -> {
                finishInFlight = false
                recordingConfirmed = false
                addTimeline(createTimelineEvent("voice", l("tl.session.done")))
                // The robot ends the voice session when the turn is over, so any
                // task_state "in_progress" seen earlier is stale: release the
                // busy/steer affordance here, otherwise the composer stays in
                // "干预当前任务 / 正在处理" forever after a voice turn.
                _uiState.update {
                    it.copy(
                        voiceActive = false, isRecording = false, ttsPlaying = false,
                        isBusy = false, isTaskRunning = false,
                        activeTurnId = "", activePilotSessionId = "",
                    )
                }
            }
            "error" -> {
                finishInFlight = false
                recordingConfirmed = false
                addMessage(ChatMessage(
                    id = chatRepository.generateMessageId(),
                    role = MessageRole.Error, text = event.error,
                ))
                _uiState.update { it.copy(ttsPlaying = false) }
            }
            else -> addTimeline(createTimelineEvent("voice", event.statusMessage.ifBlank { event.kind }))
        }
    }

    /**
     * Insert a chat message announcing which capabilities a plan round will
     * call — matches the web client's announcePlan (deduped against the
     * previous status message so re-broadcasts of the same round don't spam).
     */
    private fun announcePlan(plan: RtdlPlan) {
        val s = _uiState.value
        val last = s.messages.lastOrNull()
        if (last != null && last.role == MessageRole.Status && last.planRound == plan.round) return

        val calls = plan.nodes.mapNotNull { it.call?.name }.distinct()
        val text = if (calls.isEmpty()) {
            l("chat.plan.round", plan.round)
        } else {
            val preview = calls.take(3).joinToString(", ")
            val suffix = if (calls.size > 3) " ${l("chat.plan.more", calls.size - 3)}" else ""
            l("chat.plan.calls", preview + suffix)
        }
        addMessage(ChatMessage(
            id = chatRepository.generateMessageId(),
            role = MessageRole.Status,
            text = text,
            meta = "RTDL",
            planRound = plan.round,
        ))
    }

    private fun addTimeline(event: TimelineEvent) {
        _uiState.update { state ->
            state.copy(
                timeline = listOf(event) + state.timeline.take(79),
                // Surface the newest event as a live "what is happening right now"
                // status while a turn is in flight; cleared when the turn ends.
                liveStatus = if (state.isBusy) event.text else state.liveStatus,
            )
        }
    }

    private fun pushRtdlToShared() {
        val s = _uiState.value
        rtdlState.update(s.plan, s.nodeStates, s.batches, s.planRecords, s.taskState)
    }

    private fun hasActiveTurn(state: ChatUiState): Boolean =
        state.activeTurnId.isNotBlank() || state.isTaskRunning

    private fun bufferPendingResult(planId: String, update: (PlanRecord) -> PlanRecord) {
        val list = pendingPlanResults.getOrPut(planId) { mutableListOf() }
        list.add(update)
        if (list.size > 256) list.removeAt(0)
    }

    private fun upsertPlanRecord(records: List<PlanRecord>, plan: RtdlPlan): List<PlanRecord> {
        val key = "${plan.planId}:${plan.round}"
        val existing = records.find { it.key == key }
        val baseRecord = existing ?: PlanRecord(key = key, plan = plan)
        // Flush any buffered node/batch updates that arrived before this plan
        val pending = pendingPlanResults.remove(plan.planId) ?: emptyList()
        var updated = baseRecord.copy(plan = plan, updatedAt = System.currentTimeMillis())
        for (fn in pending) {
            updated = fn(updated)
        }
        return if (existing != null) {
            records.map { if (it.key == key) updated else it }
        } else {
            listOf(updated) + records.take(79)
        }
    }

    private fun mergeFinalText(current: String, final: String): String {
        if (current.isEmpty()) return final
        if (final.isEmpty()) return current
        if (final.contains(current)) return final
        return if (current.contains(final)) current
        else "$current${if (current.endsWith("\n")) "" else "\n"}$final"
    }
}
