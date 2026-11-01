package app.projectsteps.core

import app.projectsteps.core.TestFiles.NUMBERING
import app.projectsteps.core.TestFiles.checkboxP
import app.projectsteps.core.TestFiles.docx
import app.projectsteps.core.TestFiles.legacyCheckboxP
import app.projectsteps.core.TestFiles.p
import app.projectsteps.core.importer.Classifier
import app.projectsteps.core.importer.DocxReader
import app.projectsteps.core.importer.DraftItem
import app.projectsteps.core.importer.DraftProject
import app.projectsteps.core.importer.ImportException
import app.projectsteps.core.model.ItemKind
import app.projectsteps.core.model.TaskFlag
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class DocxImportTest {

    private fun classify(bytes: ByteArray, name: String = "Apple-TV.docx"): DraftProject =
        Classifier.classify(DocxReader.read(bytes, name), "Project-Checklists.zip", "sha")

    private fun DraftProject.item(title: String): DraftItem =
        items.firstOrNull { it.title == title } ?: error("No item '$title' in ${items.map { it.title }}")

    @Test
    fun headingsBecomeSectionsAndTitleBecomesDescription() {
        val d = classify(
            docx(
                p("Apple TV Recovery Plan", "Title") +
                    p("Preparation", "Heading1") + p("Gather cables") +
                    p("Hardware", "Heading1") + p("Cables", "Heading2")
            )
        )
        assertEquals("Apple TV", d.name)
        assertEquals("Apple TV Recovery Plan", d.description)
        val sections = d.items.filter { it.kind == ItemKind.SECTION }
        assertEquals(listOf("Preparation", "Hardware", "Cables"), sections.map { it.title })
        assertEquals(listOf(1, 1, 2), sections.map { it.headingLevel })
        assertEquals("Hardware", d.item("Cables").source.sectionPath)
        assertEquals(ItemKind.NOTE, d.item("Gather cables").kind)
        assertEquals("Preparation", d.item("Gather cables").source.sectionPath)
    }

    @Test
    fun singleTopHeadingIsTreatedAsDocumentTitle() {
        val d = classify(docx(p("Network Checklist", "Heading1") + p("Steps", "Heading2") + p("[ ] Check router")))
        assertEquals("Network Checklist", d.description)
        assertEquals(listOf("Steps"), d.items.filter { it.kind == ItemKind.SECTION }.map { it.title })
    }

    @Test
    fun textCheckboxMarkers() {
        val d = classify(
            docx(
                p("☐ Unchecked glyph") + p("☑ Checked glyph") + p("[ ] Empty brackets") +
                    p("[x] Lower x") + p("[X] Upper X") + p("✓ Check mark") + p("- [ ] Dash then box") + p("☒ Crossed box")
            )
        )
        val expect = mapOf(
            "Unchecked glyph" to false, "Checked glyph" to true, "Empty brackets" to false, "Lower x" to true,
            "Upper X" to true, "Check mark" to true, "Dash then box" to false, "Crossed box" to true,
        )
        for ((title, done) in expect) {
            val item = d.item(title)
            assertEquals("kind of $title", ItemKind.TASK, item.kind)
            assertEquals("completed of $title", done, item.completed)
            assertFalse("review of $title", item.needsReview)
            if (done) assertNotNull(item.completionMarker) else assertNull(item.completionMarker)
        }
    }

    @Test
    fun wordCheckboxContentControlsAndLegacyFormFields() {
        val d = classify(
            docx(
                checkboxP("Ticked control", true) + checkboxP("Empty control", false) +
                    legacyCheckboxP("Ticked legacy", true) + legacyCheckboxP("Empty legacy", false)
            )
        )
        assertTrue(d.item("Ticked control").completed)
        assertFalse(d.item("Empty control").completed)
        assertTrue(d.item("Ticked legacy").completed)
        assertFalse(d.item("Empty legacy").completed)
        assertTrue(d.items.all { it.kind == ItemKind.TASK })
        // The glyph inside the content control is state, not text.
        assertFalse(d.items.any { it.title.contains("☒") || it.title.contains("☐") })
    }

    @Test
    fun numberedStepsAndNestedSubsteps() {
        val d = classify(
            docx(
                p("Unplug the Apple TV", numId = 1) + p("Wait ten seconds", numId = 1, ilvl = 1) +
                    p("Hold Menu and Down", numId = 1) + p("Step 4: Connect USB-C") + p("5) Open Finder"),
                numbering = NUMBERING,
            )
        )
        val tasks = d.items.filter { it.kind == ItemKind.TASK }
        assertEquals(listOf("Unplug the Apple TV", "Wait ten seconds", "Hold Menu and Down", "Connect USB-C", "Open Finder"), tasks.map { it.title })
        assertEquals(listOf(0, 1, 0, 0, 0), tasks.map { it.level })
        assertTrue(tasks.none { it.completed })
    }

    @Test
    fun bulletsAreTasksWhenDocumentHasNoCheckboxes() {
        val d = classify(docx(p("Buy HDMI cable", numId = 2) + p("• Label the ports"), numbering = NUMBERING))
        assertEquals(ItemKind.TASK, d.item("Buy HDMI cable").kind)
        assertEquals(ItemKind.TASK, d.item("Label the ports").kind)
    }

    @Test
    fun bulletsAreReviewableNotesWhenDocumentUsesCheckboxes() {
        val d = classify(docx(p("[ ] Real task") + p("Context bullet", numId = 2), numbering = NUMBERING))
        val bullet = d.item("Context bullet")
        assertEquals(ItemKind.NOTE, bullet.kind)
        assertTrue(bullet.needsReview)
    }

    @Test
    fun checkboxBulletGlyphsFromNumbering() {
        val d = classify(
            docx(p("Glyph box", numId = 3) + p("Wingdings box", numId = 4) + p("Tick bullet", numId = 5), numbering = NUMBERING)
        )
        assertEquals(ItemKind.TASK, d.item("Glyph box").kind)
        assertEquals(ItemKind.TASK, d.item("Wingdings box").kind)
        val tick = d.item("Tick bullet")
        assertEquals(ItemKind.TASK, tick.kind)
        assertFalse("✓ bullet style must not mark done", tick.completed)
        assertTrue(tick.needsReview)
    }

    @Test
    fun plainParagraphsAreNotesAndProseNeverCompletesATask() {
        val d = classify(
            docx(
                p("This whole section is already done and finished.") +
                    p("Note: keep the remote nearby") + p("[ ] Pair remote (done)")
            )
        )
        val prose = d.item("This whole section is already done and finished.")
        assertEquals(ItemKind.NOTE, prose.kind)
        assertFalse(prose.completed)
        assertEquals(ItemKind.NOTE, d.item("Note: keep the remote nearby").kind)
        val pair = d.item("Pair remote (done)")
        assertFalse("word 'done' is not an explicit marker", pair.completed)
        assertTrue(pair.needsReview)
    }

    @Test
    fun trailingCheckMarkIsCompletedButFlaggedForReview() {
        val d = classify(docx(p("Update tvOS ✓")))
        val t = d.item("Update tvOS")
        assertEquals(ItemKind.TASK, t.kind)
        assertTrue(t.completed)
        assertTrue(t.needsReview)
    }

    @Test
    fun boldOrCapsShortLinesAreUncertainSections() {
        val bold = """<w:p><w:r><w:rPr><w:b/></w:rPr><w:t>Recovery</w:t></w:r></w:p>"""
        val d = classify(docx(bold + p("[ ] Enter DFU mode") + p("TESTING") + p("[ ] Play a video")))
        val rec = d.item("Recovery")
        assertEquals(ItemKind.SECTION, rec.kind)
        assertTrue(rec.needsReview)
        assertEquals("Recovery", d.item("Enter DFU mode").source.sectionPath)
        assertEquals("TESTING", d.item("Play a video").source.sectionPath)
    }

    @Test
    fun codeParagraphsAreNotesNeverTasks() {
        val d = classify(docx(p("adb shell pm list packages", "Code") + p("[ ] Run the check")))
        val code = d.item("adb shell pm list packages")
        assertEquals(ItemKind.NOTE, code.kind)
        assertTrue(code.reason.contains("never run"))
    }

    @Test
    fun flagsAreDetected() {
        val d = classify(
            docx(
                p("[ ] ⚠ NEEDS APPROVAL: Reset Apple TV to factory settings") +
                    p("[ ] Waiting on: replacement remote from Apple") + p("[ ] Configure VLANs (blocked by switch)")
            )
        )
        val reset = d.items[0]
        assertTrue(TaskFlag.NEEDS_APPROVAL in reset.flags)
        assertTrue(TaskFlag.DESTRUCTIVE in reset.flags)
        val waiting = d.items[1]
        assertTrue(TaskFlag.WAITING_ON in waiting.flags)
        assertEquals("replacement remote from Apple", waiting.waitingOn)
        assertTrue(TaskFlag.BLOCKED in d.items[2].flags)
    }

    @Test
    fun checklistTableRows() {
        fun cell(t: String) = "<w:tc><w:p><w:r><w:t>${TestFiles.esc(t)}</w:t></w:r></w:p></w:tc>"
        fun boldCell(t: String) = "<w:tc><w:p><w:r><w:rPr><w:b/></w:rPr><w:t>$t</w:t></w:r></w:p></w:tc>"
        val table = "<w:tbl>" +
            "<w:tr>${boldCell("Done")}${boldCell("Step")}${boldCell("Notes")}</w:tr>" +
            "<w:tr>${cell("☐")}${cell("Back up router config")}${cell("Save to NAS")}</w:tr>" +
            "<w:tr>${cell("☑")}${cell("Note current IP")}${cell("")}</w:tr>" +
            "</w:tbl>"
        val d = classify(docx(table))
        val header = d.items[0]
        assertEquals(ItemKind.NOTE, header.kind)
        assertFalse("header row excluded by default but still shown", header.include)
        val backup = d.item("Back up router config")
        assertEquals(ItemKind.TASK, backup.kind)
        assertFalse(backup.completed)
        assertEquals("Notes: Save to NAS", backup.description)
        assertTrue(d.item("Note current IP").completed)
    }

    @Test
    fun hyperlinksTrackedDeletionsAndFieldInstructions() {
        val rels = """<Relationship Id="rIdL" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/hyperlink" Target="https://support.apple.com/en-us/HT201335" TargetMode="External"/>"""
        val body = """<w:p><w:r><w:t xml:space="preserve">[ ] Read </w:t></w:r><w:hyperlink r:id="rIdL"><w:r><w:t>Apple guide</w:t></w:r></w:hyperlink><w:del><w:r><w:delText> DELETED</w:delText></w:r></w:del><w:r><w:fldChar w:fldCharType="begin"/></w:r><w:r><w:instrText> PAGE </w:instrText></w:r><w:r><w:fldChar w:fldCharType="separate"/></w:r><w:r><w:t> p1</w:t></w:r><w:r><w:fldChar w:fldCharType="end"/></w:r></w:p>"""
        val d = classify(docx(body, rels = rels))
        val t = d.items.single()
        assertEquals("Read Apple guide p1", t.title)
        assertEquals(listOf("https://support.apple.com/en-us/HT201335"), t.links)
    }

    @Test
    fun unsafeLinkSchemesAreDropped() {
        val rels = """<Relationship Id="rIdL" Type="x/hyperlink" Target="javascript:alert(1)" TargetMode="External"/>"""
        val d = classify(docx("""<w:p><w:hyperlink r:id="rIdL"><w:r><w:t>[ ] Click</w:t></w:r></w:hyperlink></w:p>""", rels = rels))
        assertTrue(d.items.single().links.isEmpty())
    }

    @Test
    fun sourceTraceabilityIsPreserved() {
        val d = classify(docx(p("Hardware", "Heading1") + p("[x] Order cable")))
        val t = d.item("Order cable")
        assertEquals("Apple-TV.docx", t.source.fileName)
        assertEquals("Project-Checklists.zip", t.source.archiveName)
        assertEquals("Hardware", t.source.sectionPath)
        assertEquals("[x] Order cable", t.source.originalText)
        assertEquals(1, t.source.blockIndex)
    }

    @Test
    fun doctypeInDocumentIsRejected() {
        val evil = TestFiles.zip(
            "[Content_Types].xml" to "<Types/>".toByteArray(),
            "word/document.xml" to """<?xml version="1.0"?><!DOCTYPE d [<!ENTITY x SYSTEM "file:///etc/passwd">]><w:document xmlns:w="${TestFiles.W}"><w:body><w:p><w:r><w:t>&x;</w:t></w:r></w:p></w:body></w:document>""".toByteArray(),
        )
        val e = assertThrows(ImportException::class.java) { DocxReader.read(evil, "evil.docx") }
        assertTrue(e.userMessage.contains("DOCTYPE"))
    }

    @Test
    fun legacyDocAndGarbageAreRejectedWithMessages() {
        val ole = byteArrayOf(0xD0.toByte(), 0xCF.toByte(), 0x11, 0xE0.toByte(), 0xA1.toByte(), 0xB1.toByte(), 0x1A, 0xE1.toByte(), 0, 0)
        assertTrue(assertThrows(ImportException::class.java) { DocxReader.read(ole, "old.doc") }.userMessage.contains("legacy"))
        assertThrows(ImportException::class.java) { DocxReader.read("hello".toByteArray(), "x.docx") }
    }

    @Test
    fun projectNamesFromFileNames() {
        assertEquals("Start Here", Classifier.projectNameFromFile("START-HERE.docx"))
        assertEquals("Apple TV", Classifier.projectNameFromFile("Apple-TV.docx"))
        assertEquals("Personal Backups", Classifier.projectNameFromFile("Personal-Backups.docx"))
        assertEquals("Network", Classifier.projectNameFromFile("Network.docx"))
        assertEquals("Maintenance", Classifier.projectNameFromFile("folder/Maintenance.docx"))
    }

    @Test
    fun macrosAndImagesProduceWarningsNotContent() {
        val img = """<w:p><w:r><w:drawing/></w:r><w:r><w:t>[ ] Photo step</w:t></w:r></w:p>"""
        val d = classify(docx(img, extra = listOf("word/vbaProject.bin" to byteArrayOf(1, 2, 3))))
        assertEquals("Photo step", d.items.single().title)
        assertTrue(d.warnings.any { it.contains("Macros") })
        assertTrue(d.warnings.any { it.contains("image") })
    }
}
