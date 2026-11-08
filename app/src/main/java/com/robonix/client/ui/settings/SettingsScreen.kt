package com.robonix.client.ui.settings

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.ui.draw.rotate
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import com.robonix.client.data.model.ClientSettings
import com.robonix.client.ui.audio.AudioViewModel
import com.robonix.client.ui.audio.Dropdown
import com.robonix.client.ui.audio.TestResultClass
import com.robonix.client.ui.audio.VuMeter
import com.robonix.client.ui.components.PulsingStatusDot
import com.robonix.client.ui.i18n.AppStrings
import com.robonix.client.ui.i18n.t
import com.robonix.client.ui.i18n.tStatus
import com.robonix.client.ui.navigation.SharedViewModel
import com.robonix.client.ui.theme.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private val languageOptions = listOf(
    AppStrings.SYSTEM to "settings.language.system",
    AppStrings.ZH to "settings.language.zh",
    AppStrings.EN to "settings.language.en",
)

enum class SettingsTab(val labelKey: String, val icon: ImageVector) {
    NETWORK("settings.tab.network", Icons.Default.Language),
    AUDIO("settings.tab.audio", Icons.Default.GraphicEq),
    SYSTEM("settings.tab.system", Icons.Default.Dns),
}

@Composable
fun SettingsScreen(
    sharedViewModel: SharedViewModel = hiltViewModel(),
    audioViewModel: AudioViewModel = hiltViewModel(),
) {
    val settings by sharedViewModel.settings.collectAsState()
    val connectionState by sharedViewModel.connectionState.collectAsState()
    val snapshot by sharedViewModel.systemSnapshot.collectAsState()

    val audioState by audioViewModel.uiState.collectAsState()
    val handsfreeStatus by audioViewModel.handsfree.status.collectAsState()
    val handsfreeBusy by audioViewModel.handsfree.busy.collectAsState()
    val handsfreeError by audioViewModel.handsfree.error.collectAsState()
    val voiceBusy by audioViewModel.handsfree.voiceBusy.collectAsState()

    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var selectedTab by rememberSaveable { mutableStateOf(SettingsTab.NETWORK) }
    var saveFeedback by remember { mutableStateOf<String?>(null) }

    // Sync language to audio VM
    LaunchedEffect(settings.language) {
        audioViewModel.setLanguage(settings.language)
    }

    // Refresh audio route when endpoint or preferred devices change
    LaunchedEffect(settings.atlasEndpoint) {
        audioViewModel.refreshAudioRoute(
            settings.atlasEndpoint,
            settings.micNodeId,
            settings.speakerNodeId,
            settings.micDeviceId,
            settings.speakerDeviceId
        )
    }

    // Manage hands-free polling while settings is active
    LaunchedEffect(settings.atlasEndpoint) {
        audioViewModel.handsfree.stopPolling()
        audioViewModel.handsfree.startPolling(settings.atlasEndpoint)
    }
    DisposableEffect(Unit) {
        onDispose { audioViewModel.handsfree.stopPolling() }
    }

    // Permissions for audio testing and voiceprint
    val permLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) audioViewModel.testMicrophone()
    }
    val enrollPermLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) audioViewModel.enrollVoiceprint(sharedViewModel.settings.value)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Bg),
    ) {
        // Top Segmented Pill Bar
        SettingsSegmentedTabs(
            selected = selectedTab,
            onSelect = { selectedTab = it },
        )

        // Tab Content with crossfade animation
        AnimatedContent(
            targetState = selectedTab,
            transitionSpec = {
                fadeIn(animationSpec = tween(200)) togetherWith
                    fadeOut(animationSpec = tween(150))
            },
            label = "tab_switch",
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
        ) { tab ->
            when (tab) {
                SettingsTab.NETWORK -> {
                    NetworkSettingsTab(
                        settings = settings,
                        connectionState = connectionState,
                        onUpdateSettings = { sharedViewModel.updateSettings(it) },
                        onSave = {
                            sharedViewModel.saveSettings()
                            saveFeedback = AppStrings.format(settings.language, "msg.settings.saved")
                            scope.launch {
                                delay(2000)
                                saveFeedback = null
                            }
                        },
                        onConnect = { sharedViewModel.connect() },
                        saveFeedback = saveFeedback,
                    )
                }
                SettingsTab.AUDIO -> {
                    AudioSettingsTab(
                        settings = settings,
                        audioState = audioState,
                        handsfreeStatus = handsfreeStatus,
                        handsfreeBusy = handsfreeBusy,
                        handsfreeError = handsfreeError,
                        voiceBusy = voiceBusy,
                        onToggleHandsfree = { audioViewModel.toggleHandsfree(sharedViewModel.settings.value) },
                        onSelectMicProvider = { id ->
                            audioViewModel.selectMicProvider(settings.atlasEndpoint, id)
                            sharedViewModel.updateSettings { it.copy(micNodeId = id) }
                        },
                        onSelectMicDevice = { id ->
                            audioViewModel.selectMicDevice(id)
                            sharedViewModel.updateSettings { it.copy(micDeviceId = id) }
                        },
                        onSelectSpeakerProvider = { id ->
                            audioViewModel.selectSpeakerProvider(settings.atlasEndpoint, id)
                            sharedViewModel.updateSettings { it.copy(speakerNodeId = id) }
                        },
                        onSelectSpeakerDevice = { id ->
                            audioViewModel.selectSpeakerDevice(id)
                            sharedViewModel.updateSettings { it.copy(speakerDeviceId = id) }
                        },
                        onRefreshRoute = {
                            audioViewModel.refreshAudioRoute(
                                settings.atlasEndpoint, settings.micNodeId, settings.speakerNodeId,
                                settings.micDeviceId, settings.speakerDeviceId
                            )
                        },
                        onApplyRoute = {
                            sharedViewModel.saveSettings()
                            audioViewModel.applyRoute(sharedViewModel.settings.value)
                        },
                        onTestMic = {
                            if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                                PackageManager.PERMISSION_GRANTED
                            ) {
                                audioViewModel.testMicrophone()
                            } else {
                                permLauncher.launch(Manifest.permission.RECORD_AUDIO)
                            }
                        },
                        onTestSpeaker = { audioViewModel.testSpeaker() },
                        onStartVuRecording = { audioViewModel.startRecording() },
                        onStopVuRecording = { audioViewModel.stopRecording() },
                        onUpdateEnrollUserId = { audioViewModel.updateEnrollUserId(it) },
                        onEnrollVoiceprint = {
                            if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                                PackageManager.PERMISSION_GRANTED
                            ) {
                                audioViewModel.enrollVoiceprint(sharedViewModel.settings.value)
                            } else {
                                enrollPermLauncher.launch(Manifest.permission.RECORD_AUDIO)
                            }
                        },
                    )
                }
                SettingsTab.SYSTEM -> {
                    SystemSettingsTab(
                        snapshot = snapshot,
                        onRefresh = { sharedViewModel.refreshSystem() },
                    )
                }
            }
        }
    }
}

@Composable
private fun SettingsSegmentedTabs(
    selected: SettingsTab,
    onSelect: (SettingsTab) -> Unit,
) {
    Surface(
        color = Panel,
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, Line),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(3.dp),
            horizontalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            SettingsTab.values().forEach { tab ->
                val isSelected = tab == selected
                val animatedAlpha by animateFloatAsState(
                    targetValue = if (isSelected) 1f else 0f,
                    animationSpec = tween(200),
                    label = "tab_alpha",
                )
                Surface(
                    onClick = { onSelect(tab) },
                    color = Cyan.copy(alpha = 0.12f * animatedAlpha),
                    shape = RoundedCornerShape(10.dp),
                    border = if (isSelected) BorderStroke(1.dp, Cyan.copy(alpha = 0.4f)) else null,
                    modifier = Modifier.weight(1f),
                ) {
                    Row(
                        modifier = Modifier.padding(vertical = 10.dp),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            tab.icon,
                            contentDescription = null,
                            tint = if (isSelected) Cyan else Muted,
                            modifier = Modifier.size(16.dp),
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = t(tab.labelKey),
                            color = if (isSelected) Cyan else Muted,
                            fontSize = 12.sp,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}

// =================================================================
// Reusable Section Components - premium list-style settings rows
// =================================================================

/** Section header label like iOS Settings */
@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text.uppercase(),
        color = Dim,
        fontSize = 11.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = 0.8.sp,
        modifier = Modifier.padding(start = 4.dp, bottom = 4.dp, top = 8.dp),
    )
}

/** A premium card container for grouped settings */
@Composable
private fun SettingsGroup(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(
        color = Panel,
        shape = RoundedCornerShape(14.dp),
        border = BorderStroke(1.dp, Line),
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(content = content)
    }
}

/** A single tappable settings row with icon, label, description, and trailing content */
@Composable
private fun SettingRow(
    icon: ImageVector,
    iconTint: Color = Cyan,
    label: String,
    description: String? = null,
    onClick: (() -> Unit)? = null,
    showDivider: Boolean = true,
    trailing: @Composable (RowScope.() -> Unit)? = null,
) {
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Icon circle
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(iconTint.copy(alpha = 0.12f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(icon, null, tint = iconTint, modifier = Modifier.size(17.dp))
            }
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(label, color = Text, fontSize = 14.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (description != null) {
                    Text(description, color = Dim, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            if (trailing != null) {
                Spacer(Modifier.width(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically, content = trailing)
            }
        }
        if (showDivider) {
            HorizontalDivider(
                color = Line.copy(alpha = 0.5f),
                thickness = 0.5.dp,
                modifier = Modifier.padding(start = 58.dp, end = 14.dp),
            )
        }
    }
}

/** Compact inline text field - no fixed height, let Material3 size itself properly */
@Composable
private fun CompactTextField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    icon: ImageVector,
    modifier: Modifier = Modifier,
    label: String? = null,
    keyboardType: KeyboardType = KeyboardType.Text,
    imeAction: ImeAction = ImeAction.Next,
    onImeAction: (() -> Unit)? = null,
) {
    val focusManager = LocalFocusManager.current
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = if (label != null) {{ Text(label, fontSize = 11.sp) }} else null,
        placeholder = { Text(placeholder, color = Dim, fontSize = 13.sp) },
        leadingIcon = { Icon(icon, null, tint = Muted, modifier = Modifier.size(16.dp)) },
        modifier = modifier.fillMaxWidth(),
        colors = OutlinedTextFieldDefaults.colors(
            focusedTextColor = Text,
            unfocusedTextColor = Text,
            focusedLabelColor = Cyan,
            unfocusedLabelColor = Muted,
            focusedBorderColor = Cyan.copy(alpha = 0.6f),
            unfocusedBorderColor = Line,
            cursorColor = Cyan,
            focusedContainerColor = Panel2,
            unfocusedContainerColor = Panel2,
            focusedLeadingIconColor = Cyan,
            unfocusedLeadingIconColor = Muted,
        ),
        shape = RoundedCornerShape(10.dp),
        singleLine = true,
        textStyle = LocalTextStyle.current.copy(fontSize = 14.sp),
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType, imeAction = imeAction),
        keyboardActions = KeyboardActions(
            onDone = { onImeAction?.invoke() ?: focusManager.clearFocus() },
            onNext = { onImeAction?.invoke() },
        ),
    )
}

/** Small pill-shaped action button */
@Composable
private fun PillButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    color: Color = Cyan,
    enabled: Boolean = true,
    filled: Boolean = true,
) {
    if (filled) {
        Button(
            onClick = onClick,
            enabled = enabled,
            colors = ButtonDefaults.buttonColors(
                containerColor = color,
                contentColor = Bg,
                disabledContainerColor = color.copy(alpha = 0.3f),
                disabledContentColor = Bg.copy(alpha = 0.5f),
            ),
            shape = RoundedCornerShape(10.dp),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            modifier = modifier,
        ) {
            if (icon != null) {
                Icon(icon, null, modifier = Modifier.size(15.dp))
                Spacer(Modifier.width(6.dp))
            }
            Text(text, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        }
    } else {
        OutlinedButton(
            onClick = onClick,
            enabled = enabled,
            border = BorderStroke(1.dp, if (enabled) color.copy(alpha = 0.5f) else Line),
            shape = RoundedCornerShape(10.dp),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            modifier = modifier,
        ) {
            if (icon != null) {
                Icon(icon, null, tint = if (enabled) color else Dim, modifier = Modifier.size(15.dp))
                Spacer(Modifier.width(6.dp))
            }
            Text(text, color = if (enabled) color else Dim, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        }
    }
}

// =================================================================
// TAB 1: Network & General
// =================================================================
@Composable
private fun NetworkSettingsTab(
    settings: ClientSettings,
    connectionState: com.robonix.client.ui.navigation.ConnectionState,
    onUpdateSettings: ((ClientSettings) -> ClientSettings) -> Unit,
    onSave: () -> Unit,
    onConnect: () -> Unit,
    saveFeedback: String?,
) {
    var portText by remember(settings.atlasPort) { mutableStateOf(settings.atlasPort.toString()) }
    var showLangDialog by remember { mutableStateOf(false) }
    var showDevLogsDialog by remember { mutableStateOf(false) }
    val currentLangLabel = languageOptions
        .firstOrNull { it.first == settings.language }?.second
        ?: "settings.language.system"

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        contentPadding = PaddingValues(bottom = 40.dp),
    ) {
        // ── Connection Status Banner ──
        item {
            val isOnline = connectionState.isOnline
            val isConnecting = connectionState.isConnecting
            val statusColor = when {
                isOnline -> Green
                isConnecting -> Amber
                else -> Red
            }
            Surface(
                color = statusColor.copy(alpha = 0.08f),
                shape = RoundedCornerShape(14.dp),
                border = BorderStroke(1.dp, statusColor.copy(alpha = 0.25f)),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (isOnline) {
                        PulsingStatusDot(color = Green, size = 10.dp)
                    } else {
                        Box(
                            modifier = Modifier
                                .size(10.dp)
                                .clip(CircleShape)
                                .background(statusColor),
                        )
                    }
                    Spacer(Modifier.width(10.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            when {
                                isConnecting -> tStatus("CONNECTING")
                                isOnline -> tStatus("ONLINE")
                                else -> tStatus("OFFLINE")
                            },
                            color = statusColor,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                        )
                        if (settings.atlasEndpoint.isNotBlank()) {
                            Text(
                                settings.atlasEndpoint,
                                color = statusColor.copy(alpha = 0.7f),
                                fontSize = 10.sp,
                                fontFamily = FontFamily.Monospace,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                    PillButton(
                        text = if (isOnline) t("settings.btn.retest") else t("settings.btn.connect"),
                        icon = if (isOnline) Icons.Default.Refresh else Icons.Default.PlayArrow,
                        color = statusColor,
                        onClick = onConnect,
                    )
                }
            }
        }

        // ── Server Configuration ──
        item {
            SectionLabel(t("settings.connection"))
            SettingsGroup {
                Column(modifier = Modifier.padding(14.dp)) {
                    // Host + Port on one row
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.Top,
                    ) {
                        CompactTextField(
                            value = settings.robotHost,
                            onValueChange = { raw ->
                                val v = raw.trim()
                                if (v.contains("://")) {
                                    val clean = v.substringAfter("://").trim().trimEnd('/')
                                    if (":" in clean) {
                                        val h = clean.substringBefore(":")
                                        val p = clean.substringAfter(":").takeWhile { it.isDigit() }.toIntOrNull()
                                        onUpdateSettings { s -> s.copy(robotHost = h, atlasPort = p ?: s.atlasPort) }
                                        if (p != null) portText = p.toString()
                                    } else {
                                        onUpdateSettings { s -> s.copy(robotHost = clean) }
                                    }
                                } else if (":" in v && !v.startsWith(":")) {
                                    val h = v.substringBefore(":")
                                    val p = v.substringAfter(":").takeWhile { it.isDigit() }.toIntOrNull()
                                    onUpdateSettings { s -> s.copy(robotHost = h, atlasPort = p ?: s.atlasPort) }
                                    if (p != null) portText = p.toString()
                                } else {
                                    onUpdateSettings { s -> s.copy(robotHost = v) }
                                }
                            },
                            placeholder = t("settings.host.placeholder"),
                            label = t("settings.host"),
                            icon = Icons.Default.Computer,
                            modifier = Modifier.weight(1f),
                        )
                        CompactTextField(
                            value = portText,
                            onValueChange = {
                                portText = it
                                val port = it.toIntOrNull()
                                if (port != null && port in 1..65535) {
                                    onUpdateSettings { s -> s.copy(atlasPort = port) }
                                }
                            },
                            placeholder = "50051",
                            label = t("settings.port"),
                            icon = Icons.Default.Tag,
                            keyboardType = KeyboardType.Number,
                            modifier = Modifier.width(120.dp),
                        )
                    }

                    Spacer(Modifier.height(8.dp))

                    // Liaison Endpoint
                    CompactTextField(
                        value = settings.liaisonEndpoint,
                        onValueChange = { v -> onUpdateSettings { s -> s.copy(liaisonEndpoint = v) } },
                        placeholder = t("settings.liaison.placeholder"),
                        label = t("settings.liaison"),
                        icon = Icons.Default.Router,
                    )

                    Spacer(Modifier.height(8.dp))

                    // User ID
                    CompactTextField(
                        value = settings.userId,
                        onValueChange = { v -> onUpdateSettings { s -> s.copy(userId = v) } },
                        placeholder = "voice:client",
                        label = t("settings.userid"),
                        icon = Icons.Default.Person,
                        imeAction = ImeAction.Done,
                    )

                    Spacer(Modifier.height(12.dp))

                    // Save button with feedback animation
                    val isSaved = saveFeedback != null
                    val buttonScale by animateFloatAsState(
                        targetValue = if (isSaved) 0.97f else 1f,
                        animationSpec = spring(dampingRatio = 0.6f),
                        label = "save_scale",
                    )
                    PillButton(
                        text = saveFeedback ?: t("action.save"),
                        icon = if (isSaved) Icons.Default.Check else Icons.Default.Save,
                        color = if (isSaved) Green else Cyan,
                        modifier = Modifier
                            .fillMaxWidth()
                            .scale(buttonScale),
                        onClick = onSave,
                    )
                }
            }
        }

        // ── Preferences ──
        item {
            SectionLabel(t("settings.prefs"))
            SettingsGroup {
                // Language selector row
                SettingRow(
                    icon = Icons.Default.Language,
                    label = t("settings.language"),
                    description = null,
                    onClick = { showLangDialog = true },
                ) {
                    Text(
                        t(currentLangLabel),
                        color = Cyan,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(Modifier.width(2.dp))
                    Icon(Icons.Default.ChevronRight, null, tint = Dim, modifier = Modifier.size(16.dp))
                }

                // Record duration row
                SettingRow(
                    icon = Icons.Default.Mic,
                    label = t("settings.record"),
                    description = t("settings.record.desc"),
                    showDivider = false,
                ) {
                    Text(
                        t("settings.record.value", settings.recordSeconds),
                        color = Cyan,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                    )
                }
                // Slider underneath
                Slider(
                    value = settings.recordSeconds.toFloat(),
                    onValueChange = { v ->
                        onUpdateSettings { it.copy(recordSeconds = v.toInt().coerceIn(2, 60)) }
                    },
                    onValueChangeFinished = onSave,
                    valueRange = 2f..60f,
                    steps = 57,
                    colors = SliderDefaults.colors(
                        thumbColor = Cyan,
                        activeTrackColor = Cyan,
                        inactiveTrackColor = Line,
                    ),
                    modifier = Modifier.padding(horizontal = 14.dp).padding(bottom = 8.dp),
                )
            }
        }

        // Language picker dialog
        if (showLangDialog) {
            item {
                // Rendered inline for simplicity; actual dialog is outside LazyColumn
            }
        }

        // ── About & Diagnostics ──
        item {
            SectionLabel(t("settings.section.about_debug"))
            SettingsGroup {
                SettingRow(
                    icon = Icons.Default.Info,
                    iconTint = Muted,
                    label = t("app.name"),
                    description = "v0.5 · Android Native",
                    showDivider = true,
                )
                SettingRow(
                    icon = Icons.Default.Terminal,
                    iconTint = Cyan,
                    label = t("settings.dev_logs"),
                    description = t("settings.dev_logs.desc"),
                    showDivider = false,
                    onClick = { showDevLogsDialog = true },
                ) {
                    Icon(Icons.Default.ChevronRight, null, tint = Dim, modifier = Modifier.size(16.dp))
                }
            }
        }
    }

    if (showDevLogsDialog) {
        DeveloperLogsDialog(onDismissRequest = { showDevLogsDialog = false })
    }

    // Language Dialog (outside LazyColumn to avoid scroll issues)
    if (showLangDialog) {
        AlertDialog(
            onDismissRequest = { showLangDialog = false },
            title = { Text(t("settings.language"), color = Text, fontWeight = FontWeight.Bold) },
            containerColor = Panel,
            shape = RoundedCornerShape(16.dp),
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    languageOptions.forEach { (value, labelKey) ->
                        val isSelected = settings.language == value
                        Surface(
                            color = if (isSelected) Cyan.copy(alpha = 0.1f) else Color.Transparent,
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    onUpdateSettings { it.copy(language = value) }
                                    onSave()
                                    showLangDialog = false
                                },
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                RadioButton(
                                    selected = isSelected,
                                    onClick = {
                                        onUpdateSettings { it.copy(language = value) }
                                        onSave()
                                        showLangDialog = false
                                    },
                                    colors = RadioButtonDefaults.colors(selectedColor = Cyan),
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(t(labelKey), color = if (isSelected) Cyan else Text, fontSize = 14.sp)
                            }
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { showLangDialog = false }) {
                    Text(t("action.cancel"), color = Muted)
                }
            },
        )
    }
}

// =================================================================
// TAB 2: Audio & Voice
// =================================================================
@Composable
private fun AudioSettingsTab(
    settings: ClientSettings,
    audioState: com.robonix.client.ui.audio.AudioUiState,
    handsfreeStatus: com.robonix.client.data.model.HandsfreeStatus?,
    handsfreeBusy: Boolean,
    handsfreeError: String?,
    voiceBusy: Boolean,
    onToggleHandsfree: () -> Unit,
    onSelectMicProvider: (String) -> Unit,
    onSelectMicDevice: (String) -> Unit,
    onSelectSpeakerProvider: (String) -> Unit,
    onSelectSpeakerDevice: (String) -> Unit,
    onRefreshRoute: () -> Unit,
    onApplyRoute: () -> Unit,
    onTestMic: () -> Unit,
    onTestSpeaker: () -> Unit,
    onStartVuRecording: () -> Unit,
    onStopVuRecording: () -> Unit,
    onUpdateEnrollUserId: (String) -> Unit,
    onEnrollVoiceprint: () -> Unit,
) {
    var showLogs by rememberSaveable { mutableStateOf(false) }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        contentPadding = PaddingValues(bottom = 40.dp),
    ) {
        // ── 1. Hands-Free Mode ──
        item {
            val hfEnabled = handsfreeStatus?.enabled == true
            SectionLabel(t("audio.hf.title"))
            SettingsGroup {
                // Main toggle row
                SettingRow(
                    icon = Icons.Default.RecordVoiceOver,
                    iconTint = if (hfEnabled) Green else Cyan,
                    label = t("audio.hf.title"),
                    description = t("audio.hf.subtitle"),
                    showDivider = hfEnabled,
                ) {
                    Switch(
                        checked = hfEnabled,
                        onCheckedChange = { onToggleHandsfree() },
                        enabled = !handsfreeBusy && !voiceBusy,
                        colors = SwitchDefaults.colors(
                            checkedTrackColor = Green,
                            checkedThumbColor = Bg,
                        ),
                    )
                }

                // Status detail (only shown when enabled)
                AnimatedVisibility(visible = hfEnabled) {
                    Surface(
                        color = Panel2,
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 4.dp)
                            .padding(bottom = 10.dp),
                    ) {
                        Column(modifier = Modifier.padding(10.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(t("settings.audio.running_status"), color = Muted, fontSize = 11.sp)
                                Surface(
                                    color = Green.copy(alpha = 0.15f),
                                    shape = RoundedCornerShape(999.dp),
                                ) {
                                    Text(
                                        handsfreeStatus?.state?.takeIf { it.isNotBlank() }?.let { s ->
                                            t("audio.hf.on") + " · " + tStatus(s)
                                        } ?: t("audio.hf.on"),
                                        color = Green,
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Bold,
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                                    )
                                }
                            }
                            if (handsfreeStatus?.keyword?.isNotBlank() == true) {
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    t("audio.hf.keyword", handsfreeStatus.keyword),
                                    color = Cyan, fontSize = 11.sp,
                                )
                            }
                            if (handsfreeStatus?.lastTranscript?.isNotBlank() == true) {
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    t("audio.hf.last.transcript", handsfreeStatus.lastTranscript),
                                    color = Text, fontSize = 11.sp, maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                }

                // Warnings
                if (voiceBusy) {
                    Text(
                        t("audio.hf.voicebusy"),
                        color = Amber,
                        fontSize = 11.sp,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 4.dp),
                    )
                }
                if (handsfreeError != null) {
                    Text(
                        t("audio.hf.error", handsfreeError),
                        color = Red,
                        fontSize = 11.sp,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 4.dp),
                    )
                }
            }
        }

        // ── 2. Audio Hardware Route ──
        item {
            SectionLabel(t("audio.route.title"))
            SettingsGroup {
                Column(modifier = Modifier.padding(14.dp)) {
                    // Input section
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Mic, null, tint = Cyan, modifier = Modifier.size(14.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(t("audio.route.input"), color = Muted, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }
                    Spacer(Modifier.height(4.dp))
                    Dropdown(
                        audioState.micProviders.associate { it.id to "${it.id} (${it.namespace})" },
                        audioState.selectedMicProvider,
                        onSelectMicProvider,
                        t("audio.route.select.input"),
                    )
                    Spacer(Modifier.height(4.dp))
                    Dropdown(
                        audioState.micDevices.associate { it.id to it.name.ifBlank { it.id } },
                        audioState.selectedMicDevice,
                        onSelectMicDevice,
                        if (audioState.devicesBusy) t("audio.route.device.loading") else t("audio.route.device.none"),
                    )

                    Spacer(Modifier.height(12.dp))
                    HorizontalDivider(color = Line.copy(alpha = 0.4f), thickness = 0.5.dp)
                    Spacer(Modifier.height(12.dp))

                    // Output section
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.VolumeUp, null, tint = Cyan, modifier = Modifier.size(14.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(t("audio.route.output"), color = Muted, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }
                    Spacer(Modifier.height(4.dp))
                    Dropdown(
                        audioState.speakerProviders.associate { it.id to "${it.id} (${it.namespace})" },
                        audioState.selectedSpeakerProvider,
                        onSelectSpeakerProvider,
                        t("audio.route.select.output"),
                    )
                    Spacer(Modifier.height(4.dp))
                    Dropdown(
                        audioState.speakerDevices.associate { it.id to it.name.ifBlank { it.id } },
                        audioState.selectedSpeakerDevice,
                        onSelectSpeakerDevice,
                        if (audioState.devicesBusy) t("audio.route.device.loading") else t("audio.route.device.none"),
                    )

                    Spacer(Modifier.height(12.dp))

                    // Action buttons
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        PillButton(
                            text = t("action.refresh"),
                            icon = Icons.Default.Refresh,
                            color = Cyan,
                            filled = false,
                            modifier = Modifier.weight(1f),
                            onClick = onRefreshRoute,
                        )
                        PillButton(
                            text = t("audio.route.apply"),
                            icon = Icons.Default.Check,
                            enabled = !audioState.isBusy,
                            modifier = Modifier.weight(1f),
                            onClick = onApplyRoute,
                        )
                    }

                    // Route status
                    val routeError = audioState.routeError
                    val routeStatus = when {
                        audioState.applyStatus.isNotBlank() -> audioState.applyStatus
                        routeError != null -> t("audio.route.status.error", routeError)
                        audioState.routeLoaded -> t("audio.route.status.found", audioState.providersFound)
                        settings.atlasEndpoint.isBlank() -> t("audio.route.status.first")
                        else -> t("audio.route.status.initial")
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(
                        routeStatus,
                        color = when (audioState.applyOk) {
                            true -> Green
                            false -> Red
                            else -> Dim
                        },
                        fontSize = 11.sp,
                    )
                }
            }
        }

        // ── 3. Audio Diagnostics ──
        item {
            SectionLabel(t("audio.diag.title"))
            SettingsGroup {
                Column(modifier = Modifier.padding(14.dp)) {
                    // Test buttons in a row
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        PillButton(
                            text = t("audio.diag.mic"),
                            icon = Icons.Default.Mic,
                            enabled = !audioState.isBusy,
                            color = Cyan,
                            filled = false,
                            modifier = Modifier.weight(1f),
                            onClick = onTestMic,
                        )
                        PillButton(
                            text = t("audio.diag.spk"),
                            icon = Icons.Default.VolumeUp,
                            enabled = !audioState.isBusy,
                            color = Cyan,
                            filled = false,
                            modifier = Modifier.weight(1f),
                            onClick = onTestSpeaker,
                        )
                    }

                    // Test result
                    AnimatedVisibility(visible = audioState.testStatus.isNotBlank()) {
                        Surface(
                            color = when (audioState.testResultClass) {
                                TestResultClass.Success -> Green.copy(alpha = 0.08f)
                                TestResultClass.Error -> Red.copy(alpha = 0.08f)
                                TestResultClass.None -> Panel2
                            },
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 8.dp),
                        ) {
                            Text(
                                audioState.testStatus,
                                color = when (audioState.testResultClass) {
                                    TestResultClass.Success -> Green
                                    TestResultClass.Error -> Red
                                    TestResultClass.None -> Text
                                },
                                fontSize = 12.sp,
                                modifier = Modifier.padding(10.dp),
                            )
                        }
                    }

                    Spacer(Modifier.height(12.dp))
                    HorizontalDivider(color = Line.copy(alpha = 0.4f), thickness = 0.5.dp)
                    Spacer(Modifier.height(10.dp))

                    // VU Meter section
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(t("audio.vu.title"), color = Muted, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                        Text("${(audioState.vuLevel * 100).toInt()}%", color = Cyan, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                    Spacer(Modifier.height(4.dp))
                    VuMeter(audioState.vuLevel)
                    Spacer(Modifier.height(8.dp))

                    if (audioState.isRecording) {
                        PillButton(
                            text = t("audio.vu.stop"),
                            icon = Icons.Default.Stop,
                            color = Red,
                            modifier = Modifier.fillMaxWidth(),
                            onClick = onStopVuRecording,
                        )
                    } else {
                        PillButton(
                            text = t("audio.vu.start"),
                            icon = Icons.Default.FiberManualRecord,
                            color = Cyan,
                            filled = false,
                            modifier = Modifier.fillMaxWidth(),
                            onClick = onStartVuRecording,
                        )
                    }
                }
            }
        }

        // ── 4. Voiceprint Registration ──
        item {
            SectionLabel(t("audio.vp.title"))
            SettingsGroup {
                Column(modifier = Modifier.padding(14.dp)) {
                    CompactTextField(
                        value = audioState.enrollUserId.ifBlank { settings.userId },
                        onValueChange = onUpdateEnrollUserId,
                        placeholder = t("audio.vp.user"),
                        label = t("audio.vp.user"),
                        icon = Icons.Default.Fingerprint,
                        imeAction = ImeAction.Done,
                    )

                    Spacer(Modifier.height(10.dp))

                    PillButton(
                        text = if (audioState.enrollBusy) t("audio.vp.recording") else t("audio.vp.enroll"),
                        icon = if (audioState.enrollBusy) Icons.Default.HourglassTop else Icons.Default.Mic,
                        enabled = !audioState.enrollBusy,
                        color = if (audioState.enrollBusy) Amber else Cyan,
                        modifier = Modifier.fillMaxWidth(),
                        onClick = onEnrollVoiceprint,
                    )

                    AnimatedVisibility(visible = audioState.enrollStatus.isNotBlank()) {
                        Text(
                            audioState.enrollStatus,
                            color = Muted,
                            fontSize = 11.sp,
                            modifier = Modifier.padding(top = 6.dp),
                        )
                    }
                }
            }
        }

        // ── 5. Audio Logs ──
        item {
            SettingsGroup {
                SettingRow(
                    icon = Icons.Default.Notes,
                    label = t("audio.log.title"),
                    onClick = { showLogs = !showLogs },
                    showDivider = false,
                ) {
                    val rotation by animateFloatAsState(
                        targetValue = if (showLogs) 180f else 0f,
                        animationSpec = tween(200),
                        label = "arrow_rotation",
                    )
                    Icon(
                        Icons.Default.KeyboardArrowDown,
                        null,
                        tint = Muted,
                        modifier = Modifier
                            .size(20.dp)
                            .rotate(rotation),
                    )
                }

                AnimatedVisibility(visible = showLogs) {
                    val logs = audioState.audioLog.takeLast(20)
                    if (logs.isEmpty()) {
                        Text(
                            t("audio.log.empty"),
                            color = Muted,
                            fontSize = 11.sp,
                            modifier = Modifier.padding(14.dp),
                        )
                    } else {
                        Surface(
                            color = Panel2,
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 14.dp)
                                .padding(bottom = 10.dp),
                        ) {
                            Column(modifier = Modifier.padding(8.dp)) {
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
                }
            }
        }
    }
}

// =================================================================
// TAB 3: System & Contracts
// =================================================================
@Composable
private fun SystemSettingsTab(
    snapshot: com.robonix.client.data.model.SystemSnapshot?,
    onRefresh: () -> Unit,
) {
    var showDevLogsDialog by remember { mutableStateOf(false) }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        contentPadding = PaddingValues(bottom = 40.dp),
    ) {
        snapshot?.let { snap ->
            // ── System Summary ──
            item {
                SectionLabel(t("settings.system"))
                SettingsGroup {
                    // Header row
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .size(32.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(Cyan.copy(alpha = 0.12f)),
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(Icons.Default.Dns, null, tint = Cyan, modifier = Modifier.size(17.dp))
                            }
                            Spacer(Modifier.width(12.dp))
                            Text(t("settings.system"), color = Text, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            StateBadge(snap.summary.state)
                            Spacer(Modifier.width(8.dp))
                            IconButton(onClick = onRefresh, modifier = Modifier.size(32.dp)) {
                                Icon(Icons.Default.Refresh, t("action.refresh"), tint = Cyan, modifier = Modifier.size(18.dp))
                            }
                        }
                    }

                    // Metric Cards Row
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp)
                            .padding(bottom = 14.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        MetricCard(t("settings.metric.providers"), snap.summary.providers.toString(), Cyan, Modifier.weight(1f))
                        MetricCard(t("settings.metric.active"), snap.summary.active.toString(), Green, Modifier.weight(1f))
                        MetricCard(t("settings.metric.errors"), snap.summary.errors.toString(), Red, Modifier.weight(1f))
                    }
                }
            }

            // ── Required Contracts ──
            item {
                SectionLabel(t("settings.contracts"))
                SettingsGroup {
                    snap.requiredContracts.forEachIndexed { index, contract ->
                        SettingRow(
                            icon = if (contract.available) Icons.Default.CheckCircle else Icons.Default.Cancel,
                            iconTint = if (contract.available) Green else Red,
                            label = contract.label,
                            description = if (contract.available) contract.providers.firstOrNull() ?: t("settings.contract.ok")
                            else t("settings.contract.missing"),
                            showDivider = index < snap.requiredContracts.lastIndex,
                        )
                    }
                }
            }

            // ── Providers ──
            if (snap.providers.isNotEmpty()) {
                item {
                    SectionLabel(t("settings.providers", snap.providers.size))
                    SettingsGroup {
                        snap.providers.forEachIndexed { index, provider ->
                            SettingRow(
                                icon = Icons.Default.Extension,
                                iconTint = stateColor(provider.state),
                                label = provider.id,
                                description = provider.kind,
                                showDivider = index < snap.providers.lastIndex,
                            ) {
                                Surface(
                                    color = stateColor(provider.state).copy(alpha = 0.12f),
                                    shape = RoundedCornerShape(999.dp),
                                ) {
                                    Text(
                                        tStatus(provider.state),
                                        color = stateColor(provider.state),
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Bold,
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                                    )
                                }
                            }
                        }
                    }
                }
            }

            snap.error?.let { err ->
                item {
                    Surface(color = Red.copy(alpha = 0.08f), shape = RoundedCornerShape(10.dp)) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(Icons.Default.Error, null, tint = Red, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(err, color = Red, fontSize = 11.sp)
                        }
                    }
                }
            }
        } ?: item {
            // Empty state
            SettingsGroup {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Icon(Icons.Default.CloudOff, null, tint = Dim, modifier = Modifier.size(40.dp))
                    Spacer(Modifier.height(12.dp))
                    Text(t("settings.system.hint"), color = Muted, fontSize = 13.sp)
                    Spacer(Modifier.height(14.dp))
                    PillButton(
                        text = t("action.refresh"),
                        icon = Icons.Default.Refresh,
                        onClick = onRefresh,
                    )
                }
            }
        }
        item {
            SectionLabel(t("settings.section.debug_logs"))
            SettingsGroup {
                SettingRow(
                    icon = Icons.Default.Terminal,
                    iconTint = Cyan,
                    label = t("settings.dev_logs"),
                    description = t("settings.dev_logs.desc"),
                    showDivider = false,
                    onClick = { showDevLogsDialog = true },
                ) {
                    Icon(Icons.Default.ChevronRight, null, tint = Dim, modifier = Modifier.size(16.dp))
                }
            }
        }
    }

    if (showDevLogsDialog) {
        DeveloperLogsDialog(onDismissRequest = { showDevLogsDialog = false })
    }
}

// =================================================================
// Sub-components
// =================================================================
@Composable
private fun MetricCard(label: String, value: String, accent: Color, modifier: Modifier = Modifier) {
    Surface(
        color = accent.copy(alpha = 0.06f),
        shape = RoundedCornerShape(10.dp),
        border = BorderStroke(0.5.dp, accent.copy(alpha = 0.15f)),
        modifier = modifier,
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(value, color = accent, fontSize = 22.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(2.dp))
            Text(label, color = Muted, fontSize = 10.sp)
        }
    }
}

@Composable
private fun StateBadge(state: String) {
    val color = when (state.lowercase()) {
        "ready" -> Green
        "degraded" -> Amber
        "idle" -> Muted
        else -> Red
    }
    Surface(color = color.copy(alpha = 0.12f), shape = RoundedCornerShape(999.dp)) {
        Text(
            tStatus(state),
            color = color,
            fontSize = 10.sp,
            fontWeight = FontWeight.ExtraBold,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
        )
    }
}

private fun stateColor(state: String) = when (state) {
    "ACTIVE" -> Green
    "ERROR" -> Red
    "TERMINATED" -> Red
    "INACTIVE" -> Muted
    else -> Dim
}

@Composable
fun inputColors() = OutlinedTextFieldDefaults.colors(
    focusedTextColor = Text,
    unfocusedTextColor = Text,
    focusedLabelColor = Cyan,
    unfocusedLabelColor = Muted,
    focusedBorderColor = Cyan,
    unfocusedBorderColor = Line,
    cursorColor = Cyan,
    focusedContainerColor = Panel2,
    unfocusedContainerColor = Panel2,
    focusedLeadingIconColor = Cyan,
    unfocusedLeadingIconColor = Muted,
)
