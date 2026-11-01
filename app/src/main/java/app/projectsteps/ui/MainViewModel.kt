package app.projectsteps.ui

import android.app.Application
import android.net.Uri
import androidx.compose.runtime.mutableStateListOf
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.projectsteps.ProjectStepsApp
import app.projectsteps.core.model.TaskStatus
import app.projectsteps.data.ActionResult
import app.projectsteps.data.AppSettings
import app.projectsteps.data.BackupRepository
import app.projectsteps.data.ProjectEntity
import app.projectsteps.data.ReminderEntity
import app.projectsteps.data.SettingKeys
import app.projectsteps.data.Snapshot
import app.projectsteps.data.TaskEntity
import app.projectsteps.data.ThemeMode
import app.projectsteps.data.Undo
import app.projectsteps.core.backup.BackupData
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalTime

sealed interface Route {
    data object Today : Route
    data object Upcoming : Route
    data object Projects : Route
    data object Search : Route
    data class Project(val id: String) : Route
    data class Task(val id: String) : Route
    data object NextAction : Route
    data object Settings : Route
    data class SettingsPage(val page: Page) : Route
    data object Import : Route
    data class ReminderEdit(val taskId: String?, val projectId: String?, val reminderId: String?) : Route

    enum class Page(val title: String) {
        PROJECTS("Projects"), NOTIFICATIONS("Notifications"), QUIET_HOURS("Quiet Hours"),
        REMINDER_DEFAULTS("Reminder Defaults"), RELIABILITY("Reminder Reliability"), BACKUP("Backup & Restore"),
        APPEARANCE("Appearance"), PRIVACY("Privacy"), ABOUT("About"),
    }

    companion object {
        val tabs = listOf(Today, Upcoming, Projects, Search)
    }
}

data class UiMessage(val text: String, val undo: Undo? = null, val id: Long = System.nanoTime())

class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val container = (app as ProjectStepsApp).container
    private val repo = container.repo

    val settings: StateFlow<AppSettings> =
        container.settings.settings.stateIn(viewModelScope, SharingStarted.Eagerly, AppSettings())
    val snapshot: StateFlow<Snapshot> =
        repo.snapshot.stateIn(viewModelScope, SharingStarted.Eagerly, Snapshot())

    /** Ticks every 30 s so "due now / overdue" labels stay current without heavy recomposition. */
    private val _now = MutableStateFlow(Instant.now())
    val now: StateFlow<Instant> = _now

    private val _messages = MutableSharedFlow<UiMessage>(extraBufferCapacity = 8)
    val messages: SharedFlow<UiMessage> = _messages

    /** Navigation back stack; the first entry is always a bottom-bar tab. */
    val backStack = mutableStateListOf<Route>(Route.Today)

    /** True while "Run Setup Again" is in progress (data is never reset by it). */
    val rerunningSetup = MutableStateFlow(false)

    init {
        viewModelScope.launch {
            while (true) {
                delay(30_000)
                _now.value = Instant.now()
            }
        }
    }

    // ------------------------------------------------------------------ navigation

    val current: Route get() = backStack.last()

    fun navigate(route: Route) {
        if (route in Route.tabs) {
            backStack.clear()
            backStack.add(route)
        } else if (backStack.lastOrNull() != route) {
            backStack.add(route)
        }
    }

    fun back(): Boolean {
        if (backStack.size <= 1) return false
        backStack.removeAt(backStack.size - 1)
        return true
    }

    fun openFromNotification(taskId: String?, projectId: String?) {
        when {
            taskId != null -> { backStack.clear(); backStack.add(Route.Today); backStack.add(Route.Task(taskId)) }
            projectId != null -> { backStack.clear(); backStack.add(Route.Projects); backStack.add(Route.Project(projectId)) }
        }
    }

    // ------------------------------------------------------------------ messages

    fun say(text: String, undo: Undo? = null) {
        _messages.tryEmit(UiMessage(text, undo))
    }

    private fun launchResult(block: suspend () -> ActionResult?) {
        viewModelScope.launch {
            val r = try {
                block()
            } catch (e: Exception) {
                ActionResult("Something went wrong: ${e.message ?: e.javaClass.simpleName}")
            }
            if (r != null) say(r.message, r.undo)
        }
    }

    fun undo(undo: Undo) {
        viewModelScope.launch {
            undo()
            say("Undone")
        }
    }

    // ------------------------------------------------------------------ tasks

    fun setDone(taskId: String, done: Boolean) = launchResult { repo.setTaskDone(taskId, done) }
    fun setStatus(taskId: String, status: TaskStatus) = launchResult { repo.setTaskStatus(taskId, status) }
    fun updateTask(task: TaskEntity) = viewModelScope.launch { repo.updateTask(task) }
    fun editTask(taskId: String, transform: (TaskEntity) -> TaskEntity) = viewModelScope.launch { repo.editTask(taskId, transform) }
    fun deleteTask(taskId: String) = launchResult { repo.deleteTask(taskId) }
    fun moveTask(taskId: String, delta: Int) = launchResult { repo.moveTask(taskId, delta) }
    fun moveTaskToSection(taskId: String, sectionId: String) = launchResult { repo.moveTaskToSection(taskId, sectionId) }

    fun addTask(projectId: String, sectionId: String, title: String, parentId: String? = null, kind: String = "TASK") {
        if (title.isBlank()) return
        viewModelScope.launch { repo.addTask(projectId, sectionId, title, parentId, kind) }
    }

    fun history(taskId: String): kotlinx.coroutines.flow.Flow<List<app.projectsteps.data.CompletionEntity>> = repo.completionHistory(taskId)

    fun canScheduleExact(): Boolean = container.scheduler.canScheduleExact()

    fun snoozeTask(taskId: String, minutes: Int) = viewModelScope.launch { say(repo.snoozeTask(taskId, minutes)) }

    fun addDependency(taskId: String, dependsOnId: String) = viewModelScope.launch {
        say(repo.addDependency(taskId, dependsOnId) ?: "Dependency added")
    }

    fun removeDependency(taskId: String, dependsOnId: String) = viewModelScope.launch { repo.removeDependency(taskId, dependsOnId) }

    // ------------------------------------------------------------------ sections & projects

    fun addSection(projectId: String, title: String) {
        if (title.isBlank()) return
        viewModelScope.launch { repo.addSection(projectId, title) }
    }

    fun renameSection(sectionId: String, title: String) = viewModelScope.launch { repo.renameSection(sectionId, title) }
    fun setSectionCollapsed(sectionId: String, collapsed: Boolean) = viewModelScope.launch { repo.setSectionCollapsed(sectionId, collapsed) }
    fun moveSection(sectionId: String, delta: Int) = launchResult { repo.moveSection(sectionId, delta) }
    fun deleteSection(sectionId: String) = launchResult { repo.deleteSection(sectionId) }

    fun createProject(name: String, open: Boolean = true) {
        if (name.isBlank()) return
        viewModelScope.launch {
            val id = repo.createProject(name)
            if (open) navigate(Route.Project(id))
        }
    }

    fun updateProject(p: ProjectEntity) = viewModelScope.launch { repo.updateProject(p) }
    fun setArchived(projectId: String, archived: Boolean) = launchResult { repo.setProjectArchived(projectId, archived) }
    fun moveProject(projectId: String, delta: Int) = launchResult { repo.moveProject(projectId, delta) }

    fun deleteProject(projectId: String) = launchResult {
        val r = repo.deleteProject(projectId)
        if (current == Route.Project(projectId)) back()
        r
    }

    // ------------------------------------------------------------------ reminders

    fun saveReminder(r: ReminderEntity) = viewModelScope.launch {
        repo.saveReminder(r)
        val s = settings.value
        if (!s.notificationsEnabled) say("Reminder saved, but notifications are turned off in Settings › Notifications.")
        else say("Reminder saved")
    }

    fun deleteReminder(id: String) = launchResult { repo.deleteReminder(id) }
    fun setReminderEnabled(id: String, enabled: Boolean) = viewModelScope.launch { repo.setReminderEnabled(id, enabled) }

    // ------------------------------------------------------------------ settings

    fun set(key: String, value: String): Job = viewModelScope.launch {
        container.settings.set(key, value)
        if (key in RESCHEDULE_KEYS) container.scheduler.rescheduleAll()
    }

    fun set(key: String, value: Boolean): Job = set(key, value.toString())
    fun set(key: String, value: Int): Job = set(key, value.toString())
    fun set(key: String, value: LocalTime): Job = set(key, value.toString())
    fun setTheme(mode: ThemeMode): Job = set(SettingKeys.THEME, mode.name)

    fun finishSetup() = viewModelScope.launch {
        container.settings.set(SettingKeys.SETUP_COMPLETED, true)
        rerunningSetup.value = false
        backStack.clear()
        backStack.add(Route.Today)
        container.scheduler.rescheduleAll()
    }

    fun runSetupAgain() {
        rerunningSetup.value = true
    }

    fun cancelSetupRerun() {
        rerunningSetup.value = false
    }

    fun rescheduleAll() = viewModelScope.launch { container.scheduler.rescheduleAll() }

    // ------------------------------------------------------------------ backup

    fun export(uri: Uri, format: BackupRepository.Format) = viewModelScope.launch {
        say(
            try {
                container.backups.export(uri, format)
            } catch (e: Exception) {
                "Export failed: ${e.message ?: e.javaClass.simpleName}"
            }
        )
    }

    /** Reads and validates a backup; the UI then asks Replace or Merge. */
    suspend fun readBackup(uri: Uri): Result<BackupData> = runCatching { container.backups.read(uri) }

    fun restore(backup: BackupData, replace: Boolean) = viewModelScope.launch {
        say(
            try {
                if (replace) container.backups.restoreReplace(backup) else container.backups.restoreMerge(backup)
            } catch (e: Exception) {
                "Restore failed, nothing was changed: ${e.message ?: e.javaClass.simpleName}"
            }
        )
    }

    companion object {
        private val RESCHEDULE_KEYS = setOf(
            SettingKeys.QUIET_ENABLED, SettingKeys.QUIET_START, SettingKeys.QUIET_END, SettingKeys.NOTIFICATIONS_ENABLED,
        )
    }
}
