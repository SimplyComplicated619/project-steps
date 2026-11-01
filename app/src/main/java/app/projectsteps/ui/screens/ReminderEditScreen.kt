@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package app.projectsteps.ui.screens

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.projectsteps.core.schedule.QuietHours
import app.projectsteps.core.schedule.RecurrenceRule
import app.projectsteps.core.schedule.ReminderPlanner
import app.projectsteps.core.schedule.ReminderRuntime
import app.projectsteps.core.schedule.ReminderSpec
import app.projectsteps.core.schedule.RepeatType
import app.projectsteps.core.schedule.RepeatUnit
import app.projectsteps.data.ReminderEntity
import app.projectsteps.data.newId
import app.projectsteps.data.toSpec
import app.projectsteps.reminders.Notifications
import app.projectsteps.ui.Format
import app.projectsteps.ui.LocalNotificationPermission
import app.projectsteps.ui.MainViewModel
import app.projectsteps.ui.Route
import app.projectsteps.ui.components.ConfirmDialog
import app.projectsteps.ui.components.DatePickerDialogM3
import app.projectsteps.ui.components.ScreenScaffold
import app.projectsteps.ui.components.TimePickerDialog
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

@Composable
fun ReminderEditScreen(vm: MainViewModel, route: Route.ReminderEdit) {
    val snap by vm.snapshot.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val permission = LocalNotificationPermission.current
    val deviceZone = ZoneId.systemDefault()

    val existing = route.reminderId?.let { id -> snap.reminders.firstOrNull { it.id == id } }
    val task = route.taskId?.let { snap.taskById[it] }
    val projectId = task?.projectId ?: route.projectId
    val project = projectId?.let { snap.projectById[it] }
    val spec0 = existing?.toSpec()

    val defaultDate = remember {
        task?.dueDate?.let { runCatching { LocalDate.parse(it) }.getOrNull() }?.takeIf { !it.isBefore(LocalDate.now()) }
            ?: if (LocalTime.now().isBefore(settings.defaultReminderTime)) LocalDate.now() else LocalDate.now().plusDays(1)
    }
    var date by remember { mutableStateOf(spec0?.startDate ?: defaultDate) }
    var time by remember { mutableStateOf(spec0?.time ?: task?.dueTime?.let { runCatching { LocalTime.parse(it) }.getOrNull() } ?: settings.defaultReminderTime) }
    var type by remember { mutableStateOf(spec0?.rule?.type ?: RepeatType.NONE) }
    var weekdays by remember { mutableStateOf(spec0?.rule?.weekdays ?: setOf(date.dayOfWeek)) }
    var intervalText by remember { mutableStateOf((spec0?.rule?.interval ?: 2).toString()) }
    var unit by remember { mutableStateOf(spec0?.rule?.unit ?: RepeatUnit.DAYS) }
    var endDate by remember { mutableStateOf(spec0?.rule?.endDate) }
    var nag by remember { mutableStateOf(spec0?.repeatUntilCompleted ?: false) }
    var nagText by remember { mutableStateOf((spec0?.nagIntervalMinutes ?: settings.nagMinutes).toString()) }
    var fixedZone by remember { mutableStateOf(spec0?.zoneId != null) }
    var zoneText by remember { mutableStateOf(spec0?.zoneId ?: deviceZone.id) }
    var precise by remember { mutableStateOf(spec0?.precise ?: false) }

    var pickDate by remember { mutableStateOf(false) }
    var pickEnd by remember { mutableStateOf(false) }
    var pickTime by remember { mutableStateOf(false) }
    var explainExact by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }

    val zoneValid = !fixedZone || runCatching { ZoneId.of(zoneText.trim()) }.isSuccess
    val interval = intervalText.toIntOrNull()?.coerceIn(1, 365) ?: 1
    val nagMinutes = nagText.toIntOrNull()?.coerceIn(5, 24 * 60) ?: 60
    val spec = ReminderSpec(
        startDate = date,
        time = time,
        zoneId = if (fixedZone && zoneValid) zoneText.trim() else null,
        rule = RecurrenceRule(
            type = type,
            interval = if (type == RepeatType.CUSTOM) interval else 1,
            unit = unit,
            weekdays = if (type == RepeatType.WEEKDAYS || (type == RepeatType.CUSTOM && unit == RepeatUnit.WEEKS)) weekdays else emptySet(),
            endDate = if (type == RepeatType.NONE) null else endDate,
        ),
        repeatUntilCompleted = nag,
        nagIntervalMinutes = nagMinutes,
        precise = precise,
    )
    val now = Instant.now()
    val preview = ReminderPlanner.nextTrigger(spec, ReminderRuntime(armedAt = now), now, deviceZone, settings.quietHours)
    val rawNext = ReminderPlanner.nextTrigger(spec, ReminderRuntime(armedAt = now), now, deviceZone, QuietHours.OFF)
    val inPast = type == RepeatType.NONE && preview == null

    fun save() {
        val nowMs = System.currentTimeMillis()
        val entity = ReminderEntity(
            id = existing?.id ?: newId(),
            taskId = route.taskId,
            projectId = projectId,
            startDate = date.toString(),
            time = time.withSecond(0).withNano(0).toString(),
            zoneId = spec.zoneId,
            repeatType = type.name,
            interval = spec.rule.interval,
            unit = unit.name,
            weekdaysMask = RecurrenceRule.weekdaysToMask(spec.rule.weekdays),
            endDate = spec.rule.endDate?.toString(),
            repeatUntilCompleted = nag,
            nagMinutes = nagMinutes,
            precise = precise,
            enabled = true,
            completedThrough = existing?.completedThrough,
            createdAt = existing?.createdAt ?: nowMs,
            updatedAt = nowMs,
        )
        if (!settings.notificationsEnabled || !Notifications.canPost(context)) {
            permission.request { _ ->
                vm.saveReminder(entity)
                vm.back()
            }
        } else {
            vm.saveReminder(entity)
            vm.back()
        }
    }

    ScreenScaffold(
        title = if (existing == null) "New reminder" else "Edit reminder",
        subtitle = task?.title ?: project?.name,
        onBack = { vm.back() },
        actions = { if (existing != null) TextButton(onClick = { confirmDelete = true }) { Text("Delete") } },
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp)
        ) {
            Text("When", style = MaterialTheme.typography.titleMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(vertical = 8.dp)) {
                OutlinedButton(onClick = { pickDate = true }, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text((if (type == RepeatType.NONE) "" else "Starts ") + Format.date(date))
                }
                OutlinedButton(onClick = { pickTime = true }, modifier = Modifier.heightIn(min = 48.dp)) { Text(Format.time(time)) }
            }

            Text("Repeat", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp))
            RepeatType.entries.forEach { rt ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).selectable(selected = type == rt, onClick = { type = rt }, role = Role.RadioButton),
                ) {
                    RadioButton(selected = type == rt, onClick = null)
                    Text(
                        when (rt) {
                            RepeatType.WEEKLY -> "Weekly (every ${date.dayOfWeek.name.lowercase().replaceFirstChar { it.uppercase() }})"
                            RepeatType.MONTHLY -> "Monthly (day ${date.dayOfMonth})"
                            else -> rt.label
                        },
                        modifier = Modifier.padding(start = 8.dp),
                    )
                }
            }
            if (type == RepeatType.CUSTOM) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Every")
                    OutlinedTextField(
                        intervalText, { intervalText = it.filter(Char::isDigit).take(3) }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.size(width = 90.dp, height = 60.dp),
                    )
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    RepeatUnit.entries.forEach { u -> FilterChip(selected = unit == u, onClick = { unit = u }, label = { Text(u.label) }) }
                }
            }
            if (type == RepeatType.WEEKDAYS || (type == RepeatType.CUSTOM && unit == RepeatUnit.WEEKS)) {
                Text("On", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 8.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    DayOfWeek.entries.forEach { d ->
                        FilterChip(
                            selected = d in weekdays,
                            onClick = { weekdays = if (d in weekdays && weekdays.size > 1) weekdays - d else weekdays + d },
                            label = { Text(d.name.take(3).lowercase().replaceFirstChar { it.uppercase() }) },
                        )
                    }
                }
            }
            if (type != RepeatType.NONE) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { pickEnd = true }) { Text("Ends: " + (endDate?.let { Format.date(it) } ?: "never")) }
                    if (endDate != null) TextButton(onClick = { endDate = null }) { Text("Clear") }
                }
            }

            SwitchRow(
                "Repeat until completed",
                "Keep reminding every $nagMinutes minutes after it's due, until you complete it.",
                nag,
            ) { nag = it }
            if (nag) {
                OutlinedTextField(
                    nagText, { nagText = it.filter(Char::isDigit).take(4) }, label = { Text("Minutes between reminders") },
                    singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth(),
                )
            }

            SwitchRow(
                "Fixed time zone",
                if (fixedZone) "Fires at this time in the zone below, even if you travel."
                else "Follows the phone's current time zone (${deviceZone.id}).",
                fixedZone,
            ) { fixedZone = it }
            if (fixedZone) {
                OutlinedTextField(
                    zoneText, { zoneText = it }, label = { Text("Time zone ID, e.g. America/New_York") }, singleLine = true,
                    isError = !zoneValid, supportingText = { if (!zoneValid) Text("Unknown time zone ID") },
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            SwitchRow(
                "Precise reminder",
                if (precise && !vm.canScheduleExact()) "Exact alarms aren't allowed yet. Until you allow them, this uses normal (approximate) scheduling."
                else "Uses an exact alarm so the notification appears on time even in battery saving. Off: Android may deliver it a few minutes late.",
                precise,
            ) { on ->
                precise = on
                if (on && !vm.canScheduleExact()) explainExact = true
            }

            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
                modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
            ) {
                Column(Modifier.padding(12.dp)) {
                    Text("Preview", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
                    Text(app.projectsteps.core.schedule.Recurrence.describe(spec))
                    when {
                        !zoneValid -> Text("Fix the time zone to see the next reminder.")
                        inPast -> Text("That time has already passed. Pick a later time.", color = MaterialTheme.colorScheme.error)
                        preview != null -> {
                            Text("Next: " + Format.dateTime(preview, deviceZone) + " (" + Format.relative(preview) + ")")
                            if (rawNext != null && rawNext != preview) Text("Moved out of quiet hours (originally ${Format.dateTime(rawNext, deviceZone)}).", style = MaterialTheme.typography.bodySmall)
                        }
                        else -> Text("No upcoming reminder (the series has ended).")
                    }
                    if (!settings.notificationsEnabled || !Notifications.canPost(context)) {
                        Text("Notifications are currently off. Saving will ask to turn them on.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                    }
                }
            }
            Button(onClick = { save() }, enabled = zoneValid && !inPast, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) { Text("Save reminder") }
            Spacer(Modifier.size(48.dp))
        }
    }

    if (pickDate) DatePickerDialogM3(date, onConfirm = { d -> if (d != null) { date = d; if (weekdays.isEmpty()) weekdays = setOf(d.dayOfWeek) } }, onDismiss = { pickDate = false })
    if (pickEnd) DatePickerDialogM3(endDate ?: date.plusMonths(1), onConfirm = { endDate = it }, onDismiss = { pickEnd = false }, allowClear = true)
    if (pickTime) TimePickerDialog(time, onConfirm = { time = it }, onDismiss = { pickTime = false })
    if (confirmDelete && existing != null) {
        ConfirmDialog("Delete reminder?", "The task itself is not affected.", "Delete", onConfirm = { vm.deleteReminder(existing.id); vm.back() }, onDismiss = { confirmDelete = false })
    }
    if (explainExact) {
        AlertDialog(
            onDismissRequest = { explainExact = false },
            title = { Text("Allow exact alarms?") },
            text = {
                Text(
                    "Android delays normal reminders to save battery. A precise reminder needs the \"Alarms & reminders\" permission, " +
                        "which you grant in system settings. If you don't, this reminder uses normal (approximate) scheduling."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    explainExact = false
                    if (Build.VERSION.SDK_INT >= 31) {
                        runCatching {
                            context.startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:${context.packageName}")))
                        }
                    }
                }) { Text("Open settings") }
            },
            dismissButton = { TextButton(onClick = { explainExact = false; precise = false }) { Text("Use approximate") } },
        )
    }
}

@Composable
fun SwitchRow(title: String, body: String?, checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .selectable(selected = checked, enabled = enabled, onClick = { onChange(!checked) }, role = Role.Switch)
            .padding(vertical = 8.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (body != null) Text(body, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = null, enabled = enabled, modifier = Modifier.padding(start = 12.dp))
    }
}
