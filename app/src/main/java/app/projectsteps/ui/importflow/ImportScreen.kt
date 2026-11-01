@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package app.projectsteps.ui.importflow

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.projectsteps.core.importer.CandidateType
import app.projectsteps.core.importer.DraftItem
import app.projectsteps.core.importer.DraftProject
import app.projectsteps.core.importer.DraftStats
import app.projectsteps.core.model.ItemKind
import app.projectsteps.data.ImportRepository
import app.projectsteps.ui.Format
import app.projectsteps.ui.MainViewModel
import app.projectsteps.ui.Route
import app.projectsteps.ui.components.FlagLabels
import app.projectsteps.ui.components.Label
import app.projectsteps.ui.components.ScreenScaffold
import app.projectsteps.ui.components.TextInputDialog
import app.projectsteps.ui.theme.LocalStatusColors

private fun plural(n: Int, word: String) = "$n $word" + if (n == 1) "" else "s"

private val ZIP_TYPES = arrayOf("application/zip", "application/x-zip-compressed", "application/octet-stream")
private val DOCX_TYPES = arrayOf("application/vnd.openxmlformats-officedocument.wordprocessingml.document", "application/octet-stream")
private val MD_TYPES = arrayOf("text/markdown", "text/x-markdown", "text/plain", "application/octet-stream")

/** Settings › Import (and Projects › Import). */
@Composable
fun ImportScreen(vm: MainViewModel, importVm: ImportViewModel) {
    ScreenScaffold(title = "Import", onBack = { importVm.reset(); vm.back() }) { padding ->
        ImportFlow(
            importVm = importVm,
            contentPadding = padding,
            showDemo = true,
            onFinished = { done ->
                importVm.reset()
                vm.back()
                if (done.createdProjectIds.size == 1) vm.navigate(Route.Project(done.createdProjectIds.first())) else vm.navigate(Route.Projects)
            },
        )
    }
}

@Composable
fun ImportFlow(
    importVm: ImportViewModel,
    contentPadding: PaddingValues,
    showDemo: Boolean,
    onFinished: (ImportState.Done) -> Unit,
    finishLabel: String = "Done",
    modifier: Modifier = Modifier,
) {
    val state by importVm.state.collectAsStateWithLifecycle()
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) importVm.onPicked(uri)
    }
    when (val s = state) {
        ImportState.Idle -> LazyColumn(contentPadding = contentPadding, modifier = modifier) {
            item {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Choose what to import", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Files are read on this phone only. Nothing is uploaded, and nothing is saved until you confirm the preview.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    BigButton("Import Project-Checklists.zip", "Your checklist bundle") { picker.launch(ZIP_TYPES) }
                    BigButton("Import another ZIP", "Any ZIP containing .docx or .md files") { picker.launch(ZIP_TYPES) }
                    BigButton("Import DOCX", "A single Word document") { picker.launch(DOCX_TYPES) }
                    BigButton("Import Markdown", "A single .md or .txt file") { picker.launch(MD_TYPES) }
                    if (showDemo) {
                        OutlinedButton(onClick = { importVm.loadDemo() }, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
                            Text("Skip and use demo data (clearly marked DEMO)")
                        }
                    }
                }
            }
        }

        is ImportState.Working -> Box(Modifier.fillMaxWidth().padding(contentPadding).padding(48.dp), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                CircularProgressIndicator()
                Spacer(Modifier.size(16.dp))
                Text(s.message)
            }
        }

        is ImportState.Failed -> LazyColumn(contentPadding = contentPadding, modifier = modifier) {
            item {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer), modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                    Column(Modifier.padding(16.dp)) {
                        Text("IMPORT STOPPED", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onErrorContainer)
                        Text(s.message, color = MaterialTheme.colorScheme.onErrorContainer)
                        Text("Nothing was saved.", color = MaterialTheme.colorScheme.onErrorContainer, style = MaterialTheme.typography.bodySmall)
                    }
                }
                Button(onClick = { importVm.reset() }, modifier = Modifier.padding(horizontal = 16.dp)) { Text("Choose another file") }
            }
        }

        is ImportState.ChooseFiles -> ChooseFiles(s, importVm, contentPadding, modifier)

        is ImportState.Preview -> Preview(s, importVm, contentPadding, modifier)

        is ImportState.Done -> LazyColumn(contentPadding = contentPadding, modifier = modifier) {
            item {
                Column(Modifier.padding(16.dp)) {
                    Text("Import saved", style = MaterialTheme.typography.titleLarge)
                    Spacer(Modifier.size(8.dp))
                    Text(s.summary)
                    Spacer(Modifier.size(16.dp))
                    Button(onClick = { onFinished(s) }, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) { Text(finishLabel) }
                    TextButton(onClick = { importVm.reset() }) { Text("Import more files") }
                }
            }
        }
    }
}

@Composable
private fun BigButton(title: String, subtitle: String, onClick: () -> Unit) {
    Card(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp).heightIn(min = 40.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun ChooseFiles(s: ImportState.ChooseFiles, importVm: ImportViewModel, contentPadding: PaddingValues, modifier: Modifier) {
    val selectable = s.candidates.filter { it.selectable }
    LazyColumn(contentPadding = contentPadding, modifier = modifier) {
        item {
            Column(Modifier.padding(16.dp)) {
                Text(s.sourceName, style = MaterialTheme.typography.titleMedium)
                Text("Archive checked: ${s.candidates.size} file(s), ${selectable.size} importable. Choose which documents to import.", style = MaterialTheme.typography.bodySmall)
                Row {
                    TextButton(onClick = { importVm.setAll(true) }) { Text("Select all") }
                    TextButton(onClick = { importVm.setAll(false) }) { Text("Select none") }
                }
            }
        }
        items(s.candidates, key = { it.path }) { c ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 56.dp)
                    .clickable(enabled = c.selectable) { importVm.toggle(c.path) }
                    .padding(horizontal = 8.dp),
            ) {
                Checkbox(checked = c.path in s.selected, onCheckedChange = if (c.selectable) { _ -> importVm.toggle(c.path) } else null, enabled = c.selectable)
                Column(Modifier.weight(1f)) {
                    Text(c.displayName, color = if (c.selectable) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outline)
                    Text(
                        listOfNotNull(
                            when (c.type) {
                                CandidateType.DOCX -> "Word document"
                                CandidateType.MARKDOWN -> "Markdown"
                                CandidateType.TEXT -> "Text"
                                else -> null
                            },
                            Format.bytes(c.sizeBytes), c.note,
                        ).joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        item {
            Button(
                onClick = { importVm.parse(s.selected) }, enabled = s.selected.isNotEmpty(),
                modifier = Modifier.fillMaxWidth().padding(16.dp).heightIn(min = 52.dp),
            ) { Text("Read ${s.selected.size} document(s)") }
        }
    }
}

private enum class PreviewFilter(val label: String) { ALL("All"), REVIEW("Needs review"), TASKS("Tasks"), NOTES("Notes"), SECTIONS("Sections"), EXCLUDED("Excluded") }

@Composable
private fun Preview(s: ImportState.Preview, importVm: ImportViewModel, contentPadding: PaddingValues, modifier: Modifier) {
    val stats = DraftStats.of(s.projects)
    var expanded by rememberSaveable { mutableStateOf(s.projects.firstOrNull()?.key) }
    var filter by rememberSaveable { mutableStateOf(PreviewFilter.ALL) }
    var editing by remember { mutableStateOf<Pair<String, DraftItem>?>(null) }
    var renaming by remember { mutableStateOf<DraftProject?>(null) }
    val c = LocalStatusColors.current

    LazyColumn(contentPadding = contentPadding, modifier = modifier) {
        item(key = "stats") {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh), modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                Column(Modifier.padding(16.dp)) {
                    Text("Review import", style = MaterialTheme.typography.titleLarge)
                    Text("Nothing is saved until you tap Save. Change anything that was classified wrongly.", style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.size(8.dp))
                    Text(listOf(plural(stats.projects, "project"), plural(stats.sections, "section"), plural(stats.tasks, "task"), plural(stats.notes, "note")).joinToString(" · "))
                    Text("${stats.completed} marked completed in the source", fontWeight = FontWeight.SemiBold)
                    if (stats.needsReview > 0) Text("${stats.needsReview} item(s) need your review", color = c.blocked, fontWeight = FontWeight.SemiBold)
                    if (stats.excluded > 0) Text("${stats.excluded} item(s) excluded (still listed below)", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        if (s.failures.isNotEmpty()) {
            item(key = "fail") {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer), modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                    Column(Modifier.padding(12.dp)) {
                        Text("Not imported", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onErrorContainer)
                        s.failures.forEach { (name, why) -> Text("$name: $why", color = MaterialTheme.colorScheme.onErrorContainer, style = MaterialTheme.typography.bodySmall) }
                    }
                }
            }
        }
        if (s.duplicates.isNotEmpty()) {
            item(key = "dups") {
                Column(Modifier.padding(16.dp)) {
                    Text("Already imported", style = MaterialTheme.typography.titleMedium)
                    s.duplicates.forEach { d ->
                        Text(
                            "${d.draftName} matches “${d.existingName}”" + if (d.identical) " (identical file)" else " (document changed)",
                            modifier = Modifier.padding(top = 8.dp),
                        )
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            ImportRepository.DuplicateAction.entries.forEach { a ->
                                FilterChip(selected = s.actions[d.draftKey] == a, onClick = { importVm.setDuplicateAction(d.draftKey, a) }, label = { Text(a.label) })
                            }
                        }
                    }
                    Text(
                        "Merge keeps your completion state, notes and reminders, adds new items, and labels items no longer in the document. It never deletes or silently overwrites.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        item(key = "filters") {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(horizontal = 16.dp)) {
                PreviewFilter.entries.forEach { f -> FilterChip(selected = filter == f, onClick = { filter = f }, label = { Text(f.label) }) }
            }
        }
        s.projects.forEach { p ->
            val pStats = DraftStats.of(listOf(p.copy(include = true)))
            item(key = "p-" + p.key) {
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
                ) {
                    Column(Modifier.padding(12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f).clickable { expanded = if (expanded == p.key) null else p.key }) {
                                Text(p.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                                Text(
                                    "${p.fileName} · ${pStats.sections} sections · ${pStats.tasks} tasks · ${pStats.notes} notes · ${pStats.completed} done" +
                                        if (pStats.needsReview > 0) " · ${pStats.needsReview} to review" else "",
                                    style = MaterialTheme.typography.bodySmall,
                                )
                                if (p.description.isNotBlank()) Text("Title: ${p.description}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Switch(
                                checked = p.include, onCheckedChange = { importVm.setProjectIncluded(p.key, it) },
                                modifier = Modifier.semantics { contentDescription = "Import ${p.name}" },
                            )
                        }
                        p.warnings.forEach { Text("⚠ $it", style = MaterialTheme.typography.bodySmall) }
                        Row {
                            TextButton(onClick = { expanded = if (expanded == p.key) null else p.key }) { Text(if (expanded == p.key) "Hide items" else "Show items") }
                            TextButton(onClick = { renaming = p }) { Text("Rename") }
                            if (pStats.needsReview > 0) TextButton(onClick = { importVm.acceptAll(p.key) }) { Text("Accept all") }
                        }
                    }
                }
            }
            if (expanded == p.key && p.include) {
                val shown = p.items.filter { item ->
                    when (filter) {
                        PreviewFilter.ALL -> true
                        PreviewFilter.REVIEW -> item.needsReview
                        PreviewFilter.TASKS -> item.kind == ItemKind.TASK
                        PreviewFilter.NOTES -> item.kind == ItemKind.NOTE
                        PreviewFilter.SECTIONS -> item.kind == ItemKind.SECTION
                        PreviewFilter.EXCLUDED -> !item.include
                    }
                }
                if (shown.isEmpty()) item(key = "none-" + p.key) { Text("No items match this filter.", modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp)) }
                items(shown, key = { "i-" + it.id }) { item -> PreviewItem(p.key, item, importVm) { editing = p.key to item } }
            }
        }
        item(key = "save") {
            val count = s.projects.count { it.include }
            Button(
                onClick = { importVm.save() }, enabled = count > 0,
                modifier = Modifier.fillMaxWidth().padding(16.dp).heightIn(min = 56.dp),
            ) { Text("Save $count project(s)") }
            TextButton(onClick = { importVm.reset() }, modifier = Modifier.padding(horizontal = 16.dp)) { Text("Cancel import") }
            Spacer(Modifier.size(24.dp))
        }
    }

    editing?.let { (key, item) ->
        TextInputDialog(
            "Edit text", item.title, "Title",
            onConfirm = { v -> importVm.editItem(key, item.id) { it.copy(title = v.trim()) } },
            onDismiss = { editing = null },
        )
    }
    renaming?.let { p ->
        TextInputDialog("Project name", p.name, "Name", onConfirm = { importVm.renameProject(p.key, it.trim()) }, onDismiss = { renaming = null })
    }
}

@Composable
private fun PreviewItem(projectKey: String, item: DraftItem, importVm: ImportViewModel, onEdit: () -> Unit) {
    var kindMenu by remember { mutableStateOf(false) }
    val c = LocalStatusColors.current
    val indent = when (item.kind) {
        ItemKind.SECTION -> ((item.headingLevel - 1).coerceIn(0, 3) * 12)
        else -> 12 + item.level.coerceIn(0, 5) * 16
    }
    Row(
        verticalAlignment = Alignment.Top,
        modifier = Modifier.fillMaxWidth().padding(start = (16 + indent).dp, end = 12.dp, top = 4.dp, bottom = 4.dp),
    ) {
        Checkbox(
            checked = item.include,
            onCheckedChange = { v -> importVm.editItem(projectKey, item.id) { it.copy(include = v) } },
            modifier = Modifier.semantics { contentDescription = "Include: ${item.title}" },
        )
        Column(Modifier.weight(1f).padding(top = 4.dp)) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Box {
                    AssistChip(onClick = { kindMenu = true }, label = { Text(item.kind.name) })
                    DropdownMenu(expanded = kindMenu, onDismissRequest = { kindMenu = false }) {
                        listOf(ItemKind.SECTION, ItemKind.TASK, ItemKind.NOTE).forEach { k ->
                            DropdownMenuItem(text = { Text(k.name) }, onClick = {
                                kindMenu = false
                                importVm.editItem(projectKey, item.id) {
                                    it.copy(
                                        kind = k, needsReview = false,
                                        completed = if (k == ItemKind.TASK) it.completed else false,
                                        headingLevel = if (k == ItemKind.SECTION) it.headingLevel.coerceAtLeast(1) else it.headingLevel,
                                    )
                                }
                            })
                        }
                    }
                }
                if (item.needsReview) Label("REVIEW", c.blocked)
                if (!item.include) Label("EXCLUDED", MaterialTheme.colorScheme.outline)
            }
            Text(
                item.title,
                style = if (item.kind == ItemKind.SECTION) MaterialTheme.typography.titleSmall else MaterialTheme.typography.bodyMedium,
                fontWeight = if (item.kind == ItemKind.SECTION) FontWeight.Bold else null,
                modifier = Modifier.clickable(onClick = onEdit).padding(vertical = 4.dp),
                maxLines = 4, overflow = TextOverflow.Ellipsis,
            )
            if (item.description.isNotBlank()) Text(item.description, style = MaterialTheme.typography.bodySmall, maxLines = 3, overflow = TextOverflow.Ellipsis)
            if (item.kind == ItemKind.TASK) FlagLabels(item.flags, item.waitingOn)
            Text(
                item.reason + " · ¶${item.source.blockIndex + 1}" + (if (item.source.sectionPath.isNotBlank()) " · ${item.source.sectionPath}" else ""),
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (item.kind == ItemKind.TASK) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = item.completed, onCheckedChange = { v -> importVm.editItem(projectKey, item.id) { it.copy(completed = v, needsReview = false) } })
                    Text(
                        if (item.completed) "Done" + (item.completionMarker?.let { " (source: $it)" } ?: " (set by you)") else "Not done",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
            if (item.needsReview) {
                TextButton(onClick = { importVm.editItem(projectKey, item.id) { it.copy(needsReview = false) } }) { Text("Looks right") }
            }
        }
    }
}
