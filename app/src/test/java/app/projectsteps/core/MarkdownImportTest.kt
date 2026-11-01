package app.projectsteps.core

import app.projectsteps.core.importer.Classifier
import app.projectsteps.core.importer.MarkdownReader
import app.projectsteps.core.model.ItemKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkdownImportTest {

    private val md = """
        # Network

        Notes about the home network.

        ## Router
        - [ ] Back up config
        - [x] Change admin password
          - [ ] Store it in the password manager
        1. Reboot router
        2. Check DHCP leases

        ## Commands
        ```
        ping 192.168.1.1
        ```

        | Done | Step | Notes |
        |------|------|-------|
        | [ ] | Update firmware | check vendor site |
        | [x] | Disable WPS | |
    """.trimIndent()

    @Test
    fun parsesHeadingsTaskListsNumberedNestedCodeAndTables() {
        val d = Classifier.classify(MarkdownReader.parse(md, "Network.md"), null, "sha")
        assertEquals("Network", d.description)
        val byTitle = d.items.associateBy { it.title }
        assertEquals(ItemKind.SECTION, byTitle.getValue("Router").kind)
        assertEquals(ItemKind.NOTE, byTitle.getValue("Notes about the home network.").kind)

        val backup = byTitle.getValue("Back up config")
        assertEquals(ItemKind.TASK, backup.kind)
        assertFalse(backup.completed)
        assertTrue(byTitle.getValue("Change admin password").completed)
        val nested = byTitle.getValue("Store it in the password manager")
        assertEquals(1, nested.level)
        assertEquals(ItemKind.TASK, byTitle.getValue("Reboot router").kind)

        val code = byTitle.getValue("ping 192.168.1.1")
        assertEquals(ItemKind.NOTE, code.kind)

        assertFalse(byTitle.getValue("Update firmware").completed)
        assertEquals("Notes: check vendor site", byTitle.getValue("Update firmware").description)
        assertTrue(byTitle.getValue("Disable WPS").completed)
        assertEquals("Router", backup.source.sectionPath)
    }

    @Test
    fun inlineMarkupAndLinksAreStripped() {
        val (text, links) = MarkdownReader.inline("Read **the** [guide](https://example.com/a) and `code` <https://x.org>")
        assertEquals("Read the guide and code https://x.org", text)
        assertEquals(listOf("https://example.com/a", "https://x.org"), links)
    }

    @Test
    fun binaryDataIsRejected() {
        val e = org.junit.Assert.assertThrows(app.projectsteps.core.importer.ImportException::class.java) {
            MarkdownReader.read(byteArrayOf(1, 0, 2), "bad.md")
        }
        assertTrue(e.userMessage.contains("text"))
    }
}
