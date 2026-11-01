@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class, ExperimentalLayoutApi::class)

package app.projectsteps.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.projectsteps.core.model.TaskFlag
import app.projectsteps.core.model.TaskStatus
import app.projectsteps.data.Progress
import app.projectsteps.data.SectionEntity
import app.projectsteps.data.Snapshot
import app.projectsteps.data.TaskEntity
import app.projectsteps.data.isDone
import app.projectsteps.ui.Format
import app.projectsteps.ui.theme.LocalStatusColors
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset

/** Small text label with a coloured outline. Colour is always paired with words. */
@Composable
fun Label(text: String, color: Color, modifier: Modifier = Modifier) {
    Surface(
        shape = RoundedCornerShape(6.dp),
        color = color.copy(alpha = 0.12f),
        border = BorderStroke(1.dp, color.copy(alpha = 0.6f)),
        modifier = modifier,
    ) {
        Text(
            text,
            color = color,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
            maxLines = 1,
        )
    }
}

@Composable
fun FlagLabels(flags: Set<TaskFlag>, waitingOn: String?, blockedByDeps: Boolean = false) {
    val c = LocalStatusColors.current
    if (flags.isEmpty() && !blockedByDeps) return
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (TaskFlag.NEEDS_APPROVAL in flags) Label("⚠ NEEDS APPROVAL", c.approval)
        if (TaskFlag.DESTRUCTIVE in flags) Label("⚠ DESTRUCTIVE ACTION", c.destructive)
        if (TaskFlag.WAITING_ON in flags) Label("WAITING ON" + (waitingOn?.let { ": " + if (it.length > 32) it.take(31).trimEnd() + "…" else it } ?: ""), c.waiting)
        if (TaskFlag.BLOCKED in flags) Label("BLOCKED", c.blocked)
        if (blockedByDeps) Label("BLOCKED BY STEP", c.blocked)
    }
}

data class DueInfo(val text: String, val color: Color, val overdue: Boolean)

@Composable
fun dueInfo(task: TaskEntity, snapshot: Snapshot, now: Instant, zone: ZoneId): DueInfo? {
    if (task.isDone && !snapshot.isRecurring(task.id)) return null
    val due = snapshot.due(task, now, zone) ?: return null
    val c = LocalStatusColors.current
    val today = LocalDate.now(zone)
    val dueDate = due.atZone(zone).toLocalDate()
    val hasTime = task.dueTime != null || snapshot.isRecurring(task.id)
    val whenText = if (hasTime) Format.dateTime(due, zone) else Format.date(dueDate, today)
    return when {
        due.isBefore(now) -> DueInfo("OVERDUE · $whenText", c.overdue, true)
        dueDate == today -> DueInfo("DUE TODAY · " + if (hasTime) Format.time(due, zone) else "today", c.dueToday, false)
        else -> DueInfo("Due $whenText", MaterialTheme.colorScheme.onSurfaceVariant, false)
    }
}

@Composable
fun ProgressHeader(progress: Progress, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(
            "${progress.done} / ${progress.total} completed · ${progress.percent}%",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.semantics { contentDescription = "${progress.done} of ${progress.total} tasks completed, ${progress.percent} percent" },
        )
        Spacer(Modifier.size(6.dp))
        LinearProgressIndicator(
            progress = { if (progress.total == 0) 0f else progress.done.toFloat() / progress.total },
            modifier = Modifier.fillMaxWidth().heightIn(min = 6.dp),
        )
    }
}

data class TaskMenuAction(val label: String, val onClick: () -> Unit)

/**
 * One checklist row: a 48dp checkbox, title, due/reminder line and flags. Every action is in the
 * ⋮ menu (also opened by long-press), so nothing depends on gestures alone.
 */
@Composable
fun TaskRow(
    task: TaskEntity,
    snapshot: Snapshot,
    now: Instant,
    zone: ZoneId,
    onToggle: (Boolean) -> Unit,
    onOpen: () -> Unit,
    menu: List<TaskMenuAction>,
    showProject: Boolean = false,
    indentLevel: Int = task.level,
) {
    var menuOpen by remember { mutableStateOf(false) }
    val done = task.isDone
    val recurring = snapshot.isRecurring(task.id)
    val blocked = snapshot.isBlocked(task)
    val flags = snapshot.flags(task)
    val due = dueInfo(task, snapshot, now, zone)
    val nextReminder = snapshot.nextReminderAt(task.id)
    val c = LocalStatusColors.current
    val stateText = when {
        done -> "completed"
        blocked -> "blocked"
        else -> "not completed"
    }

    Row(
        verticalAlignment = Alignment.Top,
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onOpen, onLongClick = { menuOpen = true }, onLongClickLabel = "More actions")
            .padding(start = (indentLevel.coerceIn(0, 5) * 20).dp, end = 4.dp, top = 2.dp, bottom = 2.dp),
    ) {
        Checkbox(
            checked = done,
            onCheckedChange = { onToggle(it) },
            modifier = Modifier.semantics {
                contentDescription = (if (done) "Mark not done: " else "Complete: ") + task.title
                stateDescription = stateText
            },
        )
        Column(
            Modifier
                .weight(1f)
                .padding(top = 12.dp, bottom = 8.dp)
        ) {
            if (showProject) {
                val p = snapshot.projectById[task.projectId]
                val s = snapshot.sectionById[task.sectionId]
                Text(
                    listOfNotNull(p?.name, s?.title).joinToString(" › "),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(
                task.title,
                style = MaterialTheme.typography.bodyLarge,
                textDecoration = if (done) TextDecoration.LineThrough else null,
                color = if (done) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
            )
            val meta = buildList {
                if (done) add("DONE")
                else if (task.status == TaskStatus.IN_PROGRESS.name) add("IN PROGRESS")
                if (recurring) add("Repeats")
            }
            if (meta.isNotEmpty() || due != null || nextReminder != null || flags.isNotEmpty() || blocked) {
                Spacer(Modifier.size(4.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    meta.forEach { Label(it, if (it == "DONE") c.done else MaterialTheme.colorScheme.primary) }
                    due?.let { Label(it.text, it.color) }
                    if (nextReminder != null && !done) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Filled.Notifications, contentDescription = "Reminder", modifier = Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(" " + Format.dateTime(nextReminder, zone), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    if (task.priority >= 3) Label(if (task.priority >= 4) "URGENT" else "HIGH", c.overdue)
                }
                if (flags.isNotEmpty() || task.id in snapshot.blockedByDeps) {
                    Spacer(Modifier.size(4.dp))
                    FlagLabels(flags, task.waitingOn, task.id in snapshot.blockedByDeps)
                }
            }
        }
        Box {
            IconButton(onClick = { menuOpen = true }) {
                Icon(Icons.Filled.MoreVert, contentDescription = "Actions for ${task.title}")
            }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                menu.forEach { a ->
                    DropdownMenuItem(text = { Text(a.label) }, onClick = { menuOpen = false; a.onClick() })
                }
            }
        }
    }
}

/** Imported note paragraph: read-only text, visually lighter than tasks, never a checkbox. */
@Composable
fun NoteRow(note: TaskEntity, onOpen: () -> Unit, menu: List<TaskMenuAction>) {
    var menuOpen by remember { mutableStateOf(false) }
    Row(
        verticalAlignment = Alignment.Top,
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onOpen, onLongClick = { menuOpen = true })
            .padding(start = (16 + note.level.coerceIn(0, 5) * 20).dp, end = 4.dp),
    ) {
        Column(Modifier.weight(1f).padding(vertical = 10.dp)) {
            Text("NOTE", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
            Text(note.title, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (note.description.isNotBlank()) {
                Text(note.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 4, overflow = TextOverflow.Ellipsis)
            }
        }
        Box {
            IconButton(onClick = { menuOpen = true }) { Icon(Icons.Filled.MoreVert, contentDescription = "Actions for note") }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                menu.forEach { a -> DropdownMenuItem(text = { Text(a.label) }, onClick = { menuOpen = false; a.onClick() }) }
            }
        }
    }
}

@Composable
fun SectionHeader(section: SectionEntity, progress: Progress, onToggle: () -> Unit, menu: List<TaskMenuAction>, topLevel: Int = 1) {
    var menuOpen by remember { mutableStateOf(false) }
    Surface(color = MaterialTheme.colorScheme.surfaceContainer, shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .combinedClickable(onClick = onToggle, onLongClick = { menuOpen = true }, onClickLabel = if (section.collapsed) "Expand" else "Collapse")
                .heightIn(min = 56.dp)
                .padding(start = (12 + (section.headingLevel - topLevel).coerceIn(0, 4) * 12).dp),
        ) {
            Icon(
                if (section.collapsed) Icons.Filled.KeyboardArrowDown else Icons.Filled.KeyboardArrowUp,
                contentDescription = if (section.collapsed) "Collapsed" else "Expanded",
            )
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Text(section.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                if (progress.total > 0) {
                    Text("${progress.done}/${progress.total} done", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (section.sourceMissing) Label("NOT IN LATEST IMPORT", MaterialTheme.colorScheme.outline)
            Box {
                IconButton(onClick = { menuOpen = true }) { Icon(Icons.Filled.MoreVert, contentDescription = "Section actions for ${section.title}") }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    menu.forEach { a -> DropdownMenuItem(text = { Text(a.label) }, onClick = { menuOpen = false; a.onClick() }) }
                }
            }
        }
    }
}

@Composable
fun ConfirmDialog(
    title: String,
    text: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    dismissLabel: String = "Cancel",
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(text) },
        confirmButton = { TextButton(onClick = { onDismiss(); onConfirm() }) { Text(confirmLabel) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(dismissLabel) } },
    )
}

@Composable
fun TextInputDialog(
    title: String,
    initial: String,
    label: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
    singleLine: Boolean = true,
    confirmLabel: String = "Save",
) {
    var value by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = value, onValueChange = { value = it }, label = { Text(label) },
                singleLine = singleLine, minLines = if (singleLine) 1 else 3, modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = { TextButton(onClick = { onDismiss(); onConfirm(value) }, enabled = value.isNotBlank() || !singleLine) { Text(confirmLabel) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
fun TimePickerDialog(initial: LocalTime, onConfirm: (LocalTime) -> Unit, onDismiss: () -> Unit, title: String = "Choose time") {
    val state = rememberTimePickerState(initialHour = initial.hour, initialMinute = initial.minute)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { TimePicker(state = state) },
        confirmButton = { TextButton(onClick = { onDismiss(); onConfirm(LocalTime.of(state.hour, state.minute)) }) { Text("OK") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
fun DatePickerDialogM3(initial: LocalDate?, onConfirm: (LocalDate?) -> Unit, onDismiss: () -> Unit, allowClear: Boolean = false) {
    val state = rememberDatePickerState(
        initialSelectedDateMillis = (initial ?: LocalDate.now()).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
    )
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = {
                onDismiss()
                onConfirm(state.selectedDateMillis?.let { Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate() })
            }) { Text("OK") }
        },
        dismissButton = {
            Row {
                if (allowClear) TextButton(onClick = { onDismiss(); onConfirm(null) }) { Text("Clear") }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        },
    ) {
        DatePicker(state = state)
    }
}

@Composable
fun EmptyState(title: String, body: String, modifier: Modifier = Modifier, action: (@Composable () -> Unit)? = null) {
    Column(modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.size(8.dp))
        Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (action != null) {
            Spacer(Modifier.size(16.dp))
            action()
        }
    }
}

@Composable
fun ListHeading(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.primary,
    inset: androidx.compose.ui.unit.Dp = 16.dp,
) {
    Text(
        text.uppercase(),
        style = MaterialTheme.typography.labelLarge,
        fontWeight = FontWeight.Bold,
        color = color,
        modifier = modifier.padding(start = inset, end = inset, top = 20.dp, bottom = 6.dp),
    )
}
