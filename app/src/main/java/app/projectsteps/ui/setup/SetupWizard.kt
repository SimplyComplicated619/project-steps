@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package app.projectsteps.ui.setup

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.projectsteps.data.BackupRepository
import app.projectsteps.data.SettingKeys
import app.projectsteps.reminders.Notifications
import app.projectsteps.ui.Format
import app.projectsteps.ui.LocalNotificationPermission
import app.projectsteps.ui.MainViewModel
import app.projectsteps.ui.components.TimePickerDialog
import app.projectsteps.ui.importflow.ImportFlow
import app.projectsteps.ui.importflow.ImportState
import app.projectsteps.ui.importflow.ImportViewModel
import app.projectsteps.ui.screens.SwitchRow

private val STEP_TITLES = listOf("Welcome", "Import projects", "Review import", "Reminder preferences", "Backup", "Finish")

/**
 * One-time setup. Completing it stores setup_completed = true; later launches open Today directly.
 * "Run setup again" reuses this wizard and never resets data.
 */
@Composable
fun SetupWizard(vm: MainViewModel, importVm: ImportViewModel, isRerun: Boolean) {
    var step by rememberSaveable { mutableIntStateOf(0) }
    var importSummary by rememberSaveable { mutableStateOf<String?>(null) }
    val importState by importVm.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(Unit) { vm.messages.collect { snackbar.showSnackbar(it.text) } }

    // Steps 2 and 3 share the import flow; the indicator follows its state.
    val shownStep = if (step == 1 && (importState is ImportState.Preview || importState is ImportState.Done)) 2 else step

    BackHandler(enabled = step > 0) {
        if (step == 1 && importState !is ImportState.Idle) importVm.reset() else step--
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            Column {
                TopAppBar(
                    title = {
                        Column {
                            Text(STEP_TITLES[shownStep])
                            Text("Setup · step ${shownStep + 1} of ${STEP_TITLES.size}", style = MaterialTheme.typography.labelMedium)
                        }
                    },
                    actions = { if (isRerun) TextButton(onClick = { importVm.reset(); vm.cancelSetupRerun() }) { Text("Exit setup") } },
                )
                LinearProgressIndicator(progress = { (shownStep + 1) / STEP_TITLES.size.toFloat() }, modifier = Modifier.fillMaxWidth())
            }
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when (step) {
                0 -> Welcome(isRerun) { step = 1 }
                1 -> Column(Modifier.fillMaxSize()) {
                    ImportFlow(
                        importVm = importVm,
                        contentPadding = PaddingValues(bottom = 8.dp),
                        showDemo = true,
                        finishLabel = "Continue",
                        onFinished = { done ->
                            importSummary = done.summary
                            importVm.reset()
                            step = 3
                        },
                        modifier = Modifier.weight(1f),
                    )
                    if (importState is ImportState.Idle) {
                        TextButton(onClick = { step = 3 }, modifier = Modifier.padding(16.dp)) {
                            Text(if (isRerun) "Skip: don't import anything now" else "Skip for now (import later from Settings)")
                        }
                    }
                }
                3 -> ReminderPreferences(vm, importSummary, onBack = { step = 1 }, onNext = { step = 4 })
                4 -> BackupPreference(vm, onBack = { step = 3 }, onNext = { step = 5 })
                else -> Finish(vm, importSummary, onBack = { step = 4 })
            }
        }
    }
}

@Composable
private fun StepColumn(content: @Composable () -> Unit) {
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) { content() }
}

@Composable
private fun NavButtons(onBack: (() -> Unit)?, nextLabel: String, onNext: () -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(top = 12.dp)) {
        if (onBack != null) OutlinedButton(onClick = onBack, modifier = Modifier.weight(1f).heightIn(min = 52.dp)) { Text("Back") }
        Button(onClick = onNext, modifier = Modifier.weight(1f).heightIn(min = 52.dp)) { Text(nextLabel) }
    }
}

@Composable
private fun Welcome(isRerun: Boolean, onNext: () -> Unit) {
    StepColumn {
        Text("Project Steps", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Text(
            "Project Steps turns your project documents into interactive checklists and reminders.",
            style = MaterialTheme.typography.titleMedium,
        )
        Text("• Import Project-Checklists.zip, a Word document or Markdown.\n• Check off steps, see what's next, overdue or blocked.\n• Schedule reminders for projects and individual steps.")
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)) {
            Text(
                "Everything stays on this phone. No account, no Internet access, no ads or analytics. " +
                    "Documents are read as data only; nothing in them is ever run.",
                modifier = Modifier.padding(16.dp),
            )
        }
        if (isRerun) Text("Running setup again doesn't change or reset your existing projects.", style = MaterialTheme.typography.bodySmall)
        NavButtons(null, "Get started", onNext)
    }
}

@Composable
private fun ReminderPreferences(vm: MainViewModel, importSummary: String?, onBack: () -> Unit, onNext: () -> Unit) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val permission = LocalNotificationPermission.current
    val context = LocalContext.current
    var pickDefault by remember { mutableStateOf(false) }
    var pickQuietStart by remember { mutableStateOf(false) }
    var pickQuietEnd by remember { mutableStateOf(false) }
    var canPost by remember { mutableStateOf(Notifications.canPost(context)) }
    StepColumn {
        if (importSummary != null) {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)) {
                Text(importSummary, modifier = Modifier.padding(12.dp), style = MaterialTheme.typography.bodySmall)
            }
        }
        SwitchRow(
            "Reminder notifications",
            "Needed to show reminders. Android will ask for permission after a short explanation.",
            settings.notificationsEnabled,
        ) { on ->
            if (on) permission.request { canPost = Notifications.canPost(context) } else vm.set(SettingKeys.NOTIFICATIONS_ENABLED, false)
        }
        if (settings.notificationsEnabled && !canPost) {
            Text("Notifications are blocked by Android. Reminders will be saved but can't appear until you allow them.", color = MaterialTheme.colorScheme.error)
        }
        Text("Default reminder time", style = MaterialTheme.typography.titleMedium)
        OutlinedButton(onClick = { pickDefault = true }) { Text(Format.time(settings.defaultReminderTime)) }

        SwitchRow("Quiet hours", "No notifications during these hours; they appear when quiet hours end.", settings.quietHours.enabled) {
            vm.set(SettingKeys.QUIET_ENABLED, it)
        }
        if (settings.quietHours.enabled) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(onClick = { pickQuietStart = true }) { Text("From " + Format.time(settings.quietHours.start)) }
                OutlinedButton(onClick = { pickQuietEnd = true }) { Text("Until " + Format.time(settings.quietHours.end)) }
            }
        }
        Text("Default snooze length", style = MaterialTheme.typography.titleMedium)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(5, 10, 15, 30, 60).forEach { m ->
                FilterChip(selected = settings.snoozeMinutes == m, onClick = { vm.set(SettingKeys.SNOOZE_MINUTES, m) }, label = { Text("$m min") })
            }
        }
        SwitchRow(
            "Show task details on lock screen",
            "Off: the lock screen only says “You have a scheduled project task.”",
            settings.lockScreenDetails,
        ) { vm.set(SettingKeys.LOCK_SCREEN_DETAILS, it) }
        Text(
            "On Samsung phones, check Settings › Reminder Reliability later for battery settings that can delay reminders.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        NavButtons(onBack, "Next", onNext)
    }
    if (pickDefault) TimePickerDialog(settings.defaultReminderTime, onConfirm = { vm.set(SettingKeys.DEFAULT_REMINDER_TIME, it) }, onDismiss = { pickDefault = false })
    if (pickQuietStart) TimePickerDialog(settings.quietHours.start, onConfirm = { vm.set(SettingKeys.QUIET_START, it) }, onDismiss = { pickQuietStart = false })
    if (pickQuietEnd) TimePickerDialog(settings.quietHours.end, onConfirm = { vm.set(SettingKeys.QUIET_END, it) }, onDismiss = { pickQuietEnd = false })
}

@Composable
private fun BackupPreference(vm: MainViewModel, onBack: () -> Unit, onNext: () -> Unit) {
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(BackupRepository.Format.JSON.mime)) { uri ->
        if (uri != null) vm.export(uri, BackupRepository.Format.JSON)
    }
    StepColumn {
        Text("MANUAL BACKUP", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
        Text(
            "Backups can be manually saved anywhere Android's document picker supports, including Google Drive.",
            style = MaterialTheme.typography.titleMedium,
        )
        Text(
            "When you export, Android's save dialog opens. Pick Google Drive (if installed), Downloads or any other location. " +
                "This is a copy you make yourself, not automatic sync. Project Steps never asks for your Google account or password.",
        )
        Text("You can export or restore any time from Settings › Backup & Restore (JSON, Markdown or ZIP).")
        OutlinedButton(onClick = {
            val stamp = java.time.LocalDateTime.now().toString().take(16).replace(':', '-').replace('T', '_')
            launcher.launch("ProjectSteps-$stamp.json")
        }) { Text("Save a backup now (optional)") }
        NavButtons(onBack, "Next", onNext)
    }
}

@Composable
private fun Finish(vm: MainViewModel, importSummary: String?, onBack: () -> Unit) {
    val snap by vm.snapshot.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    StepColumn {
        Text("You're set", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Text("${snap.activeProjects.size} project(s) · ${snap.tasks.count { it.kind == "TASK" }} task(s)")
        Text("Reminders: " + if (settings.notificationsEnabled) "on" else "off (turn on any time in Settings › Notifications)")
        importSummary?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        Text("From now on Project Steps opens straight to Today. All of these choices can be changed in Settings.")
        Spacer(Modifier.size(8.dp))
        NavButtons(onBack, "Go to Today") { vm.finishSetup() }
    }
}
