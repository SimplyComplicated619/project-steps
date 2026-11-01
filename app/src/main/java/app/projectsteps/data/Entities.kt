package app.projectsteps.data

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/*
 * Room entities. IDs are random UUID strings and never derived from titles.
 * Rows are soft-deleted (deletedAt) so accidental deletes can be undone; they are purged later.
 * Positions are doubles so items can be inserted between neighbours without renumbering.
 */

@Entity(tableName = "projects", indices = [Index("sourceKey"), Index("sourceFileName")])
data class ProjectEntity(
    @PrimaryKey val id: String,
    val name: String,
    val description: String = "",
    val priority: Int = 0,
    val status: String = "NOT_STARTED",
    val createdAt: Long,
    val updatedAt: Long,
    val dueDate: String? = null,
    val dueTime: String? = null,
    val dueZoneId: String? = null,
    val sourceFileName: String? = null,
    val sourceArchiveName: String? = null,
    val sourceTitle: String? = null,
    val sourceSha256: String? = null,
    val sourceKey: String? = null,
    val importedAt: Long? = null,
    val isDemo: Boolean = false,
    val lastActivityAt: Long,
    val position: Double,
    val archivedAt: Long? = null,
    val deletedAt: Long? = null,
)

@Entity(
    tableName = "sections",
    foreignKeys = [ForeignKey(entity = ProjectEntity::class, parentColumns = ["id"], childColumns = ["projectId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("projectId")],
)
data class SectionEntity(
    @PrimaryKey val id: String,
    val projectId: String,
    val title: String,
    val headingLevel: Int = 1,
    val position: Double,
    val collapsed: Boolean = false,
    val sourceTitle: String? = null,
    val sourceSectionPath: String? = null,
    val sourceBlockIndex: Int? = null,
    val sourceText: String? = null,
    val sourceMissing: Boolean = false,
    val createdAt: Long,
    val updatedAt: Long,
    val deletedAt: Long? = null,
)

@Entity(
    tableName = "tasks",
    foreignKeys = [
        ForeignKey(entity = ProjectEntity::class, parentColumns = ["id"], childColumns = ["projectId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(entity = SectionEntity::class, parentColumns = ["id"], childColumns = ["sectionId"], onDelete = ForeignKey.CASCADE),
    ],
    indices = [Index("projectId"), Index("sectionId"), Index("parentId")],
)
data class TaskEntity(
    @PrimaryKey val id: String,
    val projectId: String,
    val sectionId: String,
    val parentId: String? = null,
    /** "TASK" or "NOTE" (an imported note paragraph). */
    val kind: String = "TASK",
    val title: String,
    val description: String = "",
    /** The user's personal notes. Never overwritten by re-imports. */
    val notes: String = "",
    val position: Double,
    val level: Int = 0,
    val status: String = "NOT_STARTED",
    val completedAt: Long? = null,
    val priority: Int = 0,
    val dueDate: String? = null,
    val dueTime: String? = null,
    val dueZoneId: String? = null,
    val waitingOn: String? = null,
    /** Comma-separated TaskFlag names. */
    val flags: String = "",
    /** Newline-separated links. */
    val links: String = "",
    val sourceFileName: String? = null,
    val sourceSectionPath: String? = null,
    val sourceBlockIndex: Int? = null,
    val sourceText: String? = null,
    val sourceTitle: String? = null,
    val sourceCompleted: Boolean = false,
    val sourceMissing: Boolean = false,
    val fingerprint: String? = null,
    val importedAt: Long? = null,
    val createdAt: Long,
    val updatedAt: Long,
    val deletedAt: Long? = null,
)

val TaskEntity.isNote: Boolean get() = kind == "NOTE"
val TaskEntity.isDone: Boolean get() = status == "DONE"

@Entity(
    tableName = "dependencies",
    primaryKeys = ["taskId", "dependsOnId"],
    foreignKeys = [
        ForeignKey(entity = TaskEntity::class, parentColumns = ["id"], childColumns = ["taskId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(entity = TaskEntity::class, parentColumns = ["id"], childColumns = ["dependsOnId"], onDelete = ForeignKey.CASCADE),
    ],
    indices = [Index("dependsOnId")],
)
data class DependencyEntity(
    val taskId: String,
    val dependsOnId: String,
)

@Entity(tableName = "reminders", indices = [Index("taskId"), Index("projectId")])
data class ReminderEntity(
    @PrimaryKey val id: String,
    val taskId: String? = null,
    val projectId: String? = null,
    /** yyyy-MM-dd */
    val startDate: String,
    /** HH:mm */
    val time: String,
    /** IANA zone ID, or null = follow the device's time zone. */
    val zoneId: String? = null,
    val repeatType: String = "NONE",
    val interval: Int = 1,
    val unit: String = "DAYS",
    val weekdaysMask: Int = 0,
    val endDate: String? = null,
    val repeatUntilCompleted: Boolean = false,
    val nagMinutes: Int = 60,
    val precise: Boolean = false,
    val enabled: Boolean = true,
    val lastFiredAt: Long? = null,
    val snoozedUntil: Long? = null,
    val completedThrough: Long? = null,
    val itemDone: Boolean = false,
    val armedAt: Long? = null,
    /** Cached next trigger (epoch ms) for display and duplicate protection. */
    val nextFireAt: Long? = null,
    val createdAt: Long,
    val updatedAt: Long,
)

@Entity(tableName = "completion_history", indices = [Index("taskId")])
data class CompletionEntity(
    @PrimaryKey val id: String,
    val taskId: String,
    val occurrenceAt: Long? = null,
    val completedAt: Long,
    /** COMPLETED, REOPENED, IMPORTED_DONE */
    val action: String,
)

@Entity(tableName = "settings")
data class SettingEntity(
    @PrimaryKey val key: String,
    val value: String,
)
