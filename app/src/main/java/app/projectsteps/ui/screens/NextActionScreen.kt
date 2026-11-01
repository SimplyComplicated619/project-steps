@file:OptIn(ExperimentalMaterial3Api::class)

package app.projectsteps.ui.screens

import android.view.WindowManager
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.projectsteps.core.next.NextActionPicker
import app.projectsteps.data.SettingKeys
import app.projectsteps.ui.MainViewModel
import app.projectsteps.ui.Route
import app.projectsteps.ui.components.EmptyState
import app.projectsteps.ui.components.FlagLabels
import app.projectsteps.ui.components.ScreenScaffold
import app.projectsteps.ui.components.dueInfo
import java.time.ZoneId

/**
 * Large, glanceable "what do I do next" view for when you're working on another device.
 * Optionally keeps the screen awake.
 */
@Composable
fun NextActionScreen(vm: MainViewModel) {
    val snap by vm.snapshot.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val now by vm.now.collectAsStateWithLifecycle()
    val zone = ZoneId.systemDefault()
    var skip by rememberSaveable { mutableIntStateOf(0) }
    var projectFilter by rememberSaveable { mutableStateOf<String?>(null) }
    var keepAwake by rememberSaveable { mutableStateOf(false) }
    var filterOpen by rememberSaveable { mutableStateOf(false) }

    val activity = LocalActivity.current
    DisposableEffect(keepAwake) {
        if (keepAwake) activity?.window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose { activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
    }

    val ranked = NextActionPicker.rank(snap.actionCandidates(now, zone), now, zone, settings.nextIncludeBlocked)
        .filter { projectFilter == null || it.projectId == projectFilter }
    val index = if (ranked.isEmpty()) 0 else skip.mod(ranked.size)
    val task = ranked.getOrNull(index)?.let { snap.taskById[it.taskId] }

    ScreenScaffold(title = "Next action", onBack = { vm.back() }) { padding ->
        Column(
            Modifier
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(20.dp)
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box {
                    OutlinedButton(onClick = { filterOpen = true }) {
                        Text(projectFilter?.let { snap.projectById[it]?.name } ?: "All active projects")
                    }
                    DropdownMenu(expanded = filterOpen, onDismissRequest = { filterOpen = false }) {
                        DropdownMenuItem(text = { Text("All active projects") }, onClick = { projectFilter = null; skip = 0; filterOpen = false })
                        snap.activeProjects.forEach { p ->
                            DropdownMenuItem(text = { Text(p.name) }, onClick = { projectFilter = p.id; skip = 0; filterOpen = false })
                        }
                    }
                }
            }
            SwitchRow("Keep screen on", "Useful while you work on another device.", keepAwake) { keepAwake = it }
            SwitchRow("Include blocked / waiting tasks", null, settings.nextIncludeBlocked) { vm.set(SettingKeys.NEXT_INCLUDE_BLOCKED, it) }

            if (task == null) {
                EmptyState("Nothing to do next", "Every open task is done, blocked or waiting, or there are no active projects.")
                return@Column
            }
            val project = snap.projectById[task.projectId]
            val section = snap.sectionById[task.sectionId]
            Spacer(Modifier.size(16.dp))
            Text("NEXT ACTION  ${index + 1} of ${ranked.size}", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
            Text(listOfNotNull(project?.name, section?.title).joinToString(" › "), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.size(12.dp))
            Text(
                task.title,
                style = MaterialTheme.typography.displaySmall,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.semantics { heading() },
            )
            dueInfo(task, snap, now, zone)?.let {
                Spacer(Modifier.size(8.dp))
                Text(it.text, style = MaterialTheme.typography.titleMedium, color = it.color, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.size(8.dp))
            FlagLabels(snap.flags(task), task.waitingOn, task.id in snap.blockedByDeps)
            if (task.description.isNotBlank()) {
                Spacer(Modifier.size(12.dp))
                Text(task.description, style = MaterialTheme.typography.bodyLarge)
            }
            if (task.notes.isNotBlank()) {
                Spacer(Modifier.size(12.dp))
                Text("My notes", style = MaterialTheme.typography.labelLarge)
                Text(task.notes, style = MaterialTheme.typography.bodyLarge)
            }
            Spacer(Modifier.size(24.dp))
            Button(onClick = { vm.setDone(task.id, true) }, modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp)) {
                Text("COMPLETE", style = MaterialTheme.typography.titleLarge)
            }
            Spacer(Modifier.size(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(onClick = { skip++ }, modifier = Modifier.weight(1f).heightIn(min = 56.dp), enabled = ranked.size > 1) { Text("Skip for now") }
                OutlinedButton(onClick = { vm.navigate(Route.Task(task.id)) }, modifier = Modifier.weight(1f).heightIn(min = 56.dp)) { Text("Open task") }
            }
            TextButton(onClick = { vm.navigate(Route.Project(task.projectId)) }) { Text("Open project") }
        }
    }
}
