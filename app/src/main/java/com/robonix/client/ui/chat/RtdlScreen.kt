package com.robonix.client.ui.chat

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountTree
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.robonix.client.data.model.*
import com.robonix.client.domain.RtdlStateHolder
import com.robonix.client.domain.SystemRepository
import com.robonix.client.ui.chat.rtdl.RtdlCanvasView
import com.robonix.client.ui.chat.rtdl.RtdlNodeDetailSheet
import com.robonix.client.ui.chat.rtdl.TreeLayoutEngine
import com.robonix.client.ui.components.CyberCard
import com.robonix.client.ui.components.PulsingStatusDot
import com.robonix.client.ui.i18n.t
import com.robonix.client.ui.i18n.tStatus
import com.robonix.client.ui.navigation.SharedViewModel
import com.robonix.client.ui.theme.*
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.inject.Inject

data class RtdlUiState(
    val planRecords: List<PlanRecord> = emptyList(),
    val isRefreshing: Boolean = false,
    val selectedRecordIndex: Int = 0,
    val livePlan: RtdlPlan? = null,
    val liveNodeStates: Map<Int, RtdlNodeState> = emptyMap(),
    val error: String? = null,
    val taskState: TaskState? = null,
    val selectedTaskId: String? = null,
)

@HiltViewModel
class RtdlViewModel @Inject constructor(
    private val systemRepository: SystemRepository,
    private val rtdlState: RtdlStateHolder,
) : ViewModel() {

    private val _uiState = MutableStateFlow(RtdlUiState())
    val uiState: StateFlow<RtdlUiState> = _uiState.asStateFlow()

    /** Filtered plan records based on selected task's planIds when tasks.size > 1. */
    val filteredPlanRecords: StateFlow<List<PlanRecord>> = _uiState.map { state ->
        val tasks = state.taskState?.tasks ?: emptyList()
        if (tasks.size <= 1) return@map state.planRecords

        val effectiveTaskId = state.selectedTaskId ?: tasks.lastOrNull()?.taskId
        if (effectiveTaskId == null) return@map state.planRecords

        val selectedTask = tasks.find { it.taskId == effectiveTaskId }
        val allowedPlanIds = selectedTask?.planIds?.toSet() ?: emptySet()
        if (allowedPlanIds.isEmpty()) return@map emptyList()

        state.planRecords.filter { it.plan.planId in allowedPlanIds }
    }.stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    private var pollJob: Job? = null

    init {
        viewModelScope.launch {
            rtdlState.livePlan.collect { p -> _uiState.update { it.copy(livePlan = p) } }
        }
        viewModelScope.launch {
            rtdlState.liveNodeStates.collect { ns -> _uiState.update { it.copy(liveNodeStates = ns) } }
        }
        viewModelScope.launch {
            rtdlState.taskState.collect { ts -> _uiState.update { it.copy(taskState = ts) } }
        }
        viewModelScope.launch {
            rtdlState.selectedTaskId.collect { id ->
                _uiState.update { state ->
                    state.copy(selectedTaskId = id, selectedRecordIndex = 0)
                }
            }
        }
        viewModelScope.launch {
            rtdlState.planRecords.collect { recs ->
                _uiState.update {
                    it.copy(
                        planRecords = recs,
                        selectedRecordIndex = if (recs.isNotEmpty()) it.selectedRecordIndex.coerceIn(0, recs.size - 1) else 0,
                    )
                }
            }
        }
    }

    fun startPolling(target: String) {
        pollJob?.cancel()
        if (target.isBlank()) return
        pollJob = viewModelScope.launch {
            while (isActive) {
                delay(10000L)
            }
        }
    }

    fun stopPolling() {
        pollJob?.cancel()
    }

    fun refresh(@Suppress("UNUSED_PARAMETER") target: String) {
        _uiState.update { it.copy(isRefreshing = true) }
        viewModelScope.launch {
            delay(400L)
            _uiState.update { it.copy(isRefreshing = false) }
        }
    }

    fun selectRecordIndex(index: Int) {
        _uiState.update { it.copy(selectedRecordIndex = index) }
    }

    fun selectTask(taskId: String?) {
        rtdlState.selectTask(taskId)
    }
}

@Composable
fun RtdlScreen(
    viewModel: RtdlViewModel = hiltViewModel(),
    sharedViewModel: SharedViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsState()
    val filteredRecords by viewModel.filteredPlanRecords.collectAsState()
    val settings by sharedViewModel.settings.collectAsState()

    LaunchedEffect(settings.atlasEndpoint) {
        if (settings.atlasEndpoint.isNotBlank()) {
            viewModel.startPolling(settings.atlasEndpoint)
        }
    }

    DisposableEffect(Unit) {
        onDispose { viewModel.stopPolling() }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Bg)
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        if (state.error != null) {
            RtdlErrorBanner(error = state.error!!)
            Spacer(Modifier.height(8.dp))
        }

        BehaviorTreeSection(
            state = state,
            filteredRecords = filteredRecords,
            isRefreshing = state.isRefreshing,
            onRefresh = { viewModel.refresh(settings.atlasEndpoint) },
            onSelectRecord = { viewModel.selectRecordIndex(it) },
            onSelectTask = { viewModel.selectTask(it) },
            modifier = Modifier.fillMaxSize(),
        )
    }
}

@Composable
private fun BehaviorTreeSection(
    state: RtdlUiState,
    filteredRecords: List<PlanRecord>,
    isRefreshing: Boolean,
    onRefresh: () -> Unit,
    onSelectRecord: (Int) -> Unit,
    onSelectTask: (String?) -> Unit,
    modifier: Modifier = Modifier,
) {
    var inspectedNodeIndex by remember { mutableStateOf<Int?>(null) }

    val selectedRecord = filteredRecords.getOrNull(state.selectedRecordIndex)
    val plan = selectedRecord?.plan ?: state.livePlan
    val nodeStates = selectedRecord?.nodeStates ?: state.liveNodeStates

    if (plan == null || plan.nodes.isEmpty()) {
        RtdlEmptyState(
            title = t("rtdl.history.empty"),
            subtitle = t("rtdl.history.empty.desc"),
            icon = Icons.Default.AccountTree,
        )
        return
    }

    val layoutResult = remember(plan, nodeStates) {
        TreeLayoutEngine.computeLayout(plan, nodeStates)
    }

    Column(modifier = modifier) {
        // 1. Task Selector Chips (separated per task, shown when multiple tasks exist)
        val tasks = state.taskState?.tasks ?: emptyList()
        if (tasks.size > 1) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .selectableGroup()
                    .padding(bottom = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                tasks.forEachIndexed { i, task ->
                    val isSelected = when {
                        state.selectedTaskId != null -> state.selectedTaskId == task.taskId
                        else -> i == tasks.size - 1 // follow-latest: last task selected by default
                    }
                    val isRunning = task.status.lowercase() in listOf("running", "active", "executing")
                    val isFailed = task.status.lowercase() in listOf("failed", "error")
                    val borderColor = when {
                        isSelected -> Amber
                        isRunning -> Amber.copy(alpha = 0.6f)
                        isFailed -> Red.copy(alpha = 0.6f)
                        else -> LineSoft
                    }
                    Surface(
                        color = if (isSelected) Amber.copy(alpha = 0.18f) else Panel2,
                        shape = RoundedCornerShape(12.dp),
                        border = BorderStroke(1.dp, borderColor),
                        modifier = Modifier.selectable(
                            selected = isSelected,
                            role = Role.RadioButton,
                            onClick = { onSelectTask(if (i == tasks.size - 1) null else task.taskId) },
                        ),
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            if (isRunning) {
                                PulsingStatusDot(color = Amber, size = 5.dp)
                                Spacer(Modifier.width(4.dp))
                            }
                            Text(
                                text = task.goal.take(16).ifBlank { "任务 #${i + 1}" },
                                color = if (isSelected) Amber else Text,
                                fontSize = 11.sp,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
        }

        // 2. Round Selector Chips (each task has multiple rounds)
        if (filteredRecords.size > 1) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .selectableGroup()
                    .padding(bottom = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                filteredRecords.forEachIndexed { i, record ->
                    val isSelected = i == state.selectedRecordIndex
                    Surface(
                        color = if (isSelected) Cyan.copy(alpha = 0.2f) else Panel2,
                        shape = RoundedCornerShape(12.dp),
                        border = BorderStroke(1.dp, if (isSelected) Cyan else LineSoft),
                        modifier = Modifier.selectable(
                            selected = isSelected,
                            role = Role.RadioButton,
                            onClick = { onSelectRecord(i) },
                        ),
                    ) {
                        Text(
                            text = t("rtdl.record", record.plan.round, record.plan.nodes.size),
                            color = if (isSelected) Cyan else Text,
                            fontSize = 11.sp,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                        )
                    }
                }
            }
        }

        // 3. Compact Cyber Summary Card with Refresh button
        BehaviorTreeSummaryCard(
            plan = plan,
            nodeStates = nodeStates,
            isRefreshing = isRefreshing,
            onRefresh = onRefresh,
        )

        Spacer(Modifier.height(8.dp))

        // 4. Dynamic Interactive Canvas (Edge-to-edge, status-colored branches & capsules)
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
        ) {
            RtdlCanvasView(
                plan = plan,
                nodeStates = nodeStates,
                selectedNodeIndex = inspectedNodeIndex,
                onNodeClick = { node ->
                    inspectedNodeIndex = node.node.index
                },
            )
        }
    }

    // Modal Bottom Sheet Detail Inspector
    if (inspectedNodeIndex != null) {
        val selectedLayoutNode = layoutResult.nodes.find { it.node.index == inspectedNodeIndex }
        val nodesMap = remember(layoutResult) { layoutResult.nodes.associateBy { it.node.index } }
        RtdlNodeDetailSheet(
            layoutNode = selectedLayoutNode,
            allNodesByIndex = nodesMap,
            onDismiss = { inspectedNodeIndex = null },
            onSelectNode = { inspectedNodeIndex = it },
        )
    }
}

@Composable
private fun BehaviorTreeSummaryCard(
    plan: RtdlPlan,
    nodeStates: Map<Int, RtdlNodeState>,
    isRefreshing: Boolean,
    onRefresh: () -> Unit,
) {
    val aggregated = remember(plan, nodeStates) { TreeLayoutEngine.aggregateNodeStatuses(plan, nodeStates) }
    fun statusOf(index: Int) = nodeStates[index]?.state ?: aggregated[index]
    val total = plan.nodes.size
    val succeeded = plan.nodes.count { statusOf(it.index) == "SUCCEEDED" }
    val running = plan.nodes.count { statusOf(it.index) in listOf("RUNNING", "VERIFYING") }
    val failed = plan.nodes.count { statusOf(it.index) in listOf("FAILED", "ERROR") }

    CyberCard(
        modifier = Modifier.fillMaxWidth(),
        borderColor = LineSoft,
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    modifier = Modifier.weight(1f, fill = false),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Default.AccountTree, null, tint = Cyan, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = t("rtdl.plan.id", plan.planId.take(16)),
                        color = Text,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    // Round Pill
                    Surface(
                        color = Cyan.copy(alpha = 0.12f),
                        shape = RoundedCornerShape(10.dp),
                        border = BorderStroke(0.8.dp, Cyan.copy(alpha = 0.4f)),
                    ) {
                        Text(
                            text = t("rtdl.round", plan.round),
                            color = Cyan,
                            fontSize = 10.5.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                        )
                    }

                    // Refresh Button
                    IconButton(
                        onClick = onRefresh,
                        modifier = Modifier.size(28.dp),
                        enabled = !isRefreshing,
                    ) {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = t("action.refresh"),
                            tint = if (isRefreshing) Amber else Cyan,
                            modifier = Modifier.size(16.dp),
                        )
                    }
                }
            }

            Spacer(Modifier.height(8.dp))

            // Progress Metrics Bar
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = t("rtdl.nodes.completed", succeeded, total),
                    color = Muted,
                    fontSize = 11.sp,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (running > 0) MetricTag(count = running, label = tStatus("RUNNING"), color = Amber)
                    if (succeeded > 0) MetricTag(count = succeeded, label = tStatus("SUCCEEDED"), color = Green)
                    if (failed > 0) MetricTag(count = failed, label = tStatus("FAILED"), color = Red)
                }
            }

            Spacer(Modifier.height(6.dp))

            // Custom Linear Progress Bar (No BOM crash)
            val progress = if (total > 0) succeeded.toFloat() / total.toFloat() else 0f
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(4.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(Panel2),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(fraction = progress.coerceIn(0f, 1f))
                        .fillMaxHeight()
                        .clip(RoundedCornerShape(2.dp))
                        .background(if (failed > 0) Red else if (running > 0) Amber else Cyan),
                )
            }
        }
    }
}

@Composable
private fun MetricTag(count: Int, label: String, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(6.dp)
                .clip(CircleShape)
                .background(color),
        )
        Spacer(Modifier.width(3.dp))
        Text(
            text = "$count $label",
            color = color,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
        )
    }
}

@Composable
private fun RtdlEmptyState(
    title: String,
    subtitle: String,
    icon: ImageVector,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 64.dp, start = 24.dp, end = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Surface(
            color = Panel2,
            shape = CircleShape,
            border = BorderStroke(1.dp, LineSoft),
            modifier = Modifier.size(56.dp),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = Dim,
                    modifier = Modifier.size(26.dp),
                )
            }
        }
        Spacer(Modifier.height(14.dp))
        Text(
            text = title,
            color = Text,
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = subtitle,
            color = Dim,
            fontSize = 11.5.sp,
            modifier = Modifier.padding(horizontal = 16.dp),
        )
    }
}

@Composable
private fun RtdlErrorBanner(error: String) {
    Surface(
        color = Red.copy(alpha = 0.12f),
        shape = RoundedCornerShape(8.dp),
        border = BorderStroke(1.dp, Red.copy(alpha = 0.3f)),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Default.ErrorOutline, null, tint = Red, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(8.dp))
            Text(
                text = error,
                color = Red,
                fontSize = 11.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
