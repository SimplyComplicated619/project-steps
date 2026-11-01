package app.projectsteps.core.backup

import app.projectsteps.core.json.Json
import app.projectsteps.core.json.JsonObj

/*
 * Backup format (JSON). Field names match the database columns. Times are epoch milliseconds (UTC);
 * dates are ISO yyyy-MM-dd; clock times HH:mm; time zones are IANA IDs (null = device local zone).
 */

data class BProject(
    val id: String, val name: String, val description: String, val priority: Int, val status: String,
    val createdAt: Long, val updatedAt: Long, val dueDate: String?, val dueTime: String?, val dueZoneId: String?,
    val sourceFileName: String?, val sourceArchiveName: String?, val sourceTitle: String?, val sourceSha256: String?,
    val sourceKey: String?, val importedAt: Long?, val isDemo: Boolean, val lastActivityAt: Long, val position: Double,
    val archivedAt: Long?, val deletedAt: Long?,
)

data class BSection(
    val id: String, val projectId: String, val title: String, val headingLevel: Int, val position: Double,
    val collapsed: Boolean, val sourceTitle: String?, val sourceSectionPath: String?, val sourceBlockIndex: Int?,
    val sourceText: String?, val sourceMissing: Boolean, val createdAt: Long, val updatedAt: Long, val deletedAt: Long?,
)

data class BTask(
    val id: String, val projectId: String, val sectionId: String, val parentId: String?, val kind: String,
    val title: String, val description: String, val notes: String, val position: Double, val level: Int,
    val status: String, val completedAt: Long?, val priority: Int, val dueDate: String?, val dueTime: String?,
    val dueZoneId: String?, val waitingOn: String?, val flags: String, val links: String,
    val sourceFileName: String?, val sourceSectionPath: String?, val sourceBlockIndex: Int?, val sourceText: String?,
    val sourceTitle: String?, val sourceCompleted: Boolean, val sourceMissing: Boolean, val fingerprint: String?,
    val importedAt: Long?, val createdAt: Long, val updatedAt: Long, val deletedAt: Long?,
)

data class BDependency(val taskId: String, val dependsOnId: String)

data class BReminder(
    val id: String, val taskId: String?, val projectId: String?, val startDate: String, val time: String,
    val zoneId: String?, val repeatType: String, val interval: Int, val unit: String, val weekdaysMask: Int,
    val endDate: String?, val repeatUntilCompleted: Boolean, val nagMinutes: Int, val precise: Boolean,
    val enabled: Boolean, val lastFiredAt: Long?, val snoozedUntil: Long?, val completedThrough: Long?,
    val itemDone: Boolean, val armedAt: Long?, val createdAt: Long, val updatedAt: Long,
)

data class BCompletion(val id: String, val taskId: String, val occurrenceAt: Long?, val completedAt: Long, val action: String)

data class BackupData(
    val schemaVersion: Int,
    val exportedAt: Long,
    val appVersion: String,
    val deviceZoneId: String,
    val projects: List<BProject>,
    val sections: List<BSection>,
    val tasks: List<BTask>,
    val dependencies: List<BDependency>,
    val reminders: List<BReminder>,
    val completions: List<BCompletion>,
    val settings: Map<String, String>,
)

object BackupCodec {
    const val FORMAT = "project-steps-backup"
    const val SCHEMA_VERSION = 1
    const val MAX_BYTES = 30 * 1024 * 1024

    class BackupException(message: String) : Exception(message)

    fun encode(b: BackupData): String = Json.write(
        linkedMapOf(
            "format" to FORMAT,
            "schemaVersion" to b.schemaVersion,
            "exportedAt" to b.exportedAt,
            "appVersion" to b.appVersion,
            "deviceZoneId" to b.deviceZoneId,
            "settings" to b.settings,
            "projects" to b.projects.map { p ->
                linkedMapOf(
                    "id" to p.id, "name" to p.name, "description" to p.description, "priority" to p.priority,
                    "status" to p.status, "createdAt" to p.createdAt, "updatedAt" to p.updatedAt,
                    "dueDate" to p.dueDate, "dueTime" to p.dueTime, "dueZoneId" to p.dueZoneId,
                    "sourceFileName" to p.sourceFileName, "sourceArchiveName" to p.sourceArchiveName,
                    "sourceTitle" to p.sourceTitle, "sourceSha256" to p.sourceSha256, "sourceKey" to p.sourceKey,
                    "importedAt" to p.importedAt, "isDemo" to p.isDemo, "lastActivityAt" to p.lastActivityAt,
                    "position" to p.position, "archivedAt" to p.archivedAt, "deletedAt" to p.deletedAt,
                )
            },
            "sections" to b.sections.map { s ->
                linkedMapOf(
                    "id" to s.id, "projectId" to s.projectId, "title" to s.title, "headingLevel" to s.headingLevel,
                    "position" to s.position, "collapsed" to s.collapsed, "sourceTitle" to s.sourceTitle,
                    "sourceSectionPath" to s.sourceSectionPath, "sourceBlockIndex" to s.sourceBlockIndex,
                    "sourceText" to s.sourceText, "sourceMissing" to s.sourceMissing,
                    "createdAt" to s.createdAt, "updatedAt" to s.updatedAt, "deletedAt" to s.deletedAt,
                )
            },
            "tasks" to b.tasks.map { t ->
                linkedMapOf(
                    "id" to t.id, "projectId" to t.projectId, "sectionId" to t.sectionId, "parentId" to t.parentId,
                    "kind" to t.kind, "title" to t.title, "description" to t.description, "notes" to t.notes,
                    "position" to t.position, "level" to t.level, "status" to t.status, "completedAt" to t.completedAt,
                    "priority" to t.priority, "dueDate" to t.dueDate, "dueTime" to t.dueTime, "dueZoneId" to t.dueZoneId,
                    "waitingOn" to t.waitingOn, "flags" to t.flags, "links" to t.links,
                    "sourceFileName" to t.sourceFileName, "sourceSectionPath" to t.sourceSectionPath,
                    "sourceBlockIndex" to t.sourceBlockIndex, "sourceText" to t.sourceText, "sourceTitle" to t.sourceTitle,
                    "sourceCompleted" to t.sourceCompleted, "sourceMissing" to t.sourceMissing,
                    "fingerprint" to t.fingerprint, "importedAt" to t.importedAt,
                    "createdAt" to t.createdAt, "updatedAt" to t.updatedAt, "deletedAt" to t.deletedAt,
                )
            },
            "dependencies" to b.dependencies.map { linkedMapOf("taskId" to it.taskId, "dependsOnId" to it.dependsOnId) },
            "reminders" to b.reminders.map { r ->
                linkedMapOf(
                    "id" to r.id, "taskId" to r.taskId, "projectId" to r.projectId, "startDate" to r.startDate,
                    "time" to r.time, "zoneId" to r.zoneId, "repeatType" to r.repeatType, "interval" to r.interval,
                    "unit" to r.unit, "weekdaysMask" to r.weekdaysMask, "endDate" to r.endDate,
                    "repeatUntilCompleted" to r.repeatUntilCompleted, "nagMinutes" to r.nagMinutes,
                    "precise" to r.precise, "enabled" to r.enabled, "lastFiredAt" to r.lastFiredAt,
                    "snoozedUntil" to r.snoozedUntil, "completedThrough" to r.completedThrough,
                    "itemDone" to r.itemDone, "armedAt" to r.armedAt,
                    "createdAt" to r.createdAt, "updatedAt" to r.updatedAt,
                )
            },
            "completions" to b.completions.map { c ->
                linkedMapOf(
                    "id" to c.id, "taskId" to c.taskId, "occurrenceAt" to c.occurrenceAt,
                    "completedAt" to c.completedAt, "action" to c.action,
                )
            },
        )
    )

    /** Parses and validates a backup. Throws [BackupException] with a user-facing message. */
    @Suppress("UNCHECKED_CAST")
    fun decode(text: String): BackupData {
        if (text.length > MAX_BYTES) throw BackupException("Backup file is too large.")
        val root = try {
            Json.parse(text) as? Map<String, Any?> ?: throw BackupException("This file is not a Project Steps backup.")
        } catch (e: Json.JsonException) {
            throw BackupException("Backup file is damaged: ${e.message}")
        }
        val o = JsonObj(root)
        if (o.strOrNull("format") != FORMAT) throw BackupException("This file is not a Project Steps backup.")
        val version = o.int("schemaVersion", -1)
        if (version < 1) throw BackupException("Backup has no schema version.")
        if (version > SCHEMA_VERSION) throw BackupException("This backup was made by a newer version of Project Steps (schema $version). Update the app first.")
        try {
            val projects = o.objList("projects").map { p ->
                BProject(
                    p.str("id"), p.str("name"), p.strOrNull("description") ?: "", p.int("priority"),
                    p.strOrNull("status") ?: "NOT_STARTED", p.long("createdAt"), p.long("updatedAt"),
                    p.strOrNull("dueDate"), p.strOrNull("dueTime"), p.strOrNull("dueZoneId"),
                    p.strOrNull("sourceFileName"), p.strOrNull("sourceArchiveName"), p.strOrNull("sourceTitle"),
                    p.strOrNull("sourceSha256"), p.strOrNull("sourceKey"), p.longOrNull("importedAt"), p.bool("isDemo"),
                    p.longOrNull("lastActivityAt") ?: p.long("updatedAt"), p.double("position"),
                    p.longOrNull("archivedAt"), p.longOrNull("deletedAt"),
                )
            }
            val sections = o.objList("sections").map { s ->
                BSection(
                    s.str("id"), s.str("projectId"), s.str("title"), s.int("headingLevel", 1), s.double("position"),
                    s.bool("collapsed"), s.strOrNull("sourceTitle"), s.strOrNull("sourceSectionPath"),
                    s.intOrNull("sourceBlockIndex"), s.strOrNull("sourceText"), s.bool("sourceMissing"),
                    s.long("createdAt"), s.long("updatedAt"), s.longOrNull("deletedAt"),
                )
            }
            val tasks = o.objList("tasks").map { t ->
                BTask(
                    t.str("id"), t.str("projectId"), t.str("sectionId"), t.strOrNull("parentId"),
                    t.strOrNull("kind") ?: "TASK", t.str("title"), t.strOrNull("description") ?: "",
                    t.strOrNull("notes") ?: "", t.double("position"), t.int("level"),
                    t.strOrNull("status") ?: "NOT_STARTED", t.longOrNull("completedAt"), t.int("priority"),
                    t.strOrNull("dueDate"), t.strOrNull("dueTime"), t.strOrNull("dueZoneId"), t.strOrNull("waitingOn"),
                    t.strOrNull("flags") ?: "", t.strOrNull("links") ?: "", t.strOrNull("sourceFileName"),
                    t.strOrNull("sourceSectionPath"), t.intOrNull("sourceBlockIndex"), t.strOrNull("sourceText"),
                    t.strOrNull("sourceTitle"), t.bool("sourceCompleted"), t.bool("sourceMissing"),
                    t.strOrNull("fingerprint"), t.longOrNull("importedAt"), t.long("createdAt"), t.long("updatedAt"),
                    t.longOrNull("deletedAt"),
                )
            }
            val deps = o.objList("dependencies").map { BDependency(it.str("taskId"), it.str("dependsOnId")) }
            val reminders = o.objList("reminders").map { r ->
                BReminder(
                    r.str("id"), r.strOrNull("taskId"), r.strOrNull("projectId"), r.str("startDate"), r.str("time"),
                    r.strOrNull("zoneId"), r.strOrNull("repeatType") ?: "NONE", r.int("interval", 1),
                    r.strOrNull("unit") ?: "DAYS", r.int("weekdaysMask"), r.strOrNull("endDate"),
                    r.bool("repeatUntilCompleted"), r.int("nagMinutes", 60), r.bool("precise"), r.bool("enabled", true),
                    r.longOrNull("lastFiredAt"), r.longOrNull("snoozedUntil"), r.longOrNull("completedThrough"),
                    r.bool("itemDone"), r.longOrNull("armedAt"), r.long("createdAt"), r.long("updatedAt"),
                )
            }
            val completions = o.objList("completions").map { c ->
                BCompletion(c.str("id"), c.str("taskId"), c.longOrNull("occurrenceAt"), c.long("completedAt"), c.strOrNull("action") ?: "COMPLETED")
            }
            validate(projects, sections, tasks, deps, reminders)
            return BackupData(
                version, o.long("exportedAt"), o.strOrNull("appVersion") ?: "?", o.strOrNull("deviceZoneId") ?: "UTC",
                projects, sections, tasks, deps, reminders, completions, o.strMap("settings"),
            )
        } catch (e: Json.JsonException) {
            throw BackupException("Backup file is incomplete: ${e.message}")
        }
    }

    private fun validate(
        projects: List<BProject>, sections: List<BSection>, tasks: List<BTask>,
        deps: List<BDependency>, reminders: List<BReminder>,
    ) {
        fun dupes(ids: List<String>, what: String) {
            if (ids.size != ids.toSet().size) throw BackupException("Backup contains duplicate $what IDs.")
        }
        dupes(projects.map { it.id }, "project")
        dupes(sections.map { it.id }, "section")
        dupes(tasks.map { it.id }, "task")
        dupes(reminders.map { it.id }, "reminder")
        val projectIds = projects.map { it.id }.toSet()
        val sectionIds = sections.map { it.id }.toSet()
        val taskIds = tasks.map { it.id }.toSet()
        sections.firstOrNull { it.projectId !in projectIds }?.let { throw BackupException("Section “${it.title}” refers to a missing project.") }
        tasks.firstOrNull { it.projectId !in projectIds || it.sectionId !in sectionIds }
            ?.let { throw BackupException("Task “${it.title}” refers to a missing project or section.") }
        tasks.firstOrNull { it.parentId != null && it.parentId !in taskIds }
            ?.let { throw BackupException("Task “${it.title}” refers to a missing parent task.") }
        deps.firstOrNull { it.taskId !in taskIds || it.dependsOnId !in taskIds }
            ?.let { throw BackupException("Backup contains a dependency on a missing task.") }
        reminders.firstOrNull { (it.taskId == null && it.projectId == null) || (it.taskId != null && it.taskId !in taskIds) || (it.projectId != null && it.projectId !in projectIds) }
            ?.let { throw BackupException("Backup contains a reminder for a missing item.") }
    }
}

/** Human-readable Markdown export of one project. */
object MarkdownExporter {
    fun project(b: BackupData, projectId: String): String {
        val p = b.projects.first { it.id == projectId }
        val sb = StringBuilder()
        sb.append("# ").append(p.name).append("\n\n")
        if (p.description.isNotBlank()) sb.append(p.description).append("\n\n")
        val tasks = b.tasks.filter { it.projectId == projectId && it.deletedAt == null }
        val total = tasks.count { it.kind == "TASK" }
        val done = tasks.count { it.kind == "TASK" && it.status == "DONE" }
        sb.append("_Progress: $done / $total completed_\n\n")
        for (s in b.sections.filter { it.projectId == projectId && it.deletedAt == null }.sortedBy { it.position }) {
            sb.append("#".repeat((s.headingLevel + 1).coerceIn(2, 6))).append(' ').append(s.title).append("\n\n")
            val inSection = tasks.filter { it.sectionId == s.id }.sortedBy { it.position }
            for (t in inSection) {
                val indent = "  ".repeat(t.level.coerceIn(0, 6))
                if (t.kind == "NOTE") {
                    sb.append(indent).append(t.title.replace("\n", " ")).append("\n")
                    if (t.description.isNotBlank()) sb.append(indent).append(t.description.replace("\n", "\n$indent")).append("\n")
                    sb.append("\n")
                } else {
                    sb.append(indent).append(if (t.status == "DONE") "- [x] " else "- [ ] ").append(t.title.replace("\n", " "))
                    val flags = t.flags.split(',').filter { it.isNotBlank() }
                    if (flags.isNotEmpty()) sb.append("  _(").append(flags.joinToString(", ") { it.replace('_', ' ') }).append(")_")
                    sb.append("\n")
                    if (t.description.isNotBlank()) sb.append(indent).append("  ").append(t.description.replace("\n", "\n$indent  ")).append("\n")
                    if (t.notes.isNotBlank()) sb.append(indent).append("  > ").append(t.notes.replace("\n", "\n$indent  > ")).append("\n")
                }
            }
            sb.append("\n")
        }
        return sb.toString().trimEnd() + "\n"
    }

    fun safeFileName(name: String): String {
        val cleaned = name.replace(Regex("[^A-Za-z0-9 ._-]"), "").trim().replace(' ', '-')
            .replace(Regex("\\.{2,}"), ".").trim('.', '-', '_').take(60)
        return if (cleaned.any { it.isLetterOrDigit() }) cleaned else "project"
    }
}
