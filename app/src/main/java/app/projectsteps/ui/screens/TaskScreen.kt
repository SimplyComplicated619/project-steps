@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package app.projectsteps.ui.screens

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
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
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.projectsteps.core.model.Priority
import app.projectsteps.core.model.TaskFlag
import app.projectsteps.core.model.TaskStatus
import app.projectsteps.core.schedule.Recurrence
import app.projectsteps.data.TaskEntity
import app.projectsteps.data.isDone
import app.projectsteps.data.isNote
import app.projectsteps.data.toSpec
import app.projectsteps.ui.Format
import app.projectsteps.ui.MainViewModel
import app.projectsteps.ui.Route
import app.projectsteps.ui.components.ConfirmDialog
import app.projectsteps.ui.components.DatePickerDialogM3
import app.projectsteps.ui.components.EmptyState
import app.projectsteps.ui.components.FlagLabels
import app.projectsteps.ui.components.ScreenScaffold
import app.projectsteps.ui.components.TextInputDialog
import app.projectsteps.ui.components.TimePickerDialog
import app.projectsteps.ui.components.dueInfo
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

private data class TaskDraft(
    val title: String,
    val description: String,
    val notes: String,
    val waitingOn: String,
    val flags: Set<TaskFlag>,
    val priority: Int,
    val dueDate: String?,
    val dueTime: String?,
) {
    companion object {
        fun of(t: TaskEntity) = TaskDraft(t.title, t.description, t.notes, t.waitingOn ?: "", TaskFlag.decode(t.flags), t.priority, t.dueDate, t.dueTime)
    }

    fun applyTo(t: TaskEntity) = t.copy(
        title = title.trim().ifBlank { t.title }, description = description, notes = notes,
        waitingOn = waitingOn.trim().ifBlank { null }, flags = TaskFlag.encode(flags), priority = priority,
        dueDate = dueDate, dueTime = if (dueDate == null) null else dueTime,
    )
}

@Composable
fun TaskScreen(vm: MainViewModel, taskId: String) {
    val snap by vm.snapshot.collectAsStateWithLifecycle()
    val now by vm.now.collectAsStateWithLifecycle()
    val zone = ZoneId.systemDefault()
    val context = LocalContext.current
    val task = snap.taskById[taskId]

    if (task == null) {
        ScreenScaffold("Task", onBack = { vm.back() }) {
            if (snap.loaded) EmptyState("Task not found", "It may have been deleted.", Modifier.padding(it))
        }
        return
    }

    var draft by remember(taskId) { mutableStateOf(TaskDraft.of(task)) }
    val dirty = draft != TaskDraft.of(task)
    val latestDraft by rememberUpdatedState(draft)
    val latestDirty by rememberUpdatedState(dirty)
    // Edits are saved automatically when leaving the screen (never resurrects a deleted task).
    DisposableEffect(taskId) {
        onDispose {
            if (latestDirty) {
                val d = latestDraft
                vm.editTask(taskId) { d.applyTo(it) }
            }
        }
    }

    var pickDate by remember { mutableStateOf(false) }
    var pickTime by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var addDependency by remember { mutableStateOf(false) }
    var addSubtask by remember { mutableStateOf(false) }
    var customSnooze by remember { mutableStateOf(false) }

    val project = snap.projectById[task.projectId]
    val section = snap.sectionById[task.sectionId]
    val reminders = snap.remindersByTask[taskId].orEmpty()
    val children = snap.childrenOf[taskId].orEmpty()
    val historyFlow = remember(taskId) { vm.history(taskId) }
    val history by (historyFlow).collectAsStateWithLifecycle(initialValue = emptyList())
    val isNote = task.isNote

    ScreenScaffold(
        title = if (isNote) "Note" else "Task",
        subtitle = listOfNotNull(project?.name, section?.title).joinToString(" › "),
        onBack = { vm.back() },
        actions = {
            if (dirty) TextButton(onClick = { val d = draft; vm.editTask(taskId) { d.applyTo(it) }; vm.say("Saved") }) { Text("Save") }
            IconButton(onClick = { confirmDelete = true }) { Icon(Icons.Filled.Delete, contentDescription = "Delete") }
        },
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (!isNote) {
                    Checkbox(checked = task.isDone, onCheckedChange = { vm.setDone(taskId, it) })
                }
                OutlinedTextField(
                    value = draft.title, onValueChange = { draft = draft.copy(title = it) },
                    label = { Text("Title") }, modifier = Modifier.weight(1f),
                )
            }
            if (!isNote) {
                dueInfo(task, snap, now, zone)?.let { Text(it.text, color = it.color, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 6.dp)) }
                Spacer(Modifier.size(4.dp))
                FlagLabels(snap.flags(task), task.waitingOn, taskId in snap.blockedByDeps)

                SubHeading("Status")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TaskStatus.entries.forEach { st ->
                        FilterChip(selected = task.status == st.name, onClick = { vm.setStatus(taskId, st) }, label = { Text(st.label) })
                    }
                }
                if (snap.isRecurring(taskId)) {
                    Text(
                        "This task repeats. Completing it completes the current occurrence only; the next one stays scheduled.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                SubHeading("Priority")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Priority.entries.forEach { p ->
                        FilterChip(selected = draft.priority == p.rank, onClick = { draft = draft.copy(priority = p.rank) }, label = { Text(p.label) })
                    }
                }

                SubHeading("Due")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { pickDate = true }, modifier = Modifier.heightIn(min = 48.dp)) {
                        Text(draft.dueDate?.let { runCatching { Format.date(LocalDate.parse(it)) }.getOrDefault(it) } ?: "Set date")
                    }
                    if (draft.dueDate != null) {
                        OutlinedButton(onClick = { pickTime = true }, modifier = Modifier.heightIn(min = 48.dp)) {
                            Text(draft.dueTime?.let { runCatching { Format.time(LocalTime.parse(it)) }.getOrDefault(it) } ?: "Any time")
                        }
                        TextButton(onClick = { draft = draft.copy(dueDate = null, dueTime = null) }) { Text("Clear") }
                    }
                }

                SubHeading("Flags (informational only)")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TaskFlag.entries.forEach { f ->
                        FilterChip(
                            selected = f in draft.flags,
                            onClick = { draft = draft.copy(flags = if (f in draft.flags) draft.flags - f else draft.flags + f) },
                            label = { Text(f.label) },
                        )
                    }
                }
                if (TaskFlag.WAITING_ON in draft.flags) {
                    OutlinedTextField(draft.waitingOn, { draft = draft.copy(waitingOn = it) }, label = { Text("Waiting on") }, modifier = Modifier.fillMaxWidth())
                }
                Text(
                    "Project Steps never runs commands or performs the work. Flags only change how a task is shown.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            SubHeading("Description")
            OutlinedTextField(draft.description, { draft = draft.copy(description = it) }, minLines = 2, modifier = Modifier.fillMaxWidth())

            SubHeading("My notes")
            OutlinedTextField(
                draft.notes, { draft = draft.copy(notes = it) }, minLines = 3, modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("Personal notes. Kept when documents are re-imported.") },
            )

            val links = task.links.split('\n').filter { it.isNotBlank() }
            if (links.isNotEmpty()) {
                SubHeading("Links")
                links.forEach { url ->
                    Column(Modifier.padding(vertical = 4.dp)) {
                        Text(url, style = MaterialTheme.typography.bodySmall)
                        Row {
                            TextButton(onClick = {
                                try {
                                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                                } catch (e: ActivityNotFoundException) {
                                    vm.say("No app can open this link.")
                                }
                            }) { Text("Open in browser") }
                            TextButton(onClick = {
                                context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("link", url))
                                vm.say("Link copied")
                            }) { Text("Copy") }
                        }
                    }
                }
            }

            if (!isNote) {
                SubHeading("Reminders")
                reminders.forEach { r ->
                    Card(
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    ) {
                        Row(Modifier.padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(
                                Modifier.weight(1f).clickable { vm.navigate(Route.ReminderEdit(taskId, task.projectId, r.id)) }.padding(vertical = 12.dp)
                            ) {
                                Text(Recurrence.describe(r.toSpec()), style = MaterialTheme.typography.bodyMedium)
                                val next = r.nextFireAt?.let { "Next: " + Format.dateTime(Instant.ofEpochMilli(it), zone) } ?: if (r.enabled) "No upcoming time" else "Off"
                                Text(
                                    next + (if (r.precise) " · precise" else "") + (r.zoneId?.let { " · $it" } ?: ""),
                                    style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Switch(checked = r.enabled, onCheckedChange = { vm.setReminderEnabled(r.id, it) }, modifier = Modifier.padding(end = 8.dp))
                        }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { vm.navigate(Route.ReminderEdit(taskId, task.projectId, null)) }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Add reminder") }
                }
                if (reminders.any { it.enabled }) {
                    Text("Snooze", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 8.dp))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(10 to "10 min", 60 to "1 hour", 180 to "3 hours", 24 * 60 to "1 day").forEach { (m, label) ->
                            OutlinedButton(onClick = { vm.snoozeTask(taskId, m) }) { Text(label) }
                        }
                        OutlinedButton(onClick = { customSnooze = true }) { Text("Custom…") }
                    }
                }

                SubHeading("Depends on")
                val deps = snap.dependsOn[taskId].orEmpty().mapNotNull { snap.taskById[it] }
                if (deps.isEmpty()) Text("No dependencies.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                deps.forEach { d ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            (if (d.isDone) "✓ " else "○ ") + d.title,
                            modifier = Modifier.weight(1f).clickable { vm.navigate(Route.Task(d.id)) }.padding(vertical = 10.dp),
                        )
                        TextButton(onClick = { vm.removeDependency(taskId, d.id) }) { Text("Remove") }
                    }
                }
                TextButton(onClick = { addDependency = true }) { Text("+ Add dependency") }

                SubHeading("Subtasks")
                children.filter { !it.isNote }.forEach { c ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = c.isDone, onCheckedChange = { vm.setDone(c.id, it) })
                        Text(c.title, modifier = Modifier.weight(1f).clickable { vm.navigate(Route.Task(c.id)) }.padding(vertical = 10.dp))
                    }
                }
                TextButton(onClick = { addSubtask = true }) { Text("+ Add subtask") }
            }

            if (task.sourceFileName != null) {
                SubHeading("Source (read-only)")
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer), modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp)) {
                        val archive = project?.sourceArchiveName?.let { " in $it" } ?: ""
                        Text("File: ${task.sourceFileName}$archive", style = MaterialTheme.typography.bodySmall)
                        if (!task.sourceSectionPath.isNullOrBlank()) Text("Section: ${task.sourceSectionPath}", style = MaterialTheme.typography.bodySmall)
                        task.sourceBlockIndex?.let { Text("Paragraph: ${it + 1}", style = MaterialTheme.typography.bodySmall) }
                        task.importedAt?.let { Text("Imported: " + Format.dateTime(Instant.ofEpochMilli(it), zone), style = MaterialTheme.typography.bodySmall) }
                        if (task.sourceCompleted) Text("The document marked this as done.", style = MaterialTheme.typography.bodySmall)
                        if (task.sourceMissing) Text("NOT IN LATEST IMPORT: this item wasn't found in the most recent version of the document.", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
                        task.sourceText?.let {
                            Spacer(Modifier.size(6.dp))
                            HorizontalDivider()
                            SelectionContainer { Text(it, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace, modifier = Modifier.padding(top = 6.dp)) }
                        }
                    }
                }
            }

            if (history.isNotEmpty()) {
                SubHeading("History")
                history.take(20).forEach { h ->
                    val what = when (h.action) {
                        "COMPLETED" -> "Completed"
                        "REOPENED" -> "Marked not done"
                        "IMPORTED_DONE" -> "Imported as done"
                        else -> h.action
                    }
                    val occ = h.occurrenceAt?.let { " (occurrence " + Format.dateTime(Instant.ofEpochMilli(it), zone) + ")" } ?: ""
                    Text("$what · " + Format.dateTime(Instant.ofEpochMilli(h.completedAt), zone) + occ, style = MaterialTheme.typography.bodySmall)
                }
            }
            Spacer(Modifier.size(48.dp))
        }
    }

    if (pickDate) {
        DatePickerDialogM3(
            draft.dueDate?.let { runCatching { LocalDate.parse(it) }.getOrNull() },
            onConfirm = { d -> draft = draft.copy(dueDate = d?.toString(), dueTime = if (d == null) null else draft.dueTime) },
            onDismiss = { pickDate = false }, allowClear = true,
        )
    }
    if (pickTime) {
        TimePickerDialog(
            draft.dueTime?.let { runCatching { LocalTime.parse(it) }.getOrNull() } ?: LocalTime.of(17, 0),
            onConfirm = { draft = draft.copy(dueTime = it.toString()) }, onDismiss = { pickTime = false },
        )
    }
    if (confirmDelete) {
        ConfirmDialog(
            "Delete this ${if (isNote) "note" else "task"}?",
            "Subtasks and reminders are removed with it. You can undo right after.",
            "Delete", onConfirm = { vm.deleteTask(taskId); vm.back() }, onDismiss = { confirmDelete = false },
        )
    }
    if (customSnooze) {
        TextInputDialog("Snooze for how many minutes?", "30", "Minutes", onConfirm = { v ->
            v.trim().toIntOrNull()?.takeIf { it in 1..(14 * 24 * 60) }?.let { vm.snoozeTask(taskId, it) } ?: vm.say("Enter a number of minutes.")
        }, onDismiss = { customSnooze = false }, confirmLabel = "Snooze")
    }
    if (addSubtask) {
        TextInputDialog("Add subtask", "", "Subtask", onConfirm = { vm.addTask(task.projectId, task.sectionId, it, taskId) }, onDismiss = { addSubtask = false }, confirmLabel = "Add")
    }
    if (addDependency) {
        val candidates = snap.tasksByProject[task.projectId].orEmpty()
            .filter { !it.isNote && it.id != taskId && it.id !in snap.dependsOn[taskId].orEmpty() }
            .sortedWith(compareBy({ snap.sectionById[it.sectionId]?.position ?: 0.0 }, { it.position }))
        AlertDialog(
            onDismissRequest = { addDependency = false },
            title = { Text("This task depends on…") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    if (candidates.isEmpty()) Text("No other tasks in this project.")
                    candidates.forEach { c ->
                        Text(
                            (if (c.isDone) "✓ " else "") + c.title,
                            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable { addDependency = false; vm.addDependency(taskId, c.id) }.padding(vertical = 12.dp),
                        )
                    }
                }
            },
            confirmButton = { TextButton(onClick = { addDependency = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun SubHeading(text: String) {
    Text(
        text.uppercase(),
        style = MaterialTheme.typography.labelLarge,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 20.dp, bottom = 6.dp),
    )
}
