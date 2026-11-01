package app.projectsteps.core

import app.projectsteps.core.importer.ImportException
import app.projectsteps.core.importer.ImportLimits
import app.projectsteps.core.importer.MiniXml
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class XmlSecurityTest {
    private val limits = ImportLimits()
    private fun parse(s: String) = MiniXml.parse(s, limits, "test.xml")

    @Test
    fun externalEntitiesAreRejected() {
        val xxe = """<?xml version="1.0"?><!DOCTYPE foo [<!ENTITY xxe SYSTEM "http://attacker.example/x">]><a>&xxe;</a>"""
        assertTrue(assertThrows(ImportException::class.java) { parse(xxe) }.userMessage.contains("DOCTYPE"))
    }

    @Test
    fun billionLaughsIsRejected() {
        val bomb = """<!DOCTYPE lolz [<!ENTITY lol "lol"><!ENTITY lol2 "&lol;&lol;&lol;">]><a>&lol2;</a>"""
        assertThrows(ImportException::class.java) { parse(bomb) }
    }

    @Test
    fun undeclaredEntitiesAreMalformed() {
        assertTrue(assertThrows(ImportException::class.java) { parse("<a>&nbsp;</a>") }.userMessage.contains("malformed"))
    }

    @Test
    fun predefinedAndNumericEntitiesDecode() {
        val e = parse("<a x=\"&quot;q&quot;\">&lt;b&gt; &amp; &#65;&#x42; &apos;</a>")
        assertEquals("<b> & AB '", e.textContent())
        assertEquals("\"q\"", e.attr("x"))
    }

    @Test
    fun malformedXmlIsRejected() {
        assertThrows(ImportException::class.java) { parse("<a><b></a></b>") }
        assertThrows(ImportException::class.java) { parse("<a>") }
        assertThrows(ImportException::class.java) { parse("<a x=1/>") }
        assertThrows(ImportException::class.java) { parse("<a/><b/>") }
        assertThrows(ImportException::class.java) { parse("text") }
    }

    @Test
    fun depthAndNodeLimits() {
        val deep = "<a>".repeat(300) + "</a>".repeat(300)
        assertTrue(assertThrows(ImportException::class.java) { parse(deep) }.userMessage.contains("nested too deeply"))
        val many = "<r>" + "<x/>".repeat(200) + "</r>"
        assertThrows(ImportException::class.java) { MiniXml.parse(many, ImportLimits(maxXmlNodes = 100), "n.xml") }
    }

    @Test
    fun namespacesResolveByUriNotPrefix() {
        val e = parse("""<doc xmlns:q="urn:w" xmlns:r="urn:r"><q:p r:id="7"/></doc>""")
        val p = e.elements().single()
        assertEquals("p", p.local)
        assertEquals("urn:w", p.ns)
        assertEquals("7", p.attr("id", "urn:r"))
    }
}
