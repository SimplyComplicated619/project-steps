package app.projectsteps.acceptance

import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.projectsteps.ProjectStepsApp
import app.projectsteps.data.BackupRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Stage 4: export and restore with test data only (demo project on a test emulator).
 * Files are written to the app's private cache, the same code path as a user-picked location.
 */
@RunWith(AndroidJUnit4::class)
class Stage4BackupRestoreTest {
    private val app = ApplicationProvider.getApplicationContext<ProjectStepsApp>()
    private val c = app.container

    @Test
    fun exportJsonAndZipThenMergeAndReplaceRestore() = runBlocking {
        val before = c.repo.snapshot.first()
        val doneBefore = before.tasks.count { it.status == "DONE" }

        val json = File(app.cacheDir, "acceptance-backup.json").apply { delete() }
        c.backups.export(Uri.fromFile(json), BackupRepository.Format.JSON)
        assertTrue(json.length() > 0)
        val zip = File(app.cacheDir, "acceptance-backup.zip").apply { delete() }
        c.backups.export(Uri.fromFile(zip), BackupRepository.Format.ZIP)
        val md = File(app.cacheDir, "acceptance-backup.md").apply { delete() }
        c.backups.export(Uri.fromFile(md), BackupRepository.Format.MARKDOWN)
        assertTrue(md.readText().contains("- [x] Tick this box to complete a task"))

        val fromJson = c.backups.read(Uri.fromFile(json))
        val fromZip = c.backups.read(Uri.fromFile(zip))
        assertEquals(before.projects.size, fromJson.projects.size)
        assertEquals(fromJson.tasks.size, fromZip.tasks.size)

        // Change something after the backup.
        val open = before.tasks.first { it.kind == "TASK" && it.status != "DONE" && !c.repo.snapshot.first().isRecurring(it.id) }
        c.repo.setTaskDone(open.id, true)
        assertEquals(doneBefore + 1, c.repo.snapshot.first().tasks.count { it.status == "DONE" })

        // Merge keeps the newer local change and deletes nothing.
        c.backups.restoreMerge(fromJson)
        assertEquals("DONE", c.db.tasks().get(open.id)!!.status)
        assertEquals(before.tasks.size, c.repo.snapshot.first().tasks.size)

        // Replace returns exactly to the backup and keeps a safety copy of the replaced data.
        c.backups.restoreReplace(fromZip)
        assertEquals("NOT_STARTED", c.db.tasks().get(open.id)!!.status)
        assertEquals(doneBefore, c.repo.snapshot.first().tasks.count { it.status == "DONE" })
        val safety = File(app.filesDir, "safety-backups").listFiles().orEmpty()
        assertTrue("safety copy written before replace", safety.isNotEmpty())
    }
}
