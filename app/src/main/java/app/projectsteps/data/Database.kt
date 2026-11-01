package app.projectsteps.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Upsert
import androidx.room.migration.Migration
import kotlinx.coroutines.flow.Flow

/*
 * DAOs use @Upsert (insert-or-update) instead of REPLACE: REPLACE deletes the old row first,
 * which would cascade-delete a project's sections and tasks.
 */

@Dao
interface ProjectDao {
    @Query("SELECT * FROM projects WHERE deletedAt IS NULL ORDER BY position")
    fun observeActive(): Flow<List<ProjectEntity>>

    @Query("SELECT * FROM projects")
    suspend fun all(): List<ProjectEntity>

    @Query("SELECT * FROM projects WHERE id = :id")
    suspend fun get(id: String): ProjectEntity?

    @Upsert
    suspend fun upsert(item: ProjectEntity)

    @Upsert
    suspend fun upsertAll(items: List<ProjectEntity>)

    @Query("SELECT MAX(position) FROM projects")
    suspend fun maxPosition(): Double?

    @Query("DELETE FROM projects WHERE deletedAt IS NOT NULL AND deletedAt < :before")
    suspend fun purgeDeleted(before: Long)

    @Query("DELETE FROM projects")
    suspend fun deleteAll()
}

@Dao
interface SectionDao {
    @Query("SELECT * FROM sections WHERE deletedAt IS NULL ORDER BY position")
    fun observeActive(): Flow<List<SectionEntity>>

    @Query("SELECT * FROM sections")
    suspend fun all(): List<SectionEntity>

    @Query("SELECT * FROM sections WHERE projectId = :projectId")
    suspend fun forProject(projectId: String): List<SectionEntity>

    @Query("SELECT * FROM sections WHERE id = :id")
    suspend fun get(id: String): SectionEntity?

    @Upsert
    suspend fun upsert(item: SectionEntity)

    @Upsert
    suspend fun upsertAll(items: List<SectionEntity>)

    @Query("DELETE FROM sections WHERE deletedAt IS NOT NULL AND deletedAt < :before")
    suspend fun purgeDeleted(before: Long)

    @Query("DELETE FROM sections")
    suspend fun deleteAll()
}

@Dao
interface TaskDao {
    @Query("SELECT * FROM tasks WHERE deletedAt IS NULL ORDER BY position")
    fun observeActive(): Flow<List<TaskEntity>>

    @Query("SELECT * FROM tasks")
    suspend fun all(): List<TaskEntity>

    @Query("SELECT * FROM tasks WHERE projectId = :projectId")
    suspend fun forProject(projectId: String): List<TaskEntity>

    @Query("SELECT * FROM tasks WHERE id = :id")
    suspend fun get(id: String): TaskEntity?

    @Upsert
    suspend fun upsert(item: TaskEntity)

    @Upsert
    suspend fun upsertAll(items: List<TaskEntity>)

    @Query("DELETE FROM tasks WHERE deletedAt IS NOT NULL AND deletedAt < :before")
    suspend fun purgeDeleted(before: Long)

    @Query("DELETE FROM tasks")
    suspend fun deleteAll()
}

@Dao
interface DependencyDao {
    @Query("SELECT * FROM dependencies")
    fun observeAll(): Flow<List<DependencyEntity>>

    @Query("SELECT * FROM dependencies")
    suspend fun all(): List<DependencyEntity>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(item: DependencyEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(items: List<DependencyEntity>)

    @Query("DELETE FROM dependencies WHERE taskId = :taskId AND dependsOnId = :dependsOnId")
    suspend fun delete(taskId: String, dependsOnId: String)

    @Query("DELETE FROM dependencies")
    suspend fun deleteAll()
}

@Dao
interface ReminderDao {
    @Query("SELECT * FROM reminders")
    fun observeAll(): Flow<List<ReminderEntity>>

    @Query("SELECT * FROM reminders")
    suspend fun all(): List<ReminderEntity>

    @Query("SELECT * FROM reminders WHERE id = :id")
    suspend fun get(id: String): ReminderEntity?

    @Query("SELECT * FROM reminders WHERE taskId = :taskId")
    suspend fun forTask(taskId: String): List<ReminderEntity>

    @Query("SELECT * FROM reminders WHERE projectId = :projectId AND taskId IS NULL")
    suspend fun forProject(projectId: String): List<ReminderEntity>

    @Upsert
    suspend fun upsert(item: ReminderEntity)

    @Upsert
    suspend fun upsertAll(items: List<ReminderEntity>)

    /** Targeted update so rescheduling never overwrites concurrent changes to other columns. */
    @Query("UPDATE reminders SET nextFireAt = :next WHERE id = :id")
    suspend fun setNextFireAt(id: String, next: Long?)

    @Query("DELETE FROM reminders WHERE id = :id")
    suspend fun delete(id: String)

    @Query("DELETE FROM reminders")
    suspend fun deleteAll()
}

@Dao
interface CompletionDao {
    @Query("SELECT * FROM completion_history ORDER BY completedAt DESC")
    suspend fun all(): List<CompletionEntity>

    @Query("SELECT * FROM completion_history WHERE taskId = :taskId ORDER BY completedAt DESC")
    fun observeForTask(taskId: String): Flow<List<CompletionEntity>>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(item: CompletionEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(items: List<CompletionEntity>)

    @Query("DELETE FROM completion_history WHERE id = :id")
    suspend fun delete(id: String)

    @Query("DELETE FROM completion_history")
    suspend fun deleteAll()
}

@Dao
interface SettingDao {
    @Query("SELECT * FROM settings")
    fun observeAll(): Flow<List<SettingEntity>>

    @Query("SELECT * FROM settings")
    suspend fun all(): List<SettingEntity>

    @Upsert
    suspend fun upsert(item: SettingEntity)
}

@Database(
    entities = [
        ProjectEntity::class, SectionEntity::class, TaskEntity::class, DependencyEntity::class,
        ReminderEntity::class, CompletionEntity::class, SettingEntity::class,
    ],
    version = AppDatabase.VERSION,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun projects(): ProjectDao
    abstract fun sections(): SectionDao
    abstract fun tasks(): TaskDao
    abstract fun dependencies(): DependencyDao
    abstract fun reminders(): ReminderDao
    abstract fun completions(): CompletionDao
    abstract fun settings(): SettingDao

    companion object {
        const val VERSION = 1
        const val NAME = "project-steps.db"

        /**
         * Schema migrations, oldest first. When the schema changes: bump [VERSION], add a Migration
         * here, and commit the generated JSON in app/schemas. Destructive fallback is never enabled,
         * so a missing migration fails loudly instead of wiping the user's projects.
         */
        val MIGRATIONS: Array<Migration> = arrayOf()

        fun build(context: Context): AppDatabase =
            Room.databaseBuilder(context.applicationContext, AppDatabase::class.java, NAME)
                .addMigrations(*MIGRATIONS)
                .build()
    }
}
