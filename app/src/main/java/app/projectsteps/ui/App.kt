@file:OptIn(ExperimentalMaterial3Api::class)

package app.projectsteps.ui

import android.Manifest
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.projectsteps.data.SettingKeys
import app.projectsteps.reminders.Notifications
import app.projectsteps.ui.importflow.ImportScreen
import app.projectsteps.ui.screens.NextActionScreen
import app.projectsteps.ui.screens.ProjectScreen
import app.projectsteps.ui.screens.ProjectsScreen
import app.projectsteps.ui.screens.ReminderEditScreen
import app.projectsteps.ui.screens.SearchScreen
import app.projectsteps.ui.screens.SettingsPageScreen
import app.projectsteps.ui.screens.SettingsScreen
import app.projectsteps.ui.screens.TaskScreen
import app.projectsteps.ui.screens.TodayScreen
import app.projectsteps.ui.screens.UpcomingScreen
import app.projectsteps.ui.setup.SetupWizard
import app.projectsteps.ui.theme.ProjectStepsTheme
import kotlinx.coroutines.launch

/** Lets any screen run the "explain, then ask for notification permission" flow. */
class NotificationPermissionFlow(val request: (onDone: (Boolean) -> Unit) -> Unit)

val LocalNotificationPermission = staticCompositionLocalOf { NotificationPermissionFlow { it(true) } }

@Composable
fun ProjectStepsRoot(vm: MainViewModel) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val rerun by vm.rerunningSetup.collectAsStateWithLifecycle()
    ProjectStepsTheme(settings.theme, settings.amoled, settings.dynamicColor) {
        if (!settings.loaded) {
            Box(Modifier.fillMaxSize())
            return@ProjectStepsTheme
        }
        NotificationPermissionHost(vm) {
            if (!settings.setupCompleted || rerun) {
                val importVm: app.projectsteps.ui.importflow.ImportViewModel = viewModel()
                SetupWizard(vm, importVm, isRerun = settings.setupCompleted)
            } else {
                MainScaffold(vm)
            }
        }
    }
}

/**
 * Android 13+ requires a runtime permission for notifications. We only ask after the user turns
 * reminders on, and only after explaining why. If they decline, reminders stay configured and the
 * app shows that notifications can't appear.
 */
@Composable
private fun NotificationPermissionHost(vm: MainViewModel, content: @Composable () -> Unit) {
    val context = LocalContext.current
    var pendingCallback by remember { mutableStateOf<((Boolean) -> Unit)?>(null) }
    var showRationale by remember { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        vm.set(SettingKeys.NOTIFICATIONS_ENABLED, true)
        if (!granted) vm.say("Notifications are blocked. Reminders are saved but can't appear until you allow notifications.")
        pendingCallback?.invoke(granted)
        pendingCallback = null
    }
    val flow = remember {
        NotificationPermissionFlow { onDone ->
            if (Build.VERSION.SDK_INT < 33 || Notifications.canPost(context)) {
                vm.set(SettingKeys.NOTIFICATIONS_ENABLED, true)
                onDone(Notifications.canPost(context))
            } else {
                pendingCallback = onDone
                showRationale = true
            }
        }
    }
    if (showRationale) {
        AlertDialog(
            onDismissRequest = { showRationale = false; pendingCallback?.invoke(false); pendingCallback = null },
            title = { Text("Allow reminder notifications?") },
            text = {
                Text(
                    "Project Steps shows a notification when a reminder you scheduled is due, with Complete and Snooze buttons. " +
                        "Android will now ask for permission. You can change this later in Settings › Notifications."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showRationale = false
                    if (Build.VERSION.SDK_INT >= 33) launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
                }) { Text("Continue") }
            },
            dismissButton = {
                TextButton(onClick = { showRationale = false; pendingCallback?.invoke(false); pendingCallback = null }) { Text("Not now") }
            },
        )
    }
    CompositionLocalProvider(LocalNotificationPermission provides flow) { content() }
}

@Composable
private fun MainScaffold(vm: MainViewModel) {
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(Unit) {
        vm.messages.collect { msg ->
            launch {
                val result = snackbar.showSnackbar(
                    message = msg.text,
                    actionLabel = if (msg.undo != null) "Undo" else null,
                    withDismissAction = msg.undo == null,
                    duration = if (msg.undo != null) SnackbarDuration.Long else SnackbarDuration.Short,
                )
                if (result == SnackbarResult.ActionPerformed) msg.undo?.let { vm.undo(it) }
            }
        }
    }
    BackHandler(enabled = vm.backStack.size > 1) { vm.back() }

    val route = vm.backStack.last()
    val isTab = route in Route.tabs
    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            if (isTab) {
                NavigationBar {
                    TabItem(vm, Route.Today, "Today") { Icon(Icons.Filled.Home, contentDescription = null) }
                    TabItem(vm, Route.Upcoming, "Upcoming") { Icon(Icons.Filled.DateRange, contentDescription = null) }
                    TabItem(vm, Route.Projects, "Projects") { Icon(Icons.AutoMirrored.Filled.List, contentDescription = null) }
                    TabItem(vm, Route.Search, "Search") { Icon(Icons.Filled.Search, contentDescription = null) }
                }
            }
        },
    ) { padding ->
        Box(Modifier.padding(PaddingValues(bottom = padding.calculateBottomPadding()))) {
            when (route) {
                Route.Today -> TodayScreen(vm)
                Route.Upcoming -> UpcomingScreen(vm)
                Route.Projects -> ProjectsScreen(vm)
                Route.Search -> SearchScreen(vm)
                is Route.Project -> ProjectScreen(vm, route.id)
                is Route.Task -> TaskScreen(vm, route.id)
                Route.NextAction -> NextActionScreen(vm)
                Route.Settings -> SettingsScreen(vm)
                is Route.SettingsPage -> SettingsPageScreen(vm, route.page)
                Route.Import -> {
                    val importVm: app.projectsteps.ui.importflow.ImportViewModel = viewModel()
                    ImportScreen(vm, importVm)
                }
                is Route.ReminderEdit -> ReminderEditScreen(vm, route)
            }
        }
    }
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.TabItem(vm: MainViewModel, tab: Route, label: String, icon: @Composable () -> Unit) {
    NavigationBarItem(
        selected = vm.backStack.first() == tab,
        onClick = { vm.navigate(tab) },
        icon = icon,
        label = { Text(label) },
    )
}
