package app.projectsteps.data

import android.content.Context
import android.net.Uri
import androidx.room.withTransaction
import app.projectsteps.BuildConfig
import app.projectsteps.core.backup.BackupCodec
import app.projectsteps.core.backup.BackupData
import app.projectsteps.core.backup.MarkdownExporter
import app.projectsteps.core.importer.ImportLimits
import app.projectsteps.core.importer.SafeZip
import app.projectsteps.reminders.ReminderScheduler
import java.io.ByteArrayOutputStream
import java.io.File
import java.time.ZoneId
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Manual backup/export and restore. Files go wherever the user picks in the system document
 * picker (including Google Drive if installed). Nothing is uploaded automatically.
 */
class BackupRepository(
    private val context: Context,
    private val db: AppDatabase,
    private val settings: SettingsRepository,
    private val scheduler: ReminderScheduler,
) {
    enum class Format(val mime: String, val extension: String, val label: String) {
        JSON("application/json", "json", "JSON backup (complete, restorable)"),
        MARKDOWN("text/markdown", "md", "Markdown (readable checklist)"),
        ZIP("application/zip", "zip", "ZIP (backup.json + one Markdown file per project)"),
    }

    suspend fun build(): BackupData {
        val raw = settings.rawAll()
        return BackupData(
            schemaVersion = BackupCodec.SCHEMA_VERSION,
            exportedAt = System.currentTimeMillis(),
            appVersion = BuildConfig.VERSION_NAME,
            deviceZoneId = ZoneId.systemDefault().id,
            projects = db.projects().all().filter { it.deletedAt == null }.map { it.toB() },
            sections = db.sections().all().filter { it.deletedAt == null }.map { it.toB() },
            tasks = db.tasks().all().filter { it.deletedAt == null }.map { it.toB() },
            dependencies = db.dependencies().all().map { it.toB() },
            reminders = db.reminders().all().map { it.toB() },
            completions = db.completions().all().map { it.toB() },
            settings = raw.filterKeys { it in SettingKeys.PROJECT_RELEVANT },
        ).let(::consistent)
    }

    /** Drops rows that point at deleted parents so the backup always validates. */
    private fun consistent(b: BackupData): BackupData {
        val projectIds = b.projects.map { it.id }.toSet()
        val sections = b.sections.filter { it.projectId in projectIds }
        val sectionIds = sections.map { it.id }.toSet()
        var tasks = b.tasks.filter { it.projectId in projectIds && it.sectionId in sectionIds }
        var ids = tasks.map { it.id }.toSet()
        while (tasks.any { it.parentId != null && it.parentId !in ids }) {
            tasks = tasks.filter { it.parentId == null || it.parentId in ids }
            ids = tasks.map { it.id }.toSet()
        }
        return b.copy(
            sections = sections,
            tasks = tasks,
            dependencies = b.dependencies.filter { it.taskId in ids && it.dependsOnId in ids },
            reminders = b.reminders.filter { (it.taskId == null || it.taskId in ids) && (it.projectId == null || it.projectId in projectIds) && (it.taskId != null || it.projectId != null) },
            completions = b.completions.filter { it.taskId in ids },
        )
    }

    fun suggestedName(format: Format): String {
        val stamp = java.time.LocalDateTime.now().toString().take(16).replace(':', '-').replace('T', '_')
        return "ProjectSteps-backup-$stamp.${format.extension}"
    }

    suspend fun export(uri: Uri, format: Format): String {
        val b = build()
        val bytes = when (format) {
            Format.JSON -> BackupCodec.encode(b).toByteArray()
            Format.MARKDOWN -> b.projects.sortedBy { it.position }
                .joinToString("\n---\n\n") { MarkdownExporter.project(b, it.id) }.toByteArray()
            Format.ZIP -> zip(b)
        }
        FileIo.write(context, uri, bytes)
        settings.set(SettingKeys.LAST_BACKUP_AT, System.currentTimeMillis().toString())
        return "Saved ${b.projects.size} project(s), ${b.tasks.count { it.kind == "TASK" }} task(s) (${bytes.size / 1024} KB)."
    }

    private fun zip(b: BackupData): ByteArray {
        val bos = ByteArrayOutputStream()
        ZipOutputStream(bos).use { z ->
            z.putNextEntry(ZipEntry("backup.json"))
            z.write(BackupCodec.encode(b).toByteArray())
            z.closeEntry()
            val used = HashSet<String>()
            for (p in b.projects.sortedBy { it.position }) {
                var name = MarkdownExporter.safeFileName(p.name)
                var n = 2
                while (!used.add(name.lowercase())) name = MarkdownExporter.safeFileName(p.name) + "-" + n++
                z.putNextEntry(ZipEntry("projects/$name.md"))
                z.write(MarkdownExporter.project(b, p.id).toByteArray())
                z.closeEntry()
            }
        }
        return bos.toByteArray()
    }

    /** Reads a .json backup, or a .zip export containing backup.json. */
    fun read(uri: Uri): BackupData {
        val name = FileIo.displayName(context, uri)
        val bytes = FileIo.readBytes(context, uri, BackupCodec.MAX_BYTES.toLong(), name)
        val text = if (SafeZip.looksLikeZip(bytes)) {
            val zip = SafeZip.open(bytes, ImportLimits(), name)
            val entry = zip.entry("backup.json") ?: throw BackupCodec.BackupException("“$name” doesn't contain backup.json.")
            String(zip.read(entry), Charsets.UTF_8)
        } else {
            String(bytes, Charsets.UTF_8)
        }
        return BackupCodec.decode(text)
    }

    /** Writes a copy of the current data inside the app's private storage (kept: last 3). */
    suspend fun writeSafetyCopy(): File {
        val dir = File(context.filesDir, "safety-backups").apply { mkdirs() }
        val file = File(dir, "before-restore-${System.currentTimeMillis()}.json")
        file.writeText(BackupCodec.encode(build()))
        dir.listFiles()?.sortedByDescending { it.lastModified() }?.drop(3)?.forEach { it.delete() }
        return file
    }

    suspend fun restoreReplace(b: BackupData): String {
        writeSafetyCopy()
        val oldReminders = db.reminders().all().map { it.id }
        db.withTransaction {
            db.completions().deleteAll()
            db.reminders().deleteAll()
            db.dependencies().deleteAll()
            db.tasks().deleteAll()
            db.sections().deleteAll()
            db.projects().deleteAll()
            db.projects().upsertAll(b.projects.map { it.toEntity() })
            db.sections().upsertAll(b.sections.map { it.toEntity() })
            db.tasks().upsertAll(b.tasks.map { it.toEntity() })
            db.dependencies().insertAll(b.dependencies.map { it.toEntity() })
            db.reminders().upsertAll(b.reminders.map { it.toEntity() })
            db.completions().insertAll(b.completions.map { it.toEntity() })
            b.settings.filterKeys { it in SettingKeys.PROJECT_RELEVANT }.forEach { (k, v) -> settings.set(k, v) }
        }
        oldReminders.forEach { scheduler.cancel(it) }
        scheduler.rescheduleAll()
        return "Restored ${b.projects.size} project(s) and ${b.tasks.count { it.kind == "TASK" }} task(s). " +
            "A safety copy of the previous data was kept inside the app."
    }

    /** Adds what's missing; for items present in both, keeps whichever was changed most recently. */
    suspend fun restoreMerge(b: BackupData): String {
        var added = 0
        var updated = 0
        db.withTransaction {
            val projects = db.projects().all().associateBy { it.id }
            val sections = db.sections().all().associateBy { it.id }
            val tasks = db.tasks().all().associateBy { it.id }
            val reminders = db.reminders().all().associateBy { it.id }

            fun <T> decide(local: T?, incomingUpdated: Long, localUpdated: (T) -> Long): Boolean =
                when {
                    local == null -> { added++; true }
                    incomingUpdated > localUpdated(local) -> { updated++; true }
                    else -> false
                }

            db.projects().upsertAll(b.projects.filter { decide(projects[it.id], it.updatedAt) { p -> p.updatedAt } }.map { it.toEntity() })
            db.sections().upsertAll(b.sections.filter { decide(sections[it.id], it.updatedAt) { s -> s.updatedAt } }.map { it.toEntity() })
            db.tasks().upsertAll(b.tasks.filter { decide(tasks[it.id], it.updatedAt) { t -> t.updatedAt } }.map { it.toEntity() })
            db.dependencies().insertAll(b.dependencies.map { it.toEntity() })
            db.reminders().upsertAll(b.reminders.filter { decide(reminders[it.id], it.updatedAt) { r -> r.updatedAt } }.map { it.toEntity() })
            db.completions().insertAll(b.completions.map { it.toEntity() })
        }
        scheduler.rescheduleAll()
        return "Merged backup: $added item(s) added, $updated updated with newer versions. Nothing was deleted."
    }
}
