package app.projectsteps

import android.content.Context
import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.projectsteps.data.AppDatabase
import app.projectsteps.data.ProjectEntity
import app.projectsteps.data.ProjectRepository
import app.projectsteps.data.ReminderEntity
import app.projectsteps.data.SectionEntity
import app.projectsteps.data.SettingsRepository
import app.projectsteps.data.TaskEntity
import app.projectsteps.reminders.ReminderScheduler
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumented database tests. Run on a device or emulator:
 *   ./gradlew connectedDebugAndroidTest
 */
@RunWith(AndroidJUnit4::class)
class DatabaseTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val dbName = "test-project-steps.db"

    @get:Rule
    val migrationHelper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), AppDatabase::class.java)

    private fun open(): AppDatabase = Room.databaseBuilder(context, AppDatabase::class.java, dbName).build()

    private suspend fun seed(db: AppDatabase): Triple<String, String, List<String>> {
        val now = System.currentTimeMillis()
        db.projects().upsert(ProjectEntity(id = "p", name = "Apple TV", createdAt = now, updatedAt = now, lastActivityAt = now, position = 1.0))
        db.sections().upsert(SectionEntity(id = "s", projectId = "p", title = "Recovery", position = 1.0, createdAt = now, updatedAt = now))
        val ids = listOf("t3" to 3.0, "t1" to 1.0, "t2" to 2.0).map { (id, pos) ->
            db.tasks().upsert(TaskEntity(id = id, projectId = "p", sectionId = "s", title = id, position = pos, createdAt = now, updatedAt = now))
            id
        }
        return Triple("p", "s", ids)
    }

    private fun repo(db: AppDatabase) = ProjectRepository(context, db, ReminderScheduler(context, db, SettingsRepository(db.settings())))

    @Test
    fun completionPersistsAcrossReopen(): Unit = runBlocking {
        context.deleteDatabase(dbName)
        var db = open()
        seed(db)
        repo(db).setTaskDone("t1", true)
        db.close()
        db = open()
        val t = db.tasks().get("t1")!!
        assertEquals("DONE", t.status)
        assertNotNull(t.completedAt)
        assertEquals(1, db.completions().all().count { it.taskId == "t1" && it.action == "COMPLETED" })
        db.close()
        context.deleteDatabase(dbName)
    }

    @Test
    fun undoRestoresCompletion(): Unit = runBlocking {
        context.deleteDatabase(dbName)
        val db = open()
        seed(db)
        val r = repo(db).setTaskDone("t1", true)
        r.undo!!.invoke()
        assertEquals("NOT_STARTED", db.tasks().get("t1")!!.status)
        assertNull(db.tasks().get("t1")!!.completedAt)
        db.close()
        context.deleteDatabase(dbName)
    }

    @Test
    fun taskOrderingAndMove(): Unit = runBlocking {
        context.deleteDatabase(dbName)
        val db = open()
        seed(db)
        val snap = repo(db).snapshot.first()
        assertEquals(listOf("t1", "t2", "t3"), snap.tasksBySection.getValue("s").map { it.id })
        repo(db).moveTask("t3", -1)
        assertEquals(listOf("t1", "t3", "t2"), repo(db).snapshot.first().tasksBySection.getValue("s").map { it.id })
        db.close()
        context.deleteDatabase(dbName)
    }

    @Test
    fun dependencyCyclesAreRefused(): Unit = runBlocking {
        context.deleteDatabase(dbName)
        val db = open()
        seed(db)
        val repo = repo(db)
        assertNull(repo.addDependency("t2", "t1"))
        assertNull(repo.addDependency("t3", "t2"))
        assertNotNull("cycle must be refused", repo.addDependency("t1", "t3"))
        assertEquals(2, db.dependencies().all().size)
        assertTrue("t2" in repo.snapshot.first().blockedByDeps)
        db.close()
        context.deleteDatabase(dbName)
    }

    @Test
    fun softDeleteHidesAndUndoRestores(): Unit = runBlocking {
        context.deleteDatabase(dbName)
        val db = open()
        seed(db)
        val repo = repo(db)
        val r = repo.deleteTask("t2")
        assertTrue(repo.snapshot.first().taskById["t2"] == null)
        r.undo!!.invoke()
        assertNotNull(repo.snapshot.first().taskById["t2"])
        db.close()
        context.deleteDatabase(dbName)
    }

    @Test
    fun recurringCompletionKeepsFutureOccurrences(): Unit = runBlocking {
        context.deleteDatabase(dbName)
        val db = open()
        seed(db)
        val now = System.currentTimeMillis()
        db.reminders().upsert(
            ReminderEntity(
                id = "r", taskId = "t1", projectId = "p", startDate = "2026-01-01", time = "09:00",
                repeatType = "DAILY", armedAt = now, createdAt = now, updatedAt = now,
            )
        )
        repo(db).setTaskDone("t1", true)
        assertEquals("NOT_STARTED", db.tasks().get("t1")!!.status)
        val r = db.reminders().get("r")!!
        assertNotNull(r.completedThrough)
        assertTrue(r.enabled)
        db.close()
        context.deleteDatabase(dbName)
    }

    /** Schema v1 can be created from the exported schema and validates. Extend when adding migrations. */
    @Test
    fun migrationsValidate() {
        val name = "migration-test.db"
        migrationHelper.createDatabase(name, 1).close()
        migrationHelper.runMigrationsAndValidate(name, AppDatabase.VERSION, true, *AppDatabase.MIGRATIONS).close()
    }
}
