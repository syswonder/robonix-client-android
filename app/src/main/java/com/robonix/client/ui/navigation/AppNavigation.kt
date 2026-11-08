package com.robonix.client.ui.navigation

import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.robonix.client.ui.audio.AudioScreen
import com.robonix.client.ui.chat.ChatScreen
import com.robonix.client.ui.chat.ChatViewModel
import com.robonix.client.ui.chat.RtdlScreen
import com.robonix.client.ui.components.PulsingStatusDot
import com.robonix.client.ui.i18n.t
import com.robonix.client.ui.i18n.tStatus
import com.robonix.client.ui.perception.PerceptionScreen
import com.robonix.client.ui.settings.SettingsScreen
import com.robonix.client.ui.theme.*
import com.robonix.client.ui.vitals.VitalsScreen
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import kotlinx.coroutines.launch

sealed class Screen(val route: String, val labelKey: String, val icon: ImageVector) {
    object Chat : Screen("chat", "nav.chat", Icons.Default.Chat)
    object Perception : Screen("perception", "nav.perception", Icons.Default.Sensors)
    object Rtdl : Screen("rtdl", "nav.rtdl", Icons.Default.AccountTree)
    object Audio : Screen("audio", "nav.audio", Icons.Default.MusicNote)
    object Vitals : Screen("vitals", "nav.vitals", Icons.Default.MonitorHeart)
    object Settings : Screen("settings", "nav.settings", Icons.Default.Settings)
}

// Top-level lazy resolution avoids classloader cycle
private val navScreens: List<Screen> by lazy {
    listOf(Screen.Chat, Screen.Perception, Screen.Rtdl, Screen.Vitals, Screen.Settings)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppNavigation() {
    val navController = rememberNavController()
    val sharedViewModel: SharedViewModel = hiltViewModel()
    // Hoisted to the Activity scope so the Chat tab keeps one ViewModel across
    // navigation, letting the top bar (new session / session menu) talk to the
    // chat directly.
    val chatViewModel: ChatViewModel = hiltViewModel()
    val scope = rememberCoroutineScope()
    // Hoisted so the top-bar menu button can open the chat session drawer,
    // whose ModalNavigationDrawer lives inside ChatScreen.
    val sessionDrawerState = rememberDrawerState(DrawerValue.Closed)
    val connectionState by sharedViewModel.connectionState.collectAsState()
    val settings by sharedViewModel.settings.collectAsState()
    val unreadRtdl by sharedViewModel.unreadRtdlCount.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }

    // Collect events for snackbar
    LaunchedEffect(Unit) {
        sharedViewModel.events.collect { event ->
            when (event) {
                is AppEvent.Snackbar -> snackbarHostState.showSnackbar(event.message)
            }
        }
    }

    // Track the visible destination (tab clicks AND system back) so the
    // RTDL unread badge resets exactly when the RTDL screen is shown.
    val navBackStackEntryForRoute by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntryForRoute?.destination?.route
    LaunchedEffect(currentRoute) {
        currentRoute?.let { sharedViewModel.onTabSelected(it) }
    }

    Scaffold(
        containerColor = Bg,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            text = t("app.name"),
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.5.sp,
                            color = Text,
                            maxLines = 1,
                        )
                        Spacer(Modifier.width(8.dp))
                        ConnectionChip(
                            state = connectionState,
                            host = settings.cleanHost,
                            modifier = Modifier.weight(1f, fill = false),
                        )
                    }
                },
                // Session-sidebar launcher: only meaningful on the Chat tab.
                navigationIcon = {
                    if (currentRoute == Screen.Chat.route) {
                        IconButton(onClick = { scope.launch { sessionDrawerState.open() } }) {
                            Icon(Icons.Default.Menu, t("chat.history.title"), tint = Muted)
                        }
                    }
                },
                actions = {
                    // New-session shortcut lives in the top bar (left of Refresh)
                    // and only appears on the conversation tab.
                    if (currentRoute == Screen.Chat.route) {
                        IconButton(onClick = { chatViewModel.newSession() }) {
                            Icon(Icons.Default.Add, t("action.new"), tint = Muted)
                        }
                    }
                    IconButton(onClick = { sharedViewModel.refreshSystem() }) {
                        Icon(Icons.Default.Refresh, t("action.refresh"), tint = Muted)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Panel.copy(alpha = 0.96f),
                    titleContentColor = Text,
                ),
            )
        },
        bottomBar = {
            NavigationBar(
                containerColor = Panel,
                contentColor = Text,
            ) {
                val navBackStackEntry by navController.currentBackStackEntryAsState()
                val currentDestination = navBackStackEntry?.destination

                navScreens.forEach { screen ->
                    // Resolve every value from the loop item into plain locals
                    // first, so the content lambdas never close over the loop
                    // variable itself.
                    val route = screen.route
                    val tabIcon = screen.icon
                    val tabLabel = t(screen.labelKey)
                    val selected = currentDestination?.hierarchy?.any { it.route == route } == true
                    NavigationBarItem(
                        selected = selected,
                        onClick = {
                            navController.navigate(route) {
                                popUpTo(navController.graph.findStartDestination().id) {
                                    saveState = true
                                }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        icon = {
                            NavigationTabIcon(
                                icon = tabIcon,
                                contentDescription = tabLabel,
                                selected = selected,
                                showBadge = route == Screen.Rtdl.route,
                                badgeCount = unreadRtdl,
                            )
                        },
                        label = {
                            Text(
                                tabLabel,
                                fontSize = 11.sp,
                                fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                                color = if (selected) Text else Dim,
                            )
                        },
                        colors = NavigationBarItemDefaults.colors(
                            indicatorColor = Cyan.copy(alpha = 0.15f),
                        ),
                    )
                }
            }
        },
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = Screen.Chat.route,
            // consumeWindowInsets: the bottom bar already covers the nav-bar inset,
            // so Chat's imePadding() only adds the part of the keyboard above it.
            modifier = Modifier.padding(innerPadding).consumeWindowInsets(innerPadding),
            enterTransition = { fadeIn(animationSpec = tween(300)) + slideInHorizontally(tween(300)) { it / 4 } },
            exitTransition = { fadeOut(animationSpec = tween(300)) + slideOutHorizontally(tween(300)) { -it / 4 } },
            popEnterTransition = { fadeIn(animationSpec = tween(300)) + slideInHorizontally(tween(300)) { -it / 4 } },
            popExitTransition = { fadeOut(animationSpec = tween(300)) + slideOutHorizontally(tween(300)) { it / 4 } },
        ) {
            composable(Screen.Chat.route) {
                ChatScreen(
                    chatViewModel = chatViewModel,
                    sessionDrawerState = sessionDrawerState,
                )
            }
            composable(Screen.Perception.route) { PerceptionScreen() }
            composable(Screen.Rtdl.route) { RtdlScreen() }
            composable(Screen.Audio.route) { AudioScreen() }
            composable(Screen.Vitals.route) { VitalsScreen() }
            composable(Screen.Settings.route) { SettingsScreen() }
        }
    }
}

/** Icon for a bottom-nav tab, with the optional RTDL unread badge. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NavigationTabIcon(
    icon: ImageVector,
    contentDescription: String,
    selected: Boolean,
    showBadge: Boolean,
    badgeCount: Int,
) {
    BadgedBox(
        badge = {
            if (showBadge && badgeCount > 0) {
                Badge(
                    containerColor = Red,
                    contentColor = Text,
                ) { Text("$badgeCount", fontSize = 10.sp) }
            }
        },
    ) {
        Icon(
            icon,
            contentDescription = contentDescription,
            tint = if (selected) Text else Dim,
        )
    }
}

@Composable
fun ConnectionChip(
    state: ConnectionState,
    modifier: Modifier = Modifier,
    host: String = "",
) {
    val color = when {
        state.isConnecting -> Amber
        state.isOnline -> Green
        else -> Red
    }
    val label = when {
        state.isConnecting -> t("conn.connecting")
        else -> tStatus(state.statusLabel)
    }
    Surface(
        color = color.copy(alpha = 0.12f),
        shape = RoundedCornerShape(12.dp),
        border = androidx.compose.foundation.BorderStroke(0.8.dp, color.copy(alpha = 0.4f)),
        modifier = modifier.heightIn(min = 26.dp),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (state.isOnline) {
                PulsingStatusDot(color = Green, size = 5.dp)
            } else {
                Box(
                    modifier = Modifier
                        .size(6.dp)
                        .clip(CircleShape)
                        .background(color),
                )
            }
            Spacer(Modifier.width(5.dp))
            Text(
                text = label,
                color = Text,
                fontSize = 11.sp,
                lineHeight = 14.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                softWrap = false,
            )
            if (state.isOnline && host.isNotBlank()) {
                Box(
                    modifier = Modifier
                        .padding(horizontal = 5.dp)
                        .size(3.dp)
                        .clip(CircleShape)
                        .background(Dim.copy(alpha = 0.6f)),
                )
                Text(
                    text = host,
                    color = Dim,
                    fontSize = 10.sp,
                    lineHeight = 13.sp,
                    fontFamily = FontFamily.Monospace,
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

