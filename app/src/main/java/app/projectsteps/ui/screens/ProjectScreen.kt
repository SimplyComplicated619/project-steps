@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package app.projectsteps.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.projectsteps.core.model.Priority
import app.projectsteps.core.model.ProjectStatus
import app.projectsteps.data.ProjectEntity
import app.projectsteps.data.Progress
import app.projectsteps.data.SectionEntity
import app.projectsteps.data.SettingKeys
import app.projectsteps.data.TaskEntity
import app.projectsteps.data.isDone
import app.projectsteps.data.isNote
import app.projectsteps.data.toSpec
import app.projectsteps.core.schedule.Recurrence
import app.projectsteps.ui.Format
import app.projectsteps.ui.MainViewModel
import app.projectsteps.ui.Route
import app.projectsteps.ui.components.ConfirmDialog
import app.projectsteps.ui.components.DatePickerDialogM3
import app.projectsteps.ui.components.EmptyState
import app.projectsteps.ui.components.Label
import app.projectsteps.ui.components.NoteRow
import app.projectsteps.ui.components.ProgressHeader
import app.projectsteps.ui.components.ScreenScaffold
import app.projectsteps.ui.components.SectionHeader
import app.projectsteps.ui.components.TaskMenuAction
import app.projectsteps.ui.components.TaskRow
import app.projectsteps.ui.components.TextInputDialog
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** Parents first, each followed by its children (depth-first), siblings ordered by position. */
fun orderedTree(items: List<TaskEntity>): List<TaskEntity> {
    val ids = items.map { it.id }.toSet()
    val byParent = items.groupBy { if (it.parentId != null && it.parentId in ids) it.parentId else null }
    val out = ArrayList<TaskEntity>(items.size)
    val seen = HashSet<String>()
    fun walk(parent: String?) {
        for (t in byParent[parent].orEmpty().sortedBy { it.position }) {
            if (!seen.add(t.id)) continue
            out.add(t)
            walk(t.id)
        }
    }
    walk(null)
    items.filter { it.id !in seen }.sortedBy { it.position }.forEach { out.add(it) } // safety net for cycles
    return out
}

@Composable
fun ProjectScreen(vm: MainViewModel, projectId: String) {
    val snap by vm.snapshot.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val now by vm.now.collectAsStateWithLifecycle()
    val zone = ZoneId.systemDefault()
    val project = snap.projectById[projectId]
    if (project == null) {
        ScreenScaffold("Project", onBack = { vm.back() }) {
            if (snap.loaded) EmptyState("Project not found", "It may have been deleted.", Modifier.padding(it))
        }
        return
    }

    var menuOpen by remember { mutableStateOf(false) }
    var addTask by remember { mutableStateOf<Pair<String, String?>?>(null) } // sectionId, parentId
    var addSection by remember { mutableStateOf(false) }
    var renameSection by remember { mutableStateOf<SectionEntity?>(null) }
    var deleteSection by remember { mutableStateOf<SectionEntity?>(null) }
    var moveTask by remember { mutableStateOf<TaskEntity?>(null) }
    var editProject by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }

    val sections = snap.sectionsByProject[projectId].orEmpty()
    // Indent sub-headings relative to the top imported heading level (auto "General" sections excluded).
    val topLevel = sections.filter { it.sourceBlockIndex != null }.minOfOrNull { it.headingLevel } ?: 1
    val progress = snap.progress(projectId)
    val status = ProjectStatus.parse(project.status)

    ScreenScaffold(
        title = project.name,
        subtitle = status.label + if (project.isDemo) " · DEMO DATA" else "",
        onBack = { vm.back() },
        actions = {
            Box {
                IconButton(onClick = { menuOpen = true }) { Icon(Icons.Filled.MoreVert, contentDescription = "Project actions") }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(text = { Text("Edit project") }, onClick = { menuOpen = false; editProject = true })
                    DropdownMenuItem(text = { Text("Add section") }, onClick = { menuOpen = false; addSection = true })
                    DropdownMenuItem(text = { Text("Add project reminder") }, onClick = { menuOpen = false; vm.navigate(Route.ReminderEdit(null, projectId, null)) })
                    DropdownMenuItem(
                        text = { Text(if (settings.showCompleted) "Hide completed tasks" else "Show completed tasks") },
                        onClick = { menuOpen = false; vm.set(SettingKeys.SHOW_COMPLETED, !settings.showCompleted) },
                    )
                    DropdownMenuItem(
                        text = { Text(if (project.archivedAt == null) "Archive" else "Restore from archive") },
                        onClick = { menuOpen = false; vm.setArchived(projectId, project.archivedAt == null) },
                    )
                    DropdownMenuItem(text = { Text("Delete project…") }, onClick = { menuOpen = false; confirmDelete = true })
                }
            }
        },
    ) { padding ->
        LazyColumn(contentPadding = padding) {
            item(key = "header") {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                    ProgressHeader(progress)
                    if (project.description.isNotBlank()) {
                        Spacer(Modifier.size(8.dp))
                        Text(project.description, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Spacer(Modifier.size(8.dp))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        if (project.priority >= Priority.HIGH.rank) Label("PRIORITY: " + Priority.fromRank(project.priority).label.uppercase(), MaterialTheme.colorScheme.error)
                        snap.projectDue(project, zone)?.let { due ->
                            Label((if (due.isBefore(now)) "OVERDUE · " else "Due ") + Format.dateTime(due, zone), if (due.isBefore(now)) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
                        }
                        project.sourceFileName?.let { Label("From $it", MaterialTheme.colorScheme.outline) }
                    }
                    snap.remindersByProject[projectId].orEmpty().forEach { r ->
                        Text(
                            "Project reminder: " + Recurrence.describe(r.toSpec()) +
                                (r.nextFireAt?.let { " · next " + Format.dateTime(Instant.ofEpochMilli(it), zone) } ?: ""),
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { vm.navigate(Route.ReminderEdit(null, projectId, r.id)) }
                                .padding(vertical = 8.dp),
                        )
                    }
                }
            }
            if (sections.isEmpty()) {
                item { EmptyState("No sections", "Add a section to start adding tasks.") { OutlinedButton(onClick = { addSection = true }) { Text("Add section") } } }
            }
            sections.forEach { s ->
                val all = snap.tasksBySection[s.id].orEmpty()
                val visible = if (settings.showCompleted) all else all.filter { !it.isDone || snap.isRecurring(it.id) }
                val sp = Progress(all.count { !it.isNote && it.isDone }, all.count { !it.isNote })
                item(key = "s-" + s.id) {
                    Box(Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) {
                        SectionHeader(
                            s, sp,
                            onToggle = { vm.setSectionCollapsed(s.id, !s.collapsed) },
                            menu = listOf(
                                TaskMenuAction("Add task") { addTask = s.id to null },
                                TaskMenuAction("Rename") { renameSection = s },
                                TaskMenuAction("Move up") { vm.moveSection(s.id, -1) },
                                TaskMenuAction("Move down") { vm.moveSection(s.id, +1) },
                                TaskMenuAction("Delete section…") { deleteSection = s },
                            ),
                            topLevel = topLevel,
                        )
                    }
                }
                if (!s.collapsed) {
                    items(orderedTree(visible), key = { it.id }) { t ->
                        if (t.isNote) {
                            NoteRow(
                                t, onOpen = { vm.navigate(Route.Task(t.id)) },
                                menu = listOf(
                                    TaskMenuAction("Open") { vm.navigate(Route.Task(t.id)) },
                                    TaskMenuAction("Move up") { vm.moveTask(t.id, -1) },
                                    TaskMenuAction("Move down") { vm.moveTask(t.id, +1) },
                                    TaskMenuAction("Delete") { vm.deleteTask(t.id) },
                                ),
                            )
                        } else {
                            TaskRow(
                                task = t, snapshot = snap, now = now, zone = zone,
                                onToggle = { vm.setDone(t.id, it) },
                                onOpen = { vm.navigate(Route.Task(t.id)) },
                                menu = taskMenu(vm, t, snap, onAddSubtask = { addTask = s.id to t.id }, onMoveToSection = { moveTask = t }),
                            )
                        }
                    }
                    item(key = "add-" + s.id) {
                        TextButton(onClick = { addTask = s.id to null }, modifier = Modifier.padding(start = 12.dp).heightIn(min = 48.dp)) {
                            Text("+ Add task to ${s.title.take(30)}")
                        }
                    }
                }
            }
            item { Spacer(Modifier.size(32.dp)) }
        }
    }

    addTask?.let { (sectionId, parentId) ->
        TextInputDialog(
            if (parentId == null) "Add task" else "Add subtask", "", "Task",
            onConfirm = { vm.addTask(projectId, sectionId, it, parentId) },
            onDismiss = { addTask = null }, confirmLabel = "Add",
        )
    }
    if (addSection) TextInputDialog("Add section", "", "Section name", onConfirm = { vm.addSection(projectId, it) }, onDismiss = { addSection = false }, confirmLabel = "Add")
    renameSection?.let { s -> TextInputDialog("Rename section", s.title, "Section name", onConfirm = { vm.renameSection(s.id, it) }, onDismiss = { renameSection = null }) }
    deleteSection?.let { s ->
        ConfirmDialog(
            "Delete section “${s.title}”?", "Its tasks and notes are removed with it. You can undo right after.",
            "Delete", onConfirm = { vm.deleteSection(s.id) }, onDismiss = { deleteSection = null },
        )
    }
    moveTask?.let { t ->
        AlertDialog(
            onDismissRequest = { moveTask = null },
            title = { Text("Move to section") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    sections.filter { it.id != t.sectionId }.forEach { s ->
                        Text(
                            s.title,
                            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable { moveTask = null; vm.moveTaskToSection(t.id, s.id) }.padding(vertical = 12.dp),
                        )
                    }
                }
            },
            confirmButton = { TextButton(onClick = { moveTask = null }) { Text("Cancel") } },
        )
    }
    if (editProject) ProjectEditDialog(project, onSave = { vm.updateProject(it) }, onDismiss = { editProject = false })
    if (confirmDelete) {
        ConfirmDialog(
            "Delete “${project.name}”?",
            "The project and its tasks and reminders go to the trash. You can undo right after; it's permanently removed after 30 days.",
            "Delete", onConfirm = { vm.deleteProject(projectId) }, onDismiss = { confirmDelete = false },
        )
    }
}

@Composable
private fun ProjectEditDialog(project: ProjectEntity, onSave: (ProjectEntity) -> Unit, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf(project.name) }
    var description by remember { mutableStateOf(project.description) }
    var status by remember { mutableStateOf(ProjectStatus.parse(project.status)) }
    var priority by remember { mutableStateOf(Priority.fromRank(project.priority)) }
    var due by remember { mutableStateOf(project.dueDate?.let { runCatching { LocalDate.parse(it) }.getOrNull() }) }
    var pickDate by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Edit project") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.size(8.dp))
                OutlinedTextField(description, { description = it }, label = { Text("Description") }, minLines = 2, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.size(12.dp))
                Text("Status", style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    ProjectStatus.entries.filter { it != ProjectStatus.ARCHIVED }.forEach { st ->
                        FilterChip(selected = status == st, onClick = { status = st }, label = { Text(st.label) })
                    }
                }
                Text("Priority", style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Priority.entries.forEach { pr -> FilterChip(selected = priority == pr, onClick = { priority = pr }, label = { Text(pr.label) }) }
                }
                TextButton(onClick = { pickDate = true }) { Text("Due date: " + (due?.let { Format.date(it) } ?: "none")) }
            }
        },
        confirmButton = {
            TextButton(enabled = name.isNotBlank(), onClick = {
                onDismiss()
                onSave(
                    project.copy(
                        name = name.trim(), description = description.trim(),
                        status = if (project.archivedAt != null) project.status else status.name,
                        priority = priority.rank, dueDate = due?.toString(),
                    )
                )
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
    if (pickDate) DatePickerDialogM3(due, onConfirm = { due = it }, onDismiss = { pickDate = false }, allowClear = true)
}
