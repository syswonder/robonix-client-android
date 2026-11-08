package com.robonix.client.ui.chat

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.AccountTree
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
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
import kotlin.math.max

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
        _uiState.update { it.copy(selectedTaskId = taskId, selectedRecordIndex = 0) }
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
    var showTaskSheet by remember { mutableStateOf(false) }
    var showRoundSheet by remember { mutableStateOf(false) }

    val currentIndex = if (filteredRecords.isNotEmpty()) {
        state.selectedRecordIndex.coerceIn(0, filteredRecords.size - 1)
    } else 0

    val selectedRecord = filteredRecords.getOrNull(currentIndex)
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

    val tasks = state.taskState?.tasks ?: emptyList()
    val currentTask = tasks.find { it.taskId == state.selectedTaskId }
        ?: tasks.lastOrNull()

    Column(modifier = modifier) {
        // Plan A: Unified Control Header (Task Dropdown + Round Stepper + Metrics)
        RtdlControlHeader(
            tasks = tasks,
            currentTask = currentTask,
            overallGoal = state.taskState?.goal,
            filteredRecords = filteredRecords,
            selectedRecordIndex = currentIndex,
            isRefreshing = isRefreshing,
            plan = plan,
            nodeStates = nodeStates,
            onOpenTaskSheet = { showTaskSheet = true },
            onOpenRoundSheet = { showRoundSheet = true },
            onSelectRecord = onSelectRecord,
            onRefresh = onRefresh,
        )

        Spacer(Modifier.height(8.dp))

        // Dynamic Interactive Canvas (Edge-to-edge, serial status-colored pipeline)
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

    // Modal Task Picker Sheet
    if (showTaskSheet && tasks.isNotEmpty()) {
        TaskPickerSheet(
            tasks = tasks,
            selectedTaskId = state.selectedTaskId ?: tasks.lastOrNull()?.taskId,
            onSelectTask = onSelectTask,
            onDismiss = { showTaskSheet = false },
        )
    }

    // Modal Round Picker Sheet
    if (showRoundSheet && filteredRecords.isNotEmpty()) {
        RoundPickerSheet(
            records = filteredRecords,
            selectedRecordIndex = currentIndex,
            onSelectRecord = onSelectRecord,
            onDismiss = { showRoundSheet = false },
        )
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

/**
 * Plan A: Unified Cockpit Control Header
 * Line 1: Task Dropdown Pill + Node Progress + Refresh
 * Line 2: Playback Stepper (Prev < | Round ▾ | Next >) + Live/Latest Tag
 * Bottom: Hairline Progress Line
 */
@Composable
private fun RtdlControlHeader(
    tasks: List<TaskSummary>,
    currentTask: TaskSummary?,
    overallGoal: String? = null,
    filteredRecords: List<PlanRecord>,
    selectedRecordIndex: Int,
    isRefreshing: Boolean,
    plan: RtdlPlan,
    nodeStates: Map<Int, RtdlNodeState>,
    onOpenTaskSheet: () -> Unit,
    onOpenRoundSheet: () -> Unit,
    onSelectRecord: (Int) -> Unit,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val isTaskRunning = currentTask?.status?.lowercase() in listOf("running", "active", "executing")
    val isTaskFailed = currentTask?.status?.lowercase() in listOf("failed", "error")

    val aggregated = remember(plan, nodeStates) { TreeLayoutEngine.aggregateNodeStatuses(plan, nodeStates) }
    fun statusOf(index: Int) = nodeStates[index]?.state ?: aggregated[index]
    val total = plan.nodes.size
    val succeeded = plan.nodes.count { statusOf(it.index) == "SUCCEEDED" }
    val running = plan.nodes.count { statusOf(it.index) in listOf("RUNNING", "VERIFYING") }
    val failed = plan.nodes.count { statusOf(it.index) in listOf("FAILED", "ERROR") }

    CyberCard(
        modifier = modifier.fillMaxWidth(),
        borderColor = if (isTaskRunning) Amber.copy(alpha = 0.45f) else LineSoft,
    ) {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp)) {
            // Line 1: Task Selector Pill (Left) + Stats & Refresh (Right)
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                // Task Dropdown Pill
                Surface(
                    color = if (isTaskRunning) Amber.copy(alpha = 0.14f) else Panel2,
                    shape = RoundedCornerShape(12.dp),
                    border = BorderStroke(
                        width = 1.dp,
                        color = if (isTaskRunning) Amber.copy(alpha = 0.5f) else LineSoft,
                    ),
                    modifier = Modifier
                        .weight(1f, fill = false)
                        .clip(RoundedCornerShape(12.dp))
                        .clickable(enabled = tasks.size > 1) { onOpenTaskSheet() },
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        PulsingStatusDot(
                            color = if (isTaskRunning) Amber else if (isTaskFailed) Red else Green,
                            size = 6.dp,
                        )
                        Spacer(Modifier.width(6.dp))
                        val taskTitle = currentTask?.goal?.take(22)?.ifBlank { null }
                            ?: overallGoal?.take(22)?.ifBlank { null }
                            ?: if (tasks.isNotEmpty()) "任务 #${tasks.indexOf(currentTask) + 1}" else "当前任务"
                        Text(
                            text = taskTitle,
                            color = Text,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (tasks.size > 1) {
                            Spacer(Modifier.width(4.dp))
                            Icon(
                                imageVector = Icons.Default.ArrowDropDown,
                                contentDescription = "Select Task",
                                tint = Cyan,
                                modifier = Modifier.size(16.dp),
                            )
                        }
                    }
                }

                Spacer(Modifier.width(8.dp))

                // Stats & Refresh
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    // Mini node progress tag
                    Surface(
                        color = Panel2,
                        shape = RoundedCornerShape(8.dp),
                        border = BorderStroke(0.6.dp, LineSoft),
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            val dotColor = when {
                                failed > 0 -> Red
                                running > 0 -> Amber
                                else -> Green
                            }
                            Box(
                                modifier = Modifier
                                    .size(5.dp)
                                    .clip(CircleShape)
                                    .background(dotColor),
                            )
                            Spacer(Modifier.width(4.dp))
                            Text(
                                text = "$succeeded/$total",
                                color = Text,
                                fontSize = 11.sp,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold,
                            )
                        }
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

            // Line 2: Playback Stepper (Prev < | Round ▾ | Next >) + Live Button
            val totalRounds = filteredRecords.size.coerceAtLeast(1)
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                // Stepper Group
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    val canGoPrev = selectedRecordIndex < filteredRecords.size - 1
                    IconButton(
                        onClick = { if (canGoPrev) onSelectRecord(selectedRecordIndex + 1) },
                        enabled = canGoPrev,
                        modifier = Modifier.size(28.dp),
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                            contentDescription = "Previous Round",
                            tint = if (canGoPrev) Cyan else Dim.copy(alpha = 0.35f),
                            modifier = Modifier.size(20.dp),
                        )
                    }

                    // Round Dropdown Pill
                    Surface(
                        color = Panel2,
                        shape = RoundedCornerShape(10.dp),
                        border = BorderStroke(0.8.dp, Cyan.copy(alpha = 0.4f)),
                        modifier = Modifier
                            .clip(RoundedCornerShape(10.dp))
                            .clickable(enabled = filteredRecords.size > 1) { onOpenRoundSheet() },
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 9.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = "第 ${plan.round} 轮 / 共 $totalRounds 轮",
                                color = Cyan,
                                fontSize = 11.5.sp,
                                fontWeight = FontWeight.Bold,
                            )
                            if (filteredRecords.size > 1) {
                                Spacer(Modifier.width(4.dp))
                                Icon(
                                    imageVector = Icons.Default.ArrowDropDown,
                                    contentDescription = "Pick Round",
                                    tint = Cyan,
                                    modifier = Modifier.size(14.dp),
                                )
                            }
                        }
                    }

                    val canGoNext = selectedRecordIndex > 0
                    IconButton(
                        onClick = { if (canGoNext) onSelectRecord(selectedRecordIndex - 1) },
                        enabled = canGoNext,
                        modifier = Modifier.size(28.dp),
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                            contentDescription = "Next Round",
                            tint = if (canGoNext) Cyan else Dim.copy(alpha = 0.35f),
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }

                // Live / Return to Latest Pill
                val isLatest = selectedRecordIndex == 0
                Surface(
                    color = if (isLatest) Cyan.copy(alpha = 0.16f) else Panel2,
                    shape = RoundedCornerShape(10.dp),
                    border = BorderStroke(
                        width = 1.dp,
                        color = if (isLatest) Cyan.copy(alpha = 0.5f) else LineSoft,
                    ),
                    modifier = Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .clickable { if (!isLatest) onSelectRecord(0) },
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (isLatest) {
                            PulsingStatusDot(color = Cyan, size = 5.dp)
                            Spacer(Modifier.width(4.dp))
                            Text(
                                text = "实时",
                                color = Cyan,
                                fontSize = 10.5.sp,
                                fontWeight = FontWeight.Bold,
                            )
                        } else {
                            Text(
                                text = "回到最新",
                                color = Amber,
                                fontSize = 10.5.sp,
                                fontWeight = FontWeight.Bold,
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(6.dp))

            // Hairline Progress Line
            val progress = if (total > 0) succeeded.toFloat() / total.toFloat() else 0f
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(2.5.dp)
                    .clip(RoundedCornerShape(1.dp))
                    .background(Panel2),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(fraction = progress.coerceIn(0f, 1f))
                        .fillMaxHeight()
                        .clip(RoundedCornerShape(1.dp))
                        .background(if (failed > 0) Red else if (running > 0) Amber else Cyan),
                )
            }
        }
    }
}

/**
 * Task Selection Bottom Sheet
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TaskPickerSheet(
    tasks: List<TaskSummary>,
    selectedTaskId: String?,
    onSelectTask: (String?) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = Panel,
        scrimColor = Color.Black.copy(alpha = 0.6f),
        dragHandle = {
            Box(
                modifier = Modifier
                    .padding(vertical = 10.dp)
                    .width(36.dp)
                    .height(4.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(Line),
            )
        },
        shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp)
                .padding(bottom = 32.dp),
        ) {
            Text(
                text = "选择执行任务",
                color = Text,
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(vertical = 4.dp),
            )
            Text(
                text = "切换不同任务以查看对应的闭环决策与时序行为树",
                color = Dim,
                fontSize = 11.5.sp,
                modifier = Modifier.padding(bottom = 12.dp),
            )

            Column(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.verticalScroll(rememberScrollState()),
            ) {
                tasks.forEachIndexed { i, task ->
                    val isSelected = when {
                        selectedTaskId != null -> selectedTaskId == task.taskId
                        else -> i == tasks.size - 1
                    }
                    val isRunning = task.status.lowercase() in listOf("running", "active", "executing")
                    val isFailed = task.status.lowercase() in listOf("failed", "error")

                    Surface(
                        color = if (isSelected) Amber.copy(alpha = 0.12f) else Panel2,
                        shape = RoundedCornerShape(12.dp),
                        border = BorderStroke(
                            width = if (isSelected) 1.5.dp else 0.8.dp,
                            color = if (isSelected) Amber else LineSoft,
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                onSelectTask(if (i == tasks.size - 1) null else task.taskId)
                                onDismiss()
                            },
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    PulsingStatusDot(
                                        color = if (isRunning) Amber else if (isFailed) Red else Green,
                                        size = 6.dp,
                                    )
                                    Spacer(Modifier.width(6.dp))
                                    Text(
                                        text = "任务 #${i + 1}",
                                        color = if (isSelected) Amber else Cyan,
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Bold,
                                    )
                                    Spacer(Modifier.width(8.dp))
                                    Text(
                                        text = "共 ${task.planIds.size} 轮计划",
                                        color = Dim,
                                        fontSize = 11.sp,
                                    )
                                }
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    text = task.goal.ifBlank { "未命名目标" },
                                    color = Text,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Medium,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }

                            if (isSelected) {
                                Spacer(Modifier.width(8.dp))
                                Icon(
                                    imageVector = Icons.Default.Check,
                                    contentDescription = null,
                                    tint = Amber,
                                    modifier = Modifier.size(18.dp),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Round Selection Bottom Sheet
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RoundPickerSheet(
    records: List<PlanRecord>,
    selectedRecordIndex: Int,
    onSelectRecord: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = Panel,
        scrimColor = Color.Black.copy(alpha = 0.6f),
        dragHandle = {
            Box(
                modifier = Modifier
                    .padding(vertical = 10.dp)
                    .width(36.dp)
                    .height(4.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(Line),
            )
        },
        shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp)
                .padding(bottom = 32.dp),
        ) {
            Text(
                text = "轮次快速跳转",
                color = Text,
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(vertical = 4.dp),
            )
            Text(
                text = "按时序回溯该任务下的每一轮 RTDL 执行结果",
                color = Dim,
                fontSize = 11.5.sp,
                modifier = Modifier.padding(bottom = 12.dp),
            )

            Column(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.verticalScroll(rememberScrollState()),
            ) {
                records.forEachIndexed { index, record ->
                    val isSelected = index == selectedRecordIndex
                    val isLatest = index == 0
                    val total = record.plan.nodes.size
                    val nodeStatuses = record.nodeStates.values.map { it.state.uppercase() }
                    val hasFailed = nodeStatuses.any { it in listOf("FAILED", "ERROR") }
                    val hasRunning = nodeStatuses.any { it in listOf("RUNNING", "ACTIVE") }
                    val allSucceeded = nodeStatuses.isNotEmpty() && nodeStatuses.all { it in listOf("SUCCEEDED", "SUCCESS") }

                    Surface(
                        color = if (isSelected) Cyan.copy(alpha = 0.14f) else Panel2,
                        shape = RoundedCornerShape(12.dp),
                        border = BorderStroke(
                            width = if (isSelected) 1.5.dp else 0.8.dp,
                            color = if (isSelected) Cyan else LineSoft,
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                onSelectRecord(index)
                                onDismiss()
                            },
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Surface(
                                    color = if (isSelected) Cyan.copy(alpha = 0.2f) else LineSoft,
                                    shape = RoundedCornerShape(6.dp),
                                ) {
                                    Text(
                                        text = "R${record.plan.round}",
                                        color = if (isSelected) Cyan else Text,
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold,
                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp),
                                    )
                                }
                                Spacer(Modifier.width(10.dp))
                                Column {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(
                                            text = "第 ${record.plan.round} 轮",
                                            color = Text,
                                            fontSize = 13.sp,
                                            fontWeight = FontWeight.SemiBold,
                                        )
                                        if (isLatest) {
                                            Spacer(Modifier.width(6.dp))
                                            Surface(
                                                color = Cyan.copy(alpha = 0.16f),
                                                shape = RoundedCornerShape(4.dp),
                                            ) {
                                                Text(
                                                    text = "最新",
                                                    color = Cyan,
                                                    fontSize = 9.sp,
                                                    fontWeight = FontWeight.Bold,
                                                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp),
                                                )
                                            }
                                        }
                                    }
                                    Text(
                                        text = "计划 ID: ${record.plan.planId.take(12)} · $total 个节点",
                                        color = Dim,
                                        fontSize = 10.5.sp,
                                        fontFamily = FontFamily.Monospace,
                                    )
                                }
                            }

                            // Status Tag
                            Surface(
                                color = when {
                                    hasFailed -> Red.copy(alpha = 0.14f)
                                    hasRunning -> Amber.copy(alpha = 0.14f)
                                    allSucceeded -> Green.copy(alpha = 0.14f)
                                    else -> LineSoft.copy(alpha = 0.4f)
                                },
                                shape = RoundedCornerShape(6.dp),
                            ) {
                                Text(
                                    text = when {
                                        hasFailed -> "失败"
                                        hasRunning -> "运行中"
                                        allSucceeded -> "成功"
                                        else -> "完成"
                                    },
                                    color = when {
                                        hasFailed -> Red
                                        hasRunning -> Amber
                                        allSucceeded -> Green
                                        else -> Dim
                                    },
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RtdlErrorBanner(error: String) {
    Surface(
        color = Red.copy(alpha = 0.12f),
        shape = RoundedCornerShape(8.dp),
        border = BorderStroke(0.8.dp, Red.copy(alpha = 0.4f)),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Default.ErrorOutline, null, tint = Red, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(8.dp))
            Text(text = error, color = Red, fontSize = 11.5.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun RtdlEmptyState(
    title: String,
    subtitle: String,
    icon: ImageVector,
) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(32.dp),
        ) {
            Surface(
                color = Panel2,
                shape = CircleShape,
                modifier = Modifier.size(64.dp),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(icon, null, tint = Dim, modifier = Modifier.size(32.dp))
                }
            }
            Spacer(Modifier.height(16.dp))
            Text(text = title, color = Text, fontSize = 15.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(6.dp))
            Text(text = subtitle, color = Dim, fontSize = 12.sp, maxLines = 3)
        }
    }
}
