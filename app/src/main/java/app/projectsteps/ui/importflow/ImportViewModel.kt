package app.projectsteps.ui.importflow

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.projectsteps.ProjectStepsApp
import app.projectsteps.core.importer.DemoData
import app.projectsteps.core.importer.DraftItem
import app.projectsteps.core.importer.DraftProject
import app.projectsteps.core.importer.ImportCandidate
import app.projectsteps.core.importer.ImportException
import app.projectsteps.core.importer.ImportLimits
import app.projectsteps.core.importer.ImportService
import app.projectsteps.core.importer.InspectedSource
import app.projectsteps.data.FileIo
import app.projectsteps.data.ImportRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed interface ImportState {
    data object Idle : ImportState
    data class Working(val message: String) : ImportState
    data class Failed(val message: String) : ImportState
    data class ChooseFiles(val sourceName: String, val candidates: List<ImportCandidate>, val selected: Set<String>) : ImportState
    data class Preview(
        val projects: List<DraftProject>,
        val failures: List<Pair<String, String>>,
        val duplicates: List<ImportRepository.Duplicate>,
        val actions: Map<String, ImportRepository.DuplicateAction>,
    ) : ImportState
    data class Done(val summary: String, val createdProjectIds: List<String>) : ImportState
}

/**
 * Import flow: pick → validate & list files → choose → parse → editable preview → save.
 * Nothing is written to the database until the user confirms the preview.
 */
class ImportViewModel(app: Application) : AndroidViewModel(app) {
    private val container = (app as ProjectStepsApp).container
    private val limits = ImportLimits()
    private var source: InspectedSource? = null

    private val _state = MutableStateFlow<ImportState>(ImportState.Idle)
    val state: StateFlow<ImportState> = _state

    fun reset() {
        source = null
        _state.value = ImportState.Idle
    }

    fun onPicked(uri: Uri) {
        _state.value = ImportState.Working("Checking file…")
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val ctx = getApplication<Application>()
                    val name = FileIo.displayName(ctx, uri)
                    val bytes = FileIo.readBytes(ctx, uri, limits.maxInputBytes, name)
                    ImportService.inspect(bytes, name, limits)
                }
            }
            result.fold(
                onSuccess = { src ->
                    source = src
                    val selectable = src.candidates.filter { it.selectable }.map { it.path }.toSet()
                    if (src.isArchive) {
                        _state.value = ImportState.ChooseFiles(src.sourceName, src.candidates, selectable)
                    } else {
                        parse(selectable)
                    }
                },
                onFailure = { _state.value = ImportState.Failed(messageOf(it)) },
            )
        }
    }

    fun toggle(path: String) {
        val s = _state.value as? ImportState.ChooseFiles ?: return
        _state.value = s.copy(selected = if (path in s.selected) s.selected - path else s.selected + path)
    }

    fun setAll(selectAll: Boolean) {
        val s = _state.value as? ImportState.ChooseFiles ?: return
        _state.value = s.copy(selected = if (selectAll) s.candidates.filter { it.selectable }.map { it.path }.toSet() else emptySet())
    }

    fun parse(selected: Set<String>) {
        val src = source ?: return
        if (selected.isEmpty()) return
        _state.value = ImportState.Working("Reading ${selected.size} document(s)…")
        viewModelScope.launch {
            val result = withContext(Dispatchers.Default) { runCatching { ImportService.parse(src, selected, limits) } }
            result.fold(
                onSuccess = { r -> showPreview(r.projects, r.failures) },
                onFailure = { _state.value = ImportState.Failed(messageOf(it)) },
            )
        }
    }

    fun loadDemo() {
        source = null
        viewModelScope.launch { showPreview(DemoData.projects(), emptyList()) }
    }

    private suspend fun showPreview(projects: List<DraftProject>, failures: List<Pair<String, String>>) {
        if (projects.isEmpty()) {
            _state.value = ImportState.Failed(
                failures.joinToString("\n") { "${it.first}: ${it.second}" }.ifBlank { "No documents could be read." }
            )
            return
        }
        val dups = container.imports.findDuplicates(projects)
        _state.value = ImportState.Preview(
            projects, failures, dups,
            dups.associate { it.draftKey to if (it.identical) ImportRepository.DuplicateAction.SKIP else ImportRepository.DuplicateAction.MERGE },
        )
    }

    // ------------------------------------------------------------------ preview editing

    private fun editPreview(transform: (ImportState.Preview) -> ImportState.Preview) {
        val s = _state.value as? ImportState.Preview ?: return
        _state.value = transform(s)
    }

    fun setProjectIncluded(key: String, include: Boolean) = editPreview { s ->
        s.copy(projects = s.projects.map { if (it.key == key) it.copy(include = include) else it })
    }

    fun renameProject(key: String, name: String) = editPreview { s ->
        s.copy(projects = s.projects.map { if (it.key == key) it.copy(name = name) else it })
    }

    fun editItem(projectKey: String, itemId: String, transform: (DraftItem) -> DraftItem) = editPreview { s ->
        s.copy(projects = s.projects.map { p ->
            if (p.key != projectKey) p else p.copy(items = p.items.map { if (it.id == itemId) transform(it) else it })
        })
    }

    /** Marks every remaining "needs review" item in a project as reviewed. */
    fun acceptAll(projectKey: String) = editPreview { s ->
        s.copy(projects = s.projects.map { p ->
            if (p.key != projectKey) p else p.copy(items = p.items.map { it.copy(needsReview = false) })
        })
    }

    fun setDuplicateAction(key: String, action: ImportRepository.DuplicateAction) = editPreview { s ->
        s.copy(actions = s.actions + (key to action))
    }

    fun save() {
        val s = _state.value as? ImportState.Preview ?: return
        _state.value = ImportState.Working("Saving…")
        viewModelScope.launch {
            val result = runCatching { container.imports.save(s.projects, s.actions, s.duplicates) }
            result.fold(
                onSuccess = { sum -> _state.value = ImportState.Done(sum.describe(), sum.createdProjectIds.toList()) },
                onFailure = {
                    _state.value = s
                    _state.value = ImportState.Failed("Saving failed, nothing was changed: ${messageOf(it)}")
                },
            )
        }
    }

    private fun messageOf(t: Throwable): String = when (t) {
        is ImportException -> t.userMessage
        is OutOfMemoryError -> "The file is too large to import on this device."
        is SecurityException -> "Android didn't allow access to that file. Please pick it again."
        else -> t.message ?: t.javaClass.simpleName
    }
}
