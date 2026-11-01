package app.projectsteps.core

import app.projectsteps.core.backup.BTask
import app.projectsteps.core.deps.DependencyGraph
import app.projectsteps.core.deps.DependencyGraph.Edge
import app.projectsteps.core.importer.Classifier
import app.projectsteps.core.importer.DraftItem
import app.projectsteps.core.importer.MarkdownReader
import app.projectsteps.core.merge.ExistingItem
import app.projectsteps.core.merge.Fingerprint
import app.projectsteps.core.merge.MatchKind
import app.projectsteps.core.merge.MergeApplier
import app.projectsteps.core.merge.MergePlanner
import app.projectsteps.core.model.ItemKind
import app.projectsteps.core.model.TaskFlag
import app.projectsteps.core.model.TaskStatus
import app.projectsteps.core.next.ActionCandidate
import app.projectsteps.core.next.NextActionPicker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

class MergeAndDependencyTest {

    // ---------------------------------------------------------------- dependencies

    @Test
    fun cyclesArePrevented() {
        val edges = listOf(Edge("B", "A"), Edge("C", "B"))
        assertTrue("self", DependencyGraph.wouldCreateCycle(edges, "A", "A"))
        assertTrue("direct", DependencyGraph.wouldCreateCycle(edges, "A", "B"))
        assertTrue("transitive", DependencyGraph.wouldCreateCycle(edges, "A", "C"))
        assertFalse("fine", DependencyGraph.wouldCreateCycle(edges, "C", "A"))
        assertFalse("new node", DependencyGraph.wouldCreateCycle(edges, "D", "C"))
    }

    @Test
    fun unfinishedPrerequisitesBlock() {
        val edges = listOf(Edge("B", "A"), Edge("C", "B"))
        assertEquals(setOf("B", "C"), DependencyGraph.blockedTaskIds(edges, doneIds = emptySet()))
        assertEquals(setOf("C"), DependencyGraph.blockedTaskIds(edges, doneIds = setOf("A")))
        assertEquals(emptySet<String>(), DependencyGraph.blockedTaskIds(edges, doneIds = setOf("A", "B")))
    }

    // ---------------------------------------------------------------- merge / re-import

    private fun drafts(md: String): List<DraftItem> =
        Classifier.classify(MarkdownReader.parse(md, "Network.md"), null, "x", "Network.md").items

    private fun existingFrom(items: List<DraftItem>) =
        items.map { ExistingItem("e-" + it.id, it.source.sectionPath, it.source.blockIndex, it.source.originalText, it.kind == ItemKind.SECTION) }

    private val v1 = """
        ## Router
        - [ ] Back up config
        - [ ] Update firmware
        ## Wi-Fi
        - [ ] Update firmware
        - [ ] Rename SSID
    """.trimIndent()

    @Test
    fun identicalReimportMatchesEverythingExactly() {
        val items = drafts(v1)
        val plan = MergePlanner.plan(existingFrom(items), items)
        assertEquals(items.size, plan.matches.size)
        assertTrue(plan.matches.all { it.kind == MatchKind.EXACT })
        assertTrue(plan.newDraftIds.isEmpty())
        assertTrue(plan.missingExistingIds.isEmpty())
    }

    @Test
    fun sameTitleInDifferentSectionsIsNotCrossMatched() {
        val old = drafts(v1)
        val plan = MergePlanner.plan(existingFrom(old), drafts(v1))
        val routerFw = old.first { it.title == "Update firmware" && it.source.sectionPath == "Router" }
        val wifiFw = old.first { it.title == "Update firmware" && it.source.sectionPath == "Wi-Fi" }
        assertEquals("e-" + routerFw.id, plan.matches.first { it.draftId == routerFw.id }.existingId)
        assertEquals("e-" + wifiFw.id, plan.matches.first { it.draftId == wifiFw.id }.existingId)
    }

    @Test
    fun titleAloneNeverMatches() {
        val existing = listOf(ExistingItem("e1", "Router", 1, "- [ ] Rename SSID"))
        // Same text, but in another section and far away in the document.
        val far = drafts("## Guest\n" + "Filler paragraph.\n\n".repeat(12) + "- [ ] Rename SSID\n- [ ] Other")
        val plan = MergePlanner.plan(existing, far)
        assertTrue(plan.matches.isEmpty())
        assertEquals(listOf("e1"), plan.missingExistingIds)
    }

    @Test
    fun editedAddedRemovedAndToggledItems() {
        val old = drafts(v1)
        val v2 = """
            ## Router
            - [x] Back up config
            - [ ] Update the firmware
            - [ ] Enable automatic updates
            ## Wi-Fi
            - [ ] Update firmware
        """.trimIndent()
        val new = drafts(v2)
        val plan = MergePlanner.plan(existingFrom(old), new)
        fun kindFor(title: String) = plan.matches.firstOrNull { m -> new.first { it.title == title }.id == m.draftId }?.kind
        assertEquals("checkbox toggle keeps identity", MatchKind.EXACT, kindFor("Back up config"))
        assertEquals(MatchKind.EDITED, kindFor("Update the firmware"))
        assertTrue(new.first { it.title == "Enable automatic updates" }.id in plan.newDraftIds)
        val renameSsid = old.first { it.title == "Rename SSID" }
        assertTrue("removed item is reported missing, not deleted", "e-" + renameSsid.id in plan.missingExistingIds)
    }

    @Test
    fun renamedHeadingKeepsChildrenViaMovedMatch() {
        val old = drafts("## Prep\n- [ ] Label every cable on the rack\n- [ ] Photograph the wiring")
        val new = drafts("## Preparation\n- [ ] Label every cable on the rack\n- [ ] Photograph the wiring")
        val plan = MergePlanner.plan(existingFrom(old), new)
        assertEquals(MatchKind.EDITED, plan.matches.first { m -> new.first { it.kind == ItemKind.SECTION }.id == m.draftId }.kind)
        assertEquals(2, plan.matches.count { it.kind == MatchKind.MOVED })
    }

    private fun task(title: String, sourceTitle: String = title, status: String = "NOT_STARTED", notes: String = "") = BTask(
        id = "t1", projectId = "p", sectionId = "s", parentId = null, kind = "TASK", title = title, description = "",
        notes = notes, position = 1.0, level = 0, status = status, completedAt = if (status == "DONE") 1000L else null,
        priority = 3, dueDate = "2026-11-02", dueTime = "09:00", dueZoneId = null, waitingOn = null,
        flags = "NEEDS_APPROVAL", links = "", sourceFileName = "Network.md", sourceSectionPath = "Router",
        sourceBlockIndex = 1, sourceText = "- [ ] $sourceTitle", sourceTitle = sourceTitle, sourceCompleted = false,
        sourceMissing = false, fingerprint = null, importedAt = 1L, createdAt = 1L, updatedAt = 1L, deletedAt = null,
    )

    @Test
    fun mergePreservesCompletionNotesSchedulesAndIdentity() {
        val draft = drafts("## Router\n- [ ] Back up config to NAS").single { it.kind == ItemKind.TASK }
        val existing = task("Back up config", status = "DONE", notes = "Used the USB stick")
        val r = MergeApplier.applyToTask(existing, draft, Fingerprint.of("Router", draft.source.originalText), 99L)
        assertEquals("t1", r.task.id) // reminders/dependencies reference this id
        assertEquals("DONE", r.task.status)
        assertEquals(1000L, r.task.completedAt)
        assertEquals("Used the USB stick", r.task.notes)
        assertEquals("2026-11-02", r.task.dueDate)
        assertEquals("09:00", r.task.dueTime)
        assertEquals(3, r.task.priority)
        assertTrue(TaskFlag.NEEDS_APPROVAL in TaskFlag.decode(r.task.flags))
        assertEquals("unedited title follows the source", "Back up config to NAS", r.task.title)
        assertEquals("- [ ] Back up config to NAS", r.task.sourceText)
    }

    @Test
    fun mergeNeverOverwritesUserEditedTitleOrUncompletesWork() {
        val draft = drafts("## Router\n- [ ] Back up config to NAS").single { it.kind == ItemKind.TASK }
        val edited = task("My own wording", sourceTitle = "Back up config")
        val r = MergeApplier.applyToTask(edited, draft, "fp", 99L)
        assertEquals("My own wording", r.task.title)
        assertTrue(r.titleConflict)
        val doneDraft = drafts("## Router\n- [x] Back up config").single { it.kind == ItemKind.TASK }
        val notDone = task("Back up config")
        val r2 = MergeApplier.applyToTask(notDone, doneDraft, "fp", 99L)
        assertEquals("source marks are reported, not silently applied", "NOT_STARTED", r2.task.status)
        assertTrue(r2.sourceNowSaysDone)
    }

    // ---------------------------------------------------------------- next action

    private fun cand(
        id: String, project: String = "p1", pos: Double, status: TaskStatus = TaskStatus.NOT_STARTED,
        due: Instant? = null, blocked: Boolean = false, children: Boolean = false, flags: Set<TaskFlag> = emptySet(),
        priority: Int = 0, activity: Long = 0,
    ) = ActionCandidate(id, project, priority, activity, 1.0, pos, status, flags, due, blocked, children)

    @Test
    fun nextActionOrdering() {
        val now = Instant.parse("2026-10-31T17:00:00Z")
        val zone = ZoneId.of("America/Los_Angeles")
        val list = listOf(
            cand("first-step", pos = 1.0),
            cand("second-step", pos = 2.0),
            cand("done", pos = 0.5, status = TaskStatus.DONE),
            cand("blocked", pos = 0.6, blocked = true),
            cand("waiting", pos = 0.7, flags = setOf(TaskFlag.WAITING_ON)),
            cand("parent", pos = 0.8, children = true),
            cand("due-today", pos = 9.0, due = Instant.parse("2026-10-31T23:00:00Z")),
            cand("overdue", pos = 10.0, due = Instant.parse("2026-10-30T16:00:00Z")),
            cand("other-project", project = "p2", pos = 0.1, priority = 0, activity = -5),
        )
        val ranked = NextActionPicker.rank(list, now, zone).map { it.taskId }
        assertEquals(listOf("overdue", "due-today", "first-step", "second-step", "other-project"), ranked)
        val withBlocked = NextActionPicker.rank(list, now, zone, includeBlocked = true).map { it.taskId }
        assertTrue(withBlocked.containsAll(listOf("blocked", "waiting")))
        assertFalse(withBlocked.contains("done"))
        assertFalse(withBlocked.contains("parent"))
    }
}
