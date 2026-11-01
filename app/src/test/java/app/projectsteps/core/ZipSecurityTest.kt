package app.projectsteps.core

import app.projectsteps.core.TestFiles.docx
import app.projectsteps.core.TestFiles.p
import app.projectsteps.core.TestFiles.zip
import app.projectsteps.core.importer.CandidateType
import app.projectsteps.core.importer.ImportException
import app.projectsteps.core.importer.ImportLimits
import app.projectsteps.core.importer.ImportService
import app.projectsteps.core.importer.SafeZip
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ZipSecurityTest {

    private fun rejects(bytes: ByteArray, containing: String, limits: ImportLimits = ImportLimits()) {
        val e = assertThrows(ImportException::class.java) { SafeZip.open(bytes, limits, "test.zip") }
        assertTrue("message '${e.userMessage}' should contain '$containing'", e.userMessage.contains(containing))
    }

    @Test
    fun pathTraversalIsRejected() {
        rejects(zip("../evil.docx" to byteArrayOf(1)), "path traversal")
        rejects(zip("docs/../../evil.docx" to byteArrayOf(1)), "path traversal")
        rejects(zip("/etc/passwd" to byteArrayOf(1)), "absolute path")
        rejects(zip("C:/Windows/evil.docx" to byteArrayOf(1)), "drive letter")
        rejects(zip("..\\evil.docx" to byteArrayOf(1)), "path traversal")
    }

    @Test
    fun excessiveFolderNestingIsRejected() {
        rejects(zip("a/b/c/d/e/f/g/h/i/deep.docx" to byteArrayOf(1)), "nested too deeply")
    }

    @Test
    fun malformedArchivesAreRejected() {
        rejects("PK\u0003\u0004 this is not really a zip".toByteArray(), "not a valid ZIP")
        val good = zip("a.md" to "# hi".toByteArray())
        rejects(good.copyOfRange(0, good.size / 2), "")
        assertThrows(ImportException::class.java) { ImportService.inspect(ByteArray(0), "empty.zip") }
        val e = assertThrows(ImportException::class.java) { ImportService.inspect("PK\u0003\u0004junk".toByteArray(), "Project-Checklists.zip") }
        assertTrue(e.userMessage.contains("Project-Checklists.zip"))
    }

    @Test
    fun zipBombByCompressionRatioIsRejected() {
        val zeros = ByteArray(5 * 1024 * 1024)
        rejects(zip("bomb.md" to zeros), "compression ratio")
    }

    @Test
    fun entryLargerThanDeclaredSizeIsRejected() {
        val data = ByteArray(5000) { (it % 7).toByte() }
        val honest = zip("big.md" to data)
        // Lie about the uncompressed size (offset 22 in local header, 24 in central directory).
        val lying = TestFiles.patchFirstEntry(honest, 22, 24, 100)
        val z = SafeZip.open(lying, ImportLimits(), "lying.zip")
        val e = assertThrows(ImportException::class.java) { z.read(z.entries.single()) }
        assertTrue(e.userMessage, e.userMessage.contains("declared size") || e.userMessage.contains("damaged"))
    }

    @Test
    fun extractionLimitsAreEnforced() {
        val limits = ImportLimits(maxEntryBytes = 1000, maxTotalUncompressedBytes = 2500, maxEntries = 3)
        val z = SafeZip.open(zip("a.md" to ByteArray(2000) { 'a'.code.toByte() }), limits, "x.zip")
        assertTrue(assertThrows(ImportException::class.java) { z.read(z.entries.single()) }.userMessage.contains("too large"))
        rejects(zip("a.md" to ByteArray(900), "b.md" to ByteArray(900), "c.md" to ByteArray(900)), "would expand", limits)
        rejects(zip("1" to byteArrayOf(1), "2" to byteArrayOf(1), "3" to byteArrayOf(1), "4" to byteArrayOf(1)), "too many files", limits)
        rejects(ByteArray(2000), "too large", ImportLimits(maxInputBytes = 1000))
    }

    @Test
    fun encryptedEntriesAreRejected() {
        val plain = zip("secret.docx" to byteArrayOf(1, 2, 3))
        rejects(TestFiles.patchFirstEntry(plain, 6, 8, 0x0001 or 0x0008), "encrypted")
    }

    @Test
    fun unsupportedCompressionIsReportedPerFile() {
        val z = zip("Apple-TV.docx" to docx(p("[ ] a")), "Other.docx" to docx(p("[ ] b")))
        val patched = TestFiles.patchFirstEntry(z, 8, 10, 12) // method 12 = bzip2
        val src = ImportService.inspect(patched, "Project-Checklists.zip")
        val first = src.candidates.first { it.displayName == "Apple-TV.docx" }
        assertEquals(CandidateType.UNSUPPORTED, first.type)
        assertTrue(first.note!!.contains("bzip2"))
        assertTrue(src.candidates.first { it.displayName == "Other.docx" }.selectable)
    }

    @Test
    fun duplicateEntryNamesAreRejected() {
        val a = zip("x.md" to "one".toByteArray(), "y.md" to "two".toByteArray())
        // Rename the second entry to the first name in its local header and central directory.
        val out = a.copyOf()
        var idx = 0
        while (true) {
            idx = TestFiles.indexOf(out.copyOfRange(idx, out.size), "y.md".toByteArray()).let { if (it < 0) -1 else it + idx }
            if (idx < 0) break
            out[idx] = 'x'.code.toByte()
            idx += 1
        }
        rejects(out, "same path twice")
    }

    @Test
    fun crcMismatchIsDetected() {
        val stored = zip("a.md" to "hello world".toByteArray(), stored = true)
        val i = TestFiles.indexOf(stored, "hello".toByteArray())
        val corrupted = stored.copyOf().also { it[i] = 'j'.code.toByte() }
        val z = SafeZip.open(corrupted, ImportLimits(), "c.zip")
        assertTrue(assertThrows(ImportException::class.java) { z.read(z.entries.single()) }.userMessage.contains("checksum"))
    }

    @Test
    fun archiveListsUnsupportedNestedAndSystemFiles() {
        val archive = zip(
            "Project-Checklists/START-HERE.docx" to docx(p("[ ] Read me")),
            "Project-Checklists/Apple-TV.docx" to docx(p("[ ] Recovery test")),
            "Project-Checklists/notes.pdf" to byteArrayOf(1, 2),
            "Project-Checklists/inner.zip" to zip("x.docx" to byteArrayOf(1)),
            "__MACOSX/Project-Checklists/._Apple-TV.docx" to byteArrayOf(0),
            "Project-Checklists/old.doc" to byteArrayOf(1),
        )
        val src = ImportService.inspect(archive, "Project-Checklists.zip")
        assertTrue(src.isArchive)
        val types = src.candidates.associate { it.displayName to it.type }
        assertEquals(CandidateType.DOCX, types["START-HERE.docx"])
        assertEquals(CandidateType.UNSUPPORTED, types["notes.pdf"])
        assertEquals(CandidateType.NESTED_ARCHIVE, types["inner.zip"])
        assertEquals(CandidateType.SYSTEM_FILE, types["._Apple-TV.docx"])
        assertEquals(CandidateType.UNSUPPORTED, types["old.doc"])
        assertFalse(src.candidates.first { it.displayName == "inner.zip" }.selectable)
    }

    @Test
    fun onlySelectedDocumentsAreParsedAndBadOnesDontStopOthers() {
        val archive = zip(
            "START-HERE.docx" to docx(p("[ ] Read me")),
            "Apple-TV.docx" to docx(p("[ ] Recovery test")),
            "Broken.docx" to "not a docx".toByteArray(),
        )
        val src = ImportService.inspect(archive, "Project-Checklists.zip")
        val result = ImportService.parse(src, setOf("Apple-TV.docx", "Broken.docx"))
        assertEquals(listOf("Apple TV"), result.projects.map { it.name })
        assertEquals("Broken.docx", result.failures.single().first)
        assertEquals("Project-Checklists.zip", result.projects.single().archiveName)
    }

    @Test
    fun singleDocxAndMarkdownFilesAreAccepted() {
        val d = ImportService.inspect(docx(p("[ ] a")), "Network.docx")
        assertFalse(d.isArchive)
        assertEquals(1, ImportService.parse(d, setOf("Network.docx")).projects.size)
        val m = ImportService.inspect("# T\n- [ ] x".toByteArray(), "Plan.md")
        assertEquals(CandidateType.MARKDOWN, m.candidates.single().type)
        assertThrows(ImportException::class.java) { ImportService.inspect(byteArrayOf(0, 1, 2, 3, 0), "image.png") }
    }
}
