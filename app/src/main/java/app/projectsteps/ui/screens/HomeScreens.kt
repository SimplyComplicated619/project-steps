@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package app.projectsteps.ui.screens

import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.projectsteps.core.model.ProjectStatus
import app.projectsteps.core.model.TaskStatus
import app.projectsteps.core.next.NextActionPicker
import app.projectsteps.data.ProjectEntity
import app.projectsteps.data.Snapshot
import app.projectsteps.data.TaskEntity
import app.projectsteps.data.isDone
import app.projectsteps.data.isNote
import app.projectsteps.reminders.Notifications
import app.projectsteps.ui.Format
import app.projectsteps.ui.MainViewModel
import app.projectsteps.ui.Route
import app.projectsteps.ui.components.ConfirmDialog
import app.projectsteps.ui.components.EmptyState
import app.projectsteps.ui.components.FlagLabels
import app.projectsteps.ui.components.Label
import app.projectsteps.ui.components.ListHeading
import app.projectsteps.ui.components.ScreenScaffold
import app.projectsteps.ui.components.TaskMenuAction
import app.projectsteps.ui.components.TaskRow
import app.projectsteps.ui.components.TextInputDialog
import app.projectsteps.ui.theme.LocalStatusColors
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Menu shared by every task row. Each entry is a normal tap target (no gesture required). */
fun taskMenu(
    vm: MainViewModel,
    task: TaskEntity,
    snap: Snapshot,
    onAddSubtask: (() -> Unit)? = null,
    onMoveToSection: (() -> Unit)? = null,
): List<TaskMenuAction> = buildList {
    add(TaskMenuAction(if (task.isDone) "Mark not done" else "Complete") { vm.setDone(task.id, !task.isDone) })
    add(TaskMenuAction("Open / edit") { vm.navigate(Route.Task(task.id)) })
    if (snap.remindersByTask[task.id].orEmpty().any { it.enabled }) {
        add(TaskMenuAction("Snooze 1 hour") { vm.snoozeTask(task.id, 60) })
    }
    add(TaskMenuAction("Add reminder") { vm.navigate(Route.ReminderEdit(task.id, task.projectId, null)) })
    if (!task.isDone) {
        if (task.status == TaskStatus.IN_PROGRESS.name) add(TaskMenuAction("Mark not started") { vm.setStatus(task.id, TaskStatus.NOT_STARTED) })
        else add(TaskMenuAction("Mark in progress") { vm.setStatus(task.id, TaskStatus.IN_PROGRESS) })
        if (task.status == TaskStatus.BLOCKED.name) add(TaskMenuAction("Unblock") { vm.setStatus(task.id, TaskStatus.NOT_STARTED) })
        else add(TaskMenuAction("Mark blocked") { vm.setStatus(task.id, TaskStatus.BLOCKED) })
    }
    onAddSubtask?.let { add(TaskMenuAction("Add subtask", it)) }
    add(TaskMenuAction("Move up") { vm.moveTask(task.id, -1) })
    add(TaskMenuAction("Move down") { vm.moveTask(task.id, +1) })
    onMoveToSection?.let { add(TaskMenuAction("Move to section…", it)) }
    add(TaskMenuAction("Delete") { vm.deleteTask(task.id) })
}

@Composable
private fun NotificationBanner(vm: MainViewModel) {
    val snap by vm.snapshot.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val hasReminders = snap.reminders.any { it.enabled }
    if (!hasReminders) return
    val problem = when {
        !settings.notificationsEnabled -> "Reminder notifications are turned off. Reminders are saved but won't appear."
        !Notifications.canPost(context) -> "Android is blocking notifications for Project Steps. Reminders can't appear."
        else -> return
    }
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text("NOTIFICATIONS OFF", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onErrorContainer)
            Text(problem, color = MaterialTheme.colorScheme.onErrorContainer)
            TextButton(onClick = { vm.navigate(Route.SettingsPage(Route.Page.NOTIFICATIONS)) }) { Text("Fix in Settings") }
        }
    }
}

@Composable
private fun DemoBanner(snap: Snapshot) {
    if (snap.projects.none { it.isDemo }) return
    Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
        Text(
            "DEMO DATA is shown. It is not one of your projects. Remove it in Settings › Projects.",
            modifier = Modifier.padding(12.dp),
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

@Composable
fun ProjectCard(project: ProjectEntity, snap: Snapshot, onClick: () -> Unit, menu: List<TaskMenuAction> = emptyList()) {
    val progress = snap.progress(project.id)
    var menuOpen by remember { mutableStateOf(false) }
    val c = LocalStatusColors.current
    Card(
        onClick = onClick,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
    ) {
        Row(Modifier.padding(start = 16.dp, top = 12.dp, bottom = 12.dp, end = 4.dp), verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f)) {
                Text(project.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (project.description.isNotBlank()) {
                    Text(project.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Spacer(Modifier.size(8.dp))
                LinearProgressIndicator(
                    progress = { if (progress.total == 0) 0f else progress.done.toFloat() / progress.total },
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.size(6.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("${progress.done} / ${progress.total} · ${progress.percent}%", style = MaterialTheme.typography.labelMedium)
                    val status = ProjectStatus.parse(project.status)
                    Label(
                        status.label.uppercase(),
                        when (status) {
                            ProjectStatus.DONE -> c.done
                            ProjectStatus.BLOCKED -> c.blocked
                            ProjectStatus.ARCHIVED -> MaterialTheme.colorScheme.outline
                            else -> MaterialTheme.colorScheme.primary
                        },
                    )
                    if (project.isDemo) Label("DEMO", MaterialTheme.colorScheme.outline)
                }
            }
            if (menu.isNotEmpty()) {
                Box {
                    IconButton(onClick = { menuOpen = true }) { Icon(Icons.Filled.MoreVert, contentDescription = "Actions for ${project.name}") }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        menu.forEach { a -> DropdownMenuItem(text = { Text(a.label) }, onClick = { menuOpen = false; a.onClick() }) }
                    }
                }
            }
        }
    }
}

@Composable
private fun NextActionCard(vm: MainViewModel, task: TaskEntity?, snap: Snapshot, remaining: Int) {
    ElevatedCard(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Column(Modifier.padding(16.dp)) {
            Text("NEXT ACTION", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
            if (task == null) {
                Text("Nothing actionable right now.", style = MaterialTheme.typography.titleMedium)
                Text("All open tasks are done, blocked or waiting.", style = MaterialTheme.typography.bodySmall)
                return@Column
            }
            val p = snap.projectById[task.projectId]
            val s = snap.sectionById[task.sectionId]
            Text(listOfNotNull(p?.name, s?.title).joinToString(" › "), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.size(4.dp))
            Text(task.title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            FlagLabels(snap.flags(task), task.waitingOn, task.id in snap.blockedByDeps)
            Spacer(Modifier.size(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { vm.setDone(task.id, true) }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Complete") }
                OutlinedButton(onClick = { vm.navigate(Route.NextAction) }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Focus view") }
            }
            if (remaining > 0) Text("$remaining more after this", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))
        }
    }
}

@Composable
fun TodayScreen(vm: MainViewModel) {
    val snap by vm.snapshot.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val now by vm.now.collectAsStateWithLifecycle()
    val zone = ZoneId.systemDefault()
    val today = LocalDate.now(zone)
    val c = LocalStatusColors.current

    val activeIds = snap.activeProjects.map { it.id }.toSet()
    val open = snap.tasks.filter { !it.isNote && !it.isDone && it.projectId in activeIds }
    val withDue = open.mapNotNull { t -> snap.due(t, now, zone)?.let { t to it } }
    val overdue = withDue.filter { it.second.isBefore(now) }.sortedBy { it.second }.map { it.first }
    val dueToday = withDue.filter { !it.second.isBefore(now) && it.second.atZone(zone).toLocalDate() == today }.sortedBy { it.second }.map { it.first }
    val ranked = NextActionPicker.rank(snap.actionCandidates(now, zone), now, zone, settings.nextIncludeBlocked)
    val next = ranked.firstOrNull()?.let { snap.taskById[it.taskId] }
    val blocked = open.filter { snap.isBlocked(it) }
    val recent = snap.activeProjects.sortedByDescending { it.lastActivityAt }.take(4)

    ScreenScaffold(
        title = "Today",
        subtitle = today.format(DateTimeFormatter.ofPattern("EEEE d MMMM")),
        actions = {
            IconButton(onClick = { vm.navigate(Route.NextAction) }) { Icon(Icons.Filled.PlayArrow, contentDescription = "Next action focus view") }
            IconButton(onClick = { vm.navigate(Route.Settings) }) { Icon(Icons.Filled.Settings, contentDescription = "Settings") }
        },
    ) { padding ->
        LazyColumn(contentPadding = padding, modifier = Modifier.fillMaxWidth()) {
            item { NotificationBanner(vm) }
            item { DemoBanner(snap) }
            if (snap.loaded && snap.projects.isEmpty()) {
                item {
                    EmptyState("No projects yet", "Import your checklist documents to turn them into projects.") {
                        Button(onClick = { vm.navigate(Route.Import) }) { Text("Import documents") }
                    }
                }
                return@LazyColumn
            }
            item { NextActionCard(vm, next, snap, (ranked.size - 1).coerceAtLeast(0)) }

            if (overdue.isNotEmpty()) {
                item { ListHeading("Overdue (${overdue.size})", color = c.overdue) }
                items(overdue, key = { "o-" + it.id }) { t -> ListTaskRow(t, vm, snap, now, zone) }
            }
            item { ListHeading("Due today (${dueToday.size})") }
            if (dueToday.isEmpty()) {
                item { Text("Nothing else is due today.", modifier = Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant) }
            } else {
                items(dueToday, key = { "t-" + it.id }) { t -> ListTaskRow(t, vm, snap, now, zone) }
            }
            if (blocked.isNotEmpty()) {
                item { ListHeading("Blocked or waiting (${blocked.size})", color = c.blocked) }
                items(blocked.take(5), key = { "b-" + it.id }) { t -> ListTaskRow(t, vm, snap, now, zone) }
                if (blocked.size > 5) item { Text("…and ${blocked.size - 5} more in their projects", modifier = Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.bodySmall) }
            }
            if (recent.isNotEmpty()) {
                item { ListHeading("Recently active projects") }
                items(recent, key = { "p-" + it.id }) { p -> ProjectCard(p, snap, onClick = { vm.navigate(Route.Project(p.id)) }) }
            }
            item { Spacer(Modifier.size(24.dp)) }
        }
    }
}

@Composable
private fun ListTaskRow(t: TaskEntity, vm: MainViewModel, snap: Snapshot, now: Instant, zone: ZoneId) {
    TaskRow(
        task = t, snapshot = snap, now = now, zone = zone,
        onToggle = { vm.setDone(t.id, it) },
        onOpen = { vm.navigate(Route.Task(t.id)) },
        menu = taskMenu(vm, t, snap),
        showProject = true,
        indentLevel = 0,
    )
}

@Composable
fun UpcomingScreen(vm: MainViewModel) {
    val snap by vm.snapshot.collectAsStateWithLifecycle()
    val now by vm.now.collectAsStateWithLifecycle()
    val zone = ZoneId.systemDefault()
    val today = LocalDate.now(zone)
    val horizon = today.plusDays(30)
    val activeIds = snap.activeProjects.map { it.id }.toSet()

    val entries = snap.tasks
        .filter { !it.isNote && !it.isDone && it.projectId in activeIds }
        .mapNotNull { t ->
            val due = snap.due(t, now, zone)
            val reminder = snap.nextReminderAt(t.id)
            val at = listOfNotNull(due?.takeIf { !it.isBefore(now) }, reminder).minOrNull() ?: return@mapNotNull null
            val date = at.atZone(zone).toLocalDate()
            if (date.isAfter(horizon)) null else Triple(date, at, t)
        }
        .sortedBy { it.second }
    val byDate = entries.groupBy { it.first }
    val projectReminders = snap.reminders.filter { it.taskId == null && it.enabled && it.nextFireAt != null && it.projectId in activeIds }

    ScreenScaffold(title = "Upcoming", subtitle = "Next 30 days") { padding ->
        LazyColumn(contentPadding = padding) {
            if (entries.isEmpty() && projectReminders.isEmpty()) {
                item { EmptyState("Nothing scheduled", "Tasks with due dates or reminders in the next 30 days appear here.") }
            }
            byDate.forEach { (date, list) ->
                item(key = "d-$date") { ListHeading(Format.date(date, today) + " · " + date.format(DateTimeFormatter.ofPattern("EEEE"))) }
                items(list, key = { "u-" + it.third.id }) { (_, _, t) -> ListTaskRow(t, vm, snap, now, zone) }
            }
            if (projectReminders.isNotEmpty()) {
                item { ListHeading("Project reminders") }
                items(projectReminders, key = { "pr-" + it.id }) { r ->
                    val p = snap.projectById[r.projectId]
                    Text(
                        "${p?.name ?: "Project"} · " + Format.dateTime(Instant.ofEpochMilli(r.nextFireAt!!), zone),
                        modifier = Modifier.fillMaxWidth().clickable { p?.let { vm.navigate(Route.Project(it.id)) } }.padding(16.dp),
                    )
                }
            }
            item { Spacer(Modifier.size(24.dp)) }
        }
    }
}

@Composable
fun ProjectsScreen(vm: MainViewModel) {
    val snap by vm.snapshot.collectAsStateWithLifecycle()
    var showNew by remember { mutableStateOf(false) }
    var showArchived by rememberSaveable { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf<ProjectEntity?>(null) }

    fun menu(p: ProjectEntity) = listOf(
        TaskMenuAction("Move up") { vm.moveProject(p.id, -1) },
        TaskMenuAction("Move down") { vm.moveProject(p.id, +1) },
        if (p.archivedAt == null) TaskMenuAction("Archive") { vm.setArchived(p.id, true) }
        else TaskMenuAction("Restore from archive") { vm.setArchived(p.id, false) },
        TaskMenuAction("Delete…") { confirmDelete = p },
    )

    ScreenScaffold(
        title = "Projects",
        subtitle = "${snap.activeProjects.size} active",
        actions = {
            TextButton(onClick = { vm.navigate(Route.Import) }) { Text("Import") }
            IconButton(onClick = { vm.navigate(Route.Settings) }) { Icon(Icons.Filled.Settings, contentDescription = "Settings") }
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(onClick = { showNew = true }, icon = { Icon(Icons.Filled.Add, contentDescription = null) }, text = { Text("New project") })
        },
    ) { padding ->
        LazyColumn(contentPadding = padding) {
            if (snap.loaded && snap.projects.isEmpty()) {
                item {
                    EmptyState("No projects yet", "Import Project-Checklists.zip or a single document, or create a project by hand.") {
                        Button(onClick = { vm.navigate(Route.Import) }) { Text("Import documents") }
                    }
                }
            }
            items(snap.activeProjects, key = { it.id }) { p ->
                ProjectCard(p, snap, onClick = { vm.navigate(Route.Project(p.id)) }, menu = menu(p))
            }
            if (snap.archivedProjects.isNotEmpty()) {
                item {
                    TextButton(onClick = { showArchived = !showArchived }, modifier = Modifier.padding(horizontal = 8.dp)) {
                        Text((if (showArchived) "Hide" else "Show") + " archived (${snap.archivedProjects.size})")
                    }
                }
                if (showArchived) items(snap.archivedProjects, key = { "a-" + it.id }) { p ->
                    ProjectCard(p, snap, onClick = { vm.navigate(Route.Project(p.id)) }, menu = menu(p))
                }
            }
            item { Spacer(Modifier.size(96.dp)) }
        }
    }
    if (showNew) TextInputDialog("New project", "", "Project name", onConfirm = { vm.createProject(it) }, onDismiss = { showNew = false }, confirmLabel = "Create")
    confirmDelete?.let { p ->
        ConfirmDialog(
            "Delete “${p.name}”?",
            "The project, its tasks and reminders are moved to the trash. You can undo right after; it's permanently removed after 30 days.",
            "Delete", onConfirm = { vm.deleteProject(p.id) }, onDismiss = { confirmDelete = null },
        )
    }
}

@Composable
fun SearchScreen(vm: MainViewModel) {
    val snap by vm.snapshot.collectAsStateWithLifecycle()
    val now by vm.now.collectAsStateWithLifecycle()
    val zone = ZoneId.systemDefault()
    var query by rememberSaveable { mutableStateOf("") }
    val q = query.trim().lowercase()
    val projects = if (q.length < 2) emptyList() else snap.projects.filter { it.name.lowercase().contains(q) || it.description.lowercase().contains(q) }
    val sections = if (q.length < 2) emptyList() else snap.sections.filter { it.title.lowercase().contains(q) }
    val tasks = if (q.length < 2) emptyList() else snap.tasks.filter {
        it.title.lowercase().contains(q) || it.description.lowercase().contains(q) || it.notes.lowercase().contains(q) ||
            (it.waitingOn?.lowercase()?.contains(q) == true)
    }.take(200)

    ScreenScaffold(title = "Search") { padding ->
        LazyColumn(contentPadding = padding) {
            item {
                OutlinedTextField(
                    value = query, onValueChange = { query = it }, singleLine = true,
                    label = { Text("Search projects, steps and notes") },
                    trailingIcon = { if (query.isNotEmpty()) IconButton(onClick = { query = "" }) { Icon(Icons.Filled.Clear, contentDescription = "Clear search") } },
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                )
            }
            if (q.length >= 2 && projects.isEmpty() && sections.isEmpty() && tasks.isEmpty()) {
                item { EmptyState("No matches", "Nothing contains “$query”.") }
            }
            if (projects.isNotEmpty()) {
                item { ListHeading("Projects") }
                items(projects, key = { "sp-" + it.id }) { p -> ProjectCard(p, snap, onClick = { vm.navigate(Route.Project(p.id)) }) }
            }
            if (sections.isNotEmpty()) {
                item { ListHeading("Sections") }
                items(sections, key = { "ss-" + it.id }) { s ->
                    Text(
                        "${snap.projectById[s.projectId]?.name ?: ""} › ${s.title}",
                        modifier = Modifier.fillMaxWidth().clickable { vm.navigate(Route.Project(s.projectId)) }.padding(horizontal = 16.dp, vertical = 14.dp),
                    )
                }
            }
            if (tasks.isNotEmpty()) {
                item { ListHeading("Steps and notes (${tasks.size})") }
                items(tasks, key = { "st-" + it.id }) { t ->
                    if (t.isNote) {
                        Column(Modifier.fillMaxWidth().clickable { vm.navigate(Route.Task(t.id)) }.padding(horizontal = 16.dp, vertical = 10.dp)) {
                            Text("NOTE · " + (snap.projectById[t.projectId]?.name ?: ""), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
                            Text(t.title, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                    } else {
                        ListTaskRow(t, vm, snap, now, zone)
                    }
                }
            }
            item { Spacer(Modifier.size(24.dp)) }
        }
    }
}
