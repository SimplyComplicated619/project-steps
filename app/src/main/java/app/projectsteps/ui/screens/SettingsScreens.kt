@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package app.projectsteps.ui.screens

import android.app.ActivityManager
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.projectsteps.BuildConfig
import app.projectsteps.core.backup.BackupData
import app.projectsteps.data.BackupRepository
import app.projectsteps.data.SettingKeys
import app.projectsteps.data.ThemeMode
import app.projectsteps.reminders.Notifications
import app.projectsteps.ui.Format
import app.projectsteps.ui.LocalNotificationPermission
import app.projectsteps.ui.MainViewModel
import app.projectsteps.ui.Route
import app.projectsteps.ui.components.ConfirmDialog
import app.projectsteps.ui.components.ListHeading
import app.projectsteps.ui.components.ScreenScaffold
import app.projectsteps.ui.components.TextInputDialog
import app.projectsteps.ui.components.TimePickerDialog
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId

@Composable
fun SettingsScreen(vm: MainViewModel) {
    var confirmRerun by remember { mutableStateOf(false) }
    ScreenScaffold(title = "Settings", onBack = { vm.back() }) { padding ->
        Column(Modifier.padding(padding).verticalScroll(rememberScrollState())) {
            fun entry(page: Route.Page, body: String) = page to body
            listOf(
                entry(Route.Page.PROJECTS, "Archived projects, demo data, trash"),
                entry(Route.Page.NOTIFICATIONS, "Turn reminders on or off, lock screen"),
                entry(Route.Page.QUIET_HOURS, "No notifications during these hours"),
                entry(Route.Page.REMINDER_DEFAULTS, "Default time and snooze length"),
                entry(Route.Page.RELIABILITY, "Samsung battery and permission checks"),
                entry(Route.Page.BACKUP, "Manual backup, export and restore"),
            ).forEach { (page, body) -> SettingsEntry(page.title, body) { vm.navigate(Route.SettingsPage(page)) } }
            SettingsEntry("Import", "Import a ZIP, DOCX or Markdown file") { vm.navigate(Route.Import) }
            listOf(
                entry(Route.Page.APPEARANCE, "Theme, AMOLED black, completed tasks"),
                entry(Route.Page.PRIVACY, "Lock screen details and data handling"),
                entry(Route.Page.ABOUT, "Version and safety notes"),
            ).forEach { (page, body) -> SettingsEntry(page.title, body) { vm.navigate(Route.SettingsPage(page)) } }
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            SettingsEntry("Run setup again", "Walk through the setup steps. Your projects are not changed or reset.") { confirmRerun = true }
            Spacer(Modifier.size(32.dp))
        }
    }
    if (confirmRerun) {
        ConfirmDialog(
            "Run setup again?", "Your projects, tasks and reminders stay exactly as they are. You can import more documents during setup.",
            "Start setup", onConfirm = { vm.runSetupAgain() }, onDismiss = { confirmRerun = false },
        )
    }
}

@Composable
private fun SettingsEntry(title: String, body: String, onClick: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 12.dp)
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        Text(body, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
fun SettingsPageScreen(vm: MainViewModel, page: Route.Page) {
    ScreenScaffold(title = page.title, onBack = { vm.back() }) { padding ->
        Column(
            Modifier
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 8.dp)
        ) {
            when (page) {
                Route.Page.PROJECTS -> ProjectsSettings(vm)
                Route.Page.NOTIFICATIONS -> NotificationSettings(vm)
                Route.Page.QUIET_HOURS -> QuietHoursSettings(vm)
                Route.Page.REMINDER_DEFAULTS -> ReminderDefaults(vm)
                Route.Page.RELIABILITY -> ReliabilitySettings(vm)
                Route.Page.BACKUP -> BackupSettings(vm)
                Route.Page.APPEARANCE -> AppearanceSettings(vm)
                Route.Page.PRIVACY -> PrivacySettings(vm)
                Route.Page.ABOUT -> AboutSettings()
            }
            Spacer(Modifier.size(48.dp))
        }
    }
}

@Composable
private fun Para(text: String) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 6.dp))
}

@Composable
private fun ProjectsSettings(vm: MainViewModel) {
    val snap by vm.snapshot.collectAsStateWithLifecycle()
    var confirmDemo by remember { mutableStateOf(false) }
    ListHeading("Active", inset = 0.dp)
    snap.activeProjects.forEach { p ->
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Text(p.name + if (p.isDemo) " (DEMO)" else "", modifier = Modifier.weight(1f).clickable { vm.navigate(Route.Project(p.id)) }.padding(vertical = 12.dp))
            TextButton(onClick = { vm.setArchived(p.id, true) }) { Text("Archive") }
        }
    }
    if (snap.archivedProjects.isNotEmpty()) {
        ListHeading("Archived", inset = 0.dp)
        snap.archivedProjects.forEach { p ->
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Text(p.name, modifier = Modifier.weight(1f).padding(vertical = 12.dp))
                TextButton(onClick = { vm.setArchived(p.id, false) }) { Text("Restore") }
                TextButton(onClick = { vm.deleteProject(p.id) }) { Text("Delete") }
            }
        }
    }
    if (snap.projects.any { it.isDemo }) {
        Spacer(Modifier.size(16.dp))
        OutlinedButton(onClick = { confirmDemo = true }) { Text("Remove demo data") }
    }
    Para("Deleted projects, sections and tasks stay in the trash for 30 days (so Undo always works) and are then removed permanently.")
    if (confirmDemo) {
        ConfirmDialog("Remove demo data?", "Only projects marked DEMO are removed.", "Remove", onConfirm = {
            snap.projects.filter { it.isDemo }.forEach { vm.deleteProject(it.id) }
        }, onDismiss = { confirmDemo = false })
    }
}

@Composable
private fun NotificationSettings(vm: MainViewModel) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val permission = LocalNotificationPermission.current
    var canPost by remember { mutableStateOf(Notifications.canPost(context)) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { canPost = Notifications.canPost(context) }

    SwitchRow("Reminder notifications", "Show a notification when a reminder is due.", settings.notificationsEnabled) { on ->
        if (on) permission.request { canPost = Notifications.canPost(context) } else vm.set(SettingKeys.NOTIFICATIONS_ENABLED, false)
    }
    StatusLine("Android permission", if (canPost) "Allowed" else "NOT ALLOWED: notifications can't appear", canPost)
    if (!canPost) {
        Button(onClick = { openAppNotificationSettings(context) }) { Text("Open notification settings") }
    }
    SwitchRow(
        "Show task details on lock screen",
        if (settings.lockScreenDetails) "Lock screen shows project and task names."
        else "Lock screen shows only “Project Steps: You have a scheduled project task.”",
        settings.lockScreenDetails,
    ) { vm.set(SettingKeys.LOCK_SCREEN_DETAILS, it) }
    TextButton(onClick = { vm.navigate(Route.SettingsPage(Route.Page.RELIABILITY)) }) { Text("Reminder reliability on Samsung →") }
}

@Composable
private fun StatusLine(label: String, value: String, ok: Boolean?) {
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Text(label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
        Text(
            (when (ok) { true -> "✓ "; false -> "✗ "; null -> "" }) + value,
            fontWeight = FontWeight.SemiBold,
            color = when (ok) { true -> MaterialTheme.colorScheme.primary; false -> MaterialTheme.colorScheme.error; null -> MaterialTheme.colorScheme.onSurface },
            modifier = Modifier.weight(1.2f),
        )
    }
}

@Composable
private fun QuietHoursSettings(vm: MainViewModel) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    var pickStart by remember { mutableStateOf(false) }
    var pickEnd by remember { mutableStateOf(false) }
    val q = settings.quietHours
    SwitchRow("Quiet hours", "Reminders due during quiet hours are shown when quiet hours end.", q.enabled) { vm.set(SettingKeys.QUIET_ENABLED, it) }
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        OutlinedButton(onClick = { pickStart = true }, enabled = q.enabled) { Text("From " + Format.time(q.start)) }
        OutlinedButton(onClick = { pickEnd = true }, enabled = q.enabled) { Text("Until " + Format.time(q.end)) }
    }
    if (pickStart) TimePickerDialog(q.start, onConfirm = { vm.set(SettingKeys.QUIET_START, it) }, onDismiss = { pickStart = false }, title = "Quiet hours start")
    if (pickEnd) TimePickerDialog(q.end, onConfirm = { vm.set(SettingKeys.QUIET_END, it) }, onDismiss = { pickEnd = false }, title = "Quiet hours end")
}

@Composable
private fun ReminderDefaults(vm: MainViewModel) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    var pickTime by remember { mutableStateOf(false) }
    var customSnooze by remember { mutableStateOf(false) }
    Text("Default reminder time", style = MaterialTheme.typography.titleMedium)
    OutlinedButton(onClick = { pickTime = true }) { Text(Format.time(settings.defaultReminderTime)) }
    Spacer(Modifier.size(16.dp))
    Text("Snooze length (notification button)", style = MaterialTheme.typography.titleMedium)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf(5, 10, 15, 30, 60).forEach { m ->
            FilterChip(selected = settings.snoozeMinutes == m, onClick = { vm.set(SettingKeys.SNOOZE_MINUTES, m) }, label = { Text("$m min") })
        }
        FilterChip(
            selected = settings.snoozeMinutes !in listOf(5, 10, 15, 30, 60),
            onClick = { customSnooze = true },
            label = { Text(if (settings.snoozeMinutes in listOf(5, 10, 15, 30, 60)) "Custom…" else "${settings.snoozeMinutes} min") },
        )
    }
    Spacer(Modifier.size(16.dp))
    Text("“Repeat until completed” interval", style = MaterialTheme.typography.titleMedium)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf(15, 30, 60, 120, 240).forEach { m ->
            FilterChip(selected = settings.nagMinutes == m, onClick = { vm.set(SettingKeys.NAG_MINUTES, m) }, label = { Text(if (m < 60) "$m min" else "${m / 60} h") })
        }
    }
    Para("Defaults apply to new reminders. Each reminder can still be edited individually.")
    if (pickTime) TimePickerDialog(settings.defaultReminderTime, onConfirm = { vm.set(SettingKeys.DEFAULT_REMINDER_TIME, it) }, onDismiss = { pickTime = false })
    if (customSnooze) {
        TextInputDialog("Snooze length in minutes", settings.snoozeMinutes.toString(), "Minutes", onConfirm = { v ->
            v.trim().toIntOrNull()?.takeIf { it in 1..1440 }?.let { vm.set(SettingKeys.SNOOZE_MINUTES, it) }
        }, onDismiss = { customSnooze = false })
    }
}

private fun openAppNotificationSettings(context: android.content.Context) {
    runCatching {
        context.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName))
    }
}

private fun openAppDetails(context: android.content.Context) {
    runCatching { context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))) }
}

@Composable
private fun ReliabilitySettings(vm: MainViewModel) {
    val context = LocalContext.current
    var tick by remember { mutableIntStateOf(0) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { tick++ }
    val settings by vm.settings.collectAsStateWithLifecycle()
    val snap by vm.snapshot.collectAsStateWithLifecycle()

    // Re-read every time the screen resumes (e.g. after returning from system settings).
    val canPost = remember(tick) { Notifications.canPost(context) }
    val exact = remember(tick) { vm.canScheduleExact() }
    val unrestricted = remember(tick) {
        context.getSystemService(PowerManager::class.java)?.isIgnoringBatteryOptimizations(context.packageName) == true
    }
    val restricted = remember(tick) { context.getSystemService(ActivityManager::class.java)?.isBackgroundRestricted == true }
    val isSamsung = Build.MANUFACTURER.equals("samsung", ignoreCase = true)

    Para("Android, and Samsung's One UI in particular, can delay or block background work to save battery. Project Steps never changes these settings for you. Here's what affects reminders on this phone.")
    StatusLine("App reminders", if (settings.notificationsEnabled) "On" else "Off", settings.notificationsEnabled)
    StatusLine("Notification permission", if (canPost) "Allowed" else "Not allowed", canPost)
    if (!canPost) OutlinedButton(onClick = { openAppNotificationSettings(context) }) { Text("Open notification settings") }

    StatusLine(
        "Exact alarms (precise reminders)",
        if (Build.VERSION.SDK_INT < 31) "Not required" else if (exact) "Allowed" else "Not allowed (precise reminders fall back to approximate)",
        if (Build.VERSION.SDK_INT < 31) null else exact,
    )
    if (Build.VERSION.SDK_INT >= 31 && !exact) {
        OutlinedButton(onClick = {
            runCatching { context.startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:${context.packageName}"))) }
        }) { Text("Open Alarms & reminders setting") }
    }

    StatusLine(
        "Battery optimization",
        if (unrestricted) "Not optimized (reminders least likely to be delayed)" else "Optimized: reminders may be delayed",
        unrestricted,
    )
    StatusLine("Background restriction", if (restricted) "RESTRICTED: reminders may not run" else "Not restricted", !restricted)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = { openAppDetails(context) }) { Text("Open app info") }
        OutlinedButton(onClick = { runCatching { context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) } }) { Text("Battery optimization list") }
    }

    if (isSamsung) {
        ListHeading("Samsung (One UI) checklist", inset = 0.dp)
        Para("1. App info › Battery › choose Unrestricted.")
        Para("2. Settings › Battery › Background usage limits: make sure Project Steps is NOT in “Sleeping apps” or “Deep sleeping apps”. Optionally add it to “Never sleeping apps”.")
        Para("3. Settings › Notifications › App notifications: Project Steps on; “Task reminders” category on.")
        Para("4. If reminders must be on the minute, use “Precise reminder” and allow Alarms & reminders.")
    }

    ListHeading("What to expect", inset = 0.dp)
    Para("App open or in the background, screen on or off: reminders are delivered. Normal reminders may arrive a few minutes late; precise ones are on time when exact alarms are allowed.")
    Para("Force stop: Android cancels ALL of an app's scheduled alarms and jobs after a manual Force Stop, and no app can override that. Reminders resume (missed ones from the last 24 hours are shown) the next time you open Project Steps.")
    Para("Reboot, clock or time-zone change: schedules are rebuilt automatically. Daylight-saving changes keep reminders at the same wall-clock time.")
    Para("No app can guarantee delivery: the phone's power management has the final say.")

    ListHeading("Test", inset = 0.dp)
    OutlinedButton(onClick = {
        val ok = Notifications.show(
            context,
            Notifications.Content(
                reminderId = "test-notification", projectName = "Project Steps", taskTitle = "Test notification",
                sectionName = null, dueText = "This is only a test", taskId = null, projectId = null,
                snoozeMinutes = settings.snoozeMinutes, showDetailsOnLockScreen = settings.lockScreenDetails,
            ),
        )
        vm.say(if (ok) "Test notification sent" else "Notifications are blocked; nothing could be shown.")
    }) { Text("Show a test notification now") }
    val scheduled = snap.reminders.count { it.enabled && it.nextFireAt != null }
    Para("$scheduled reminder(s) currently scheduled.")
    TextButton(onClick = { vm.rescheduleAll(); vm.say("All reminders rescheduled") }) { Text("Rebuild all schedules now") }
}

@Composable
private fun BackupSettings(vm: MainViewModel) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var pending by remember { mutableStateOf<BackupData?>(null) }
    var confirmReplace by remember { mutableStateOf(false) }

    val jsonLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(BackupRepository.Format.JSON.mime)) { uri ->
        if (uri != null) vm.export(uri, BackupRepository.Format.JSON)
    }
    val mdLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(BackupRepository.Format.MARKDOWN.mime)) { uri ->
        if (uri != null) vm.export(uri, BackupRepository.Format.MARKDOWN)
    }
    val zipLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(BackupRepository.Format.ZIP.mime)) { uri ->
        if (uri != null) vm.export(uri, BackupRepository.Format.ZIP)
    }
    val restoreLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            vm.readBackup(uri).fold(onSuccess = { pending = it }, onFailure = { vm.say("Can't restore: ${it.message ?: it.javaClass.simpleName}") })
        }
    }
    fun export(format: BackupRepository.Format) {
        val stamp = java.time.LocalDateTime.now().toString().take(16).replace(':', '-').replace('T', '_')
        val name = "ProjectSteps-$stamp.${format.extension}"
        when (format) {
            BackupRepository.Format.JSON -> jsonLauncher.launch(name)
            BackupRepository.Format.MARKDOWN -> mdLauncher.launch(name)
            BackupRepository.Format.ZIP -> zipLauncher.launch(name)
        }
    }

    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text("MANUAL BACKUP", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
            Text("Save a copy anywhere Android's file picker offers, including Google Drive. This is a manual copy, not automatic sync. Nothing is uploaded unless you choose a cloud location.")
            Text(
                "Last backup: " + (settings.lastBackupAt?.let { Format.dateTime(Instant.ofEpochMilli(it), ZoneId.systemDefault()) } ?: "never"),
                style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 8.dp),
            )
        }
    }
    ListHeading("Export", inset = 0.dp)
    BackupRepository.Format.entries.forEach { f ->
        OutlinedButton(onClick = { export(f) }, modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp).heightIn(min = 48.dp)) { Text(f.label) }
    }
    ListHeading("Restore", inset = 0.dp)
    Para("Choose a .json backup or a .zip export. You'll see what's in it before anything changes.")
    Button(onClick = { restoreLauncher.launch(arrayOf("application/json", "application/zip", "application/octet-stream", "text/plain")) }) { Text("Choose backup file…") }

    pending?.let { b ->
        AlertDialog(
            onDismissRequest = { pending = null },
            title = { Text("Restore backup?") },
            text = {
                Column {
                    Text("Made: " + Format.dateTime(Instant.ofEpochMilli(b.exportedAt), ZoneId.systemDefault()) + " (app ${b.appVersion})")
                    Text("${b.projects.size} projects · ${b.tasks.count { it.kind == "TASK" }} tasks · ${b.reminders.size} reminders")
                    Spacer(Modifier.size(8.dp))
                    Text("Merge: adds what's missing and keeps the newest version of items that exist in both. Nothing is deleted.")
                    Text("Replace: deletes ALL current data and replaces it with the backup.")
                }
            },
            confirmButton = { TextButton(onClick = { vm.restore(b, replace = false); pending = null }) { Text("Merge") } },
            dismissButton = {
                Row {
                    TextButton(onClick = { confirmReplace = true }) { Text("Replace…") }
                    TextButton(onClick = { pending = null }) { Text("Cancel") }
                }
            },
        )
    }
    if (confirmReplace) {
        val b = pending
        AlertDialog(
            onDismissRequest = { confirmReplace = false },
            title = { Text("Replace all data?") },
            text = {
                Text(
                    "This deletes your current projects, tasks and reminders and replaces them with the backup. " +
                        "A safety copy of the current data is saved inside the app automatically. You can also save your own copy first."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmReplace = false
                    pending = null
                    if (b != null) vm.restore(b, replace = true)
                }) { Text("Replace") }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = { export(BackupRepository.Format.JSON) }) { Text("Save copy first") }
                    TextButton(onClick = { confirmReplace = false }) { Text("Cancel") }
                }
            },
        )
    }
}

@Composable
private fun AppearanceSettings(vm: MainViewModel) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    Text("Theme", style = MaterialTheme.typography.titleMedium)
    ThemeMode.entries.forEach { m ->
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).selectable(selected = settings.theme == m, onClick = { vm.setTheme(m) }, role = Role.RadioButton),
        ) {
            RadioButton(selected = settings.theme == m, onClick = null)
            Text(m.label, modifier = Modifier.padding(start = 8.dp))
        }
    }
    SwitchRow("AMOLED black", "Pure black background in dark mode (saves power on OLED screens). Cards stay slightly raised for contrast.", settings.amoled) {
        vm.set(SettingKeys.AMOLED, it)
    }
    if (Build.VERSION.SDK_INT >= 31) {
        SwitchRow("Use wallpaper colours", "Material You colours from your wallpaper.", settings.dynamicColor) { vm.set(SettingKeys.DYNAMIC_COLOR, it) }
    }
    SwitchRow("Show completed tasks", "In project checklists.", settings.showCompleted) { vm.set(SettingKeys.SHOW_COMPLETED, it) }
    Para("Text size follows the system font size (Settings › Display › Font size and style).")
}

@Composable
private fun PrivacySettings(vm: MainViewModel) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    SwitchRow(
        "Show task details on lock screen",
        "When off, the lock screen only says “You have a scheduled project task.”",
        settings.lockScreenDetails,
    ) { vm.set(SettingKeys.LOCK_SCREEN_DETAILS, it) }
    ListHeading("How your data is handled", inset = 0.dp)
    Para("• Works fully offline. The app has no Internet permission, so it cannot send anything anywhere.")
    Para("• No account, no analytics, no ads, no AI services.")
    Para("• Imported documents are read as data only. Commands or instructions inside them are never run.")
    Para("• Files are only read or written through Android's file picker, one file at a time, when you choose. No broad storage access.")
    Para("• Android cloud backup and device-to-device transfer are disabled for app data. Use Backup & Restore to make your own copies.")
}

@Composable
private fun AboutSettings() {
    Text("Project Steps", style = MaterialTheme.typography.headlineSmall)
    Para("Version ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}) · ${if (BuildConfig.DEBUG) "debug build" else "release build"}")
    Para("Turns your project checklist documents into interactive checklists with reminders.")
    ListHeading("Safety", inset = 0.dp)
    Para("Project Steps manages project work; it does not perform it. It never executes shell, ADB, server, device-control or network commands, including any written in your documents. Flags such as NEEDS APPROVAL and DESTRUCTIVE ACTION are informational only.")
    ListHeading("Updates", inset = 0.dp)
    Para("Android only installs an update over this app if it is signed with the same key. Debug builds use your computer's debug key: keep that key (or create a proper release key once) and don't regenerate it, or you'll have to uninstall (and lose data unless backed up) before updating.")
}
