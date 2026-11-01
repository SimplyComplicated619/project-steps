package app.projectsteps.core

import app.projectsteps.core.backup.BCompletion
import app.projectsteps.core.backup.BDependency
import app.projectsteps.core.backup.BProject
import app.projectsteps.core.backup.BReminder
import app.projectsteps.core.backup.BSection
import app.projectsteps.core.backup.BTask
import app.projectsteps.core.backup.BackupCodec
import app.projectsteps.core.backup.BackupData
import app.projectsteps.core.backup.MarkdownExporter
import app.projectsteps.core.importer.DemoData
import app.projectsteps.core.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupTest {

    private fun sample(): BackupData {
        val p = BProject(
            "p1", "Apple TV", "Recovery plan", 2, "IN_PROGRESS", 1L, 2L, "2026-11-05", "18:30", "America/Los_Angeles",
            "Apple-TV.docx", "Project-Checklists.zip", "Apple TV Recovery", "abc", "Project-Checklists.zip/Apple-TV.docx",
            3L, false, 4L, 1.0, null, null,
        )
        val s = BSection("s1", "p1", "Recovery", 1, 1.0, false, "Recovery", "", 0, "Recovery", false, 1L, 2L, null)
        fun t(id: String, title: String, status: String, parent: String? = null) = BTask(
            id, "p1", "s1", parent, "TASK", title, "desc \"quoted\"\nline2", "my note ✓", 1.0, if (parent == null) 0 else 1,
            status, if (status == "DONE") 5L else null, 3, null, null, null, null, "NEEDS_APPROVAL,DESTRUCTIVE",
            "https://example.com", "Apple-TV.docx", "Recovery", 1, "[ ] $title", title, false, false, "fp", 3L, 1L, 2L, null,
        )
        val note = BTask(
            "n1", "p1", "s1", null, "NOTE", "Keep the remote nearby", "", "", 0.5, 0, "NOT_STARTED", null, 0,
            null, null, null, null, "", "", null, null, null, null, null, false, false, null, null, 1L, 1L, null,
        )
        val r = BReminder(
            "r1", "t1", null, "2026-11-02", "09:00", null, "WEEKDAYS", 1, "DAYS", 0b0010101, null, true, 30, false, true,
            null, null, null, false, 10L, 1L, 2L,
        )
        return BackupData(
            BackupCodec.SCHEMA_VERSION, 123L, "1.0", "America/Los_Angeles",
            listOf(p), listOf(s),
            listOf(t("t1", "Enter recovery mode", "NOT_STARTED"), t("t2", "Restore tvOS", "DONE", parent = "t1"), note),
            listOf(BDependency("t2", "t1")), listOf(r),
            listOf(BCompletion("c1", "t2", null, 5L, "COMPLETED")),
            mapOf("default_reminder_time" to "09:00", "quiet_enabled" to "true"),
        )
    }

    @Test
    fun roundTripPreservesEverything() {
        val b = sample()
        val text = BackupCodec.encode(b)
        val back = BackupCodec.decode(text)
        assertEquals(b, back)
        assertTrue(text.contains("\"schemaVersion\": 1"))
        assertTrue(text.contains("\"zoneId\": null"))
    }

    @Test
    fun rejectsNewerSchemaForeignFilesAndDamage() {
        val text = BackupCodec.encode(sample())
        val newer = text.replace("\"schemaVersion\": 1", "\"schemaVersion\": 99")
        assertTrue(assertThrows(BackupCodec.BackupException::class.java) { BackupCodec.decode(newer) }.message!!.contains("newer"))
        assertThrows(BackupCodec.BackupException::class.java) { BackupCodec.decode("{\"hello\": 1}") }
        assertThrows(BackupCodec.BackupException::class.java) { BackupCodec.decode(text.substring(0, text.length / 2)) }
        assertThrows(BackupCodec.BackupException::class.java) { BackupCodec.decode("[1,2,3]") }
    }

    @Test
    fun rejectsDanglingReferences() {
        val b = sample()
        val broken = b.copy(dependencies = listOf(BDependency("t2", "missing")))
        assertThrows(BackupCodec.BackupException::class.java) { BackupCodec.decode(BackupCodec.encode(broken)) }
        val orphan = b.copy(sections = emptyList())
        assertThrows(BackupCodec.BackupException::class.java) { BackupCodec.decode(BackupCodec.encode(orphan)) }
    }

    @Test
    fun markdownExport() {
        val md = MarkdownExporter.project(sample(), "p1")
        assertTrue(md.startsWith("# Apple TV\n"))
        assertTrue(md.contains("_Progress: 1 / 2 completed_"))
        assertTrue(md.contains("## Recovery"))
        assertTrue(md.contains("- [ ] Enter recovery mode  _(NEEDS APPROVAL, DESTRUCTIVE)_"))
        assertTrue(md.contains("  - [x] Restore tvOS"))
        assertTrue(md.contains("Keep the remote nearby"))
        assertEquals("Apple-TV", MarkdownExporter.safeFileName("Apple TV"))
        assertEquals("project", MarkdownExporter.safeFileName("../../"))
    }

    @Test
    fun jsonEdgeCases() {
        val v = mapOf("s" to "a\"b\\c\n\u0001é😀", "n" to -12L, "d" to 1.5, "l" to listOf(true, null, 3L), "e" to emptyMap<String, Any>())
        assertEquals(v, Json.parse(Json.write(v)))
        assertEquals(mapOf("x" to "A"), Json.parse("{\"x\":\"\\u0041\"}"))
        assertThrows(Json.JsonException::class.java) { Json.parse("[".repeat(100) + "]".repeat(100)) }
        assertThrows(Json.JsonException::class.java) { Json.parse("{\"a\":1} trailing") }
        assertThrows(Json.JsonException::class.java) { Json.parse("{'a':1}") }
    }

    @Test
    fun demoDataIsClearlyLabelled() {
        val demo = DemoData.projects()
        assertTrue(demo.all { it.isDemo && it.name.startsWith("DEMO") })
        assertTrue(demo.single().items.any { it.completed })
        assertTrue(demo.single().items.any { !it.completed && it.title.startsWith("Tick this box") })
    }
}
