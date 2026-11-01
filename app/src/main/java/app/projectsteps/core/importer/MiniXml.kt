package app.projectsteps.core.importer

/**
 * A deliberately small, safe XML parser for Office Open XML parts.
 *
 * Security properties (by construction, not by configuration):
 *  - DOCTYPE and ENTITY declarations are rejected outright, so external entities, entity
 *    expansion ("billion laughs") and external resource loading are impossible.
 *  - Only the five predefined entities and numeric character references are decoded.
 *  - Nothing is ever fetched: there is no resolver, no network and no file access.
 *  - Size, depth and node-count limits are enforced.
 */
object MiniXml {

    fun parse(bytes: ByteArray, limits: ImportLimits, label: String): XmlElement {
        if (bytes.size > limits.maxXmlBytes) {
            throw ImportException("“$label” has an XML part that is too large (${bytes.size.toLong().toMbString()}).")
        }
        return parse(decode(bytes, label), limits, label)
    }

    fun parse(text: String, limits: ImportLimits, label: String): XmlElement =
        Parser(text, limits, label).run()

    private fun decode(bytes: ByteArray, label: String): String {
        if (bytes.size >= 2) {
            val b0 = bytes[0].toInt() and 0xff
            val b1 = bytes[1].toInt() and 0xff
            if (b0 == 0xFE && b1 == 0xFF) return String(bytes, 2, bytes.size - 2, Charsets.UTF_16BE)
            if (b0 == 0xFF && b1 == 0xFE) return String(bytes, 2, bytes.size - 2, Charsets.UTF_16LE)
        }
        if (bytes.size >= 3 && (bytes[0].toInt() and 0xff) == 0xEF && (bytes[1].toInt() and 0xff) == 0xBB && (bytes[2].toInt() and 0xff) == 0xBF) {
            return String(bytes, 3, bytes.size - 3, Charsets.UTF_8)
        }
        return String(bytes, Charsets.UTF_8)
    }

    private class Parser(val s: String, val limits: ImportLimits, val label: String) {
        var pos = 0
        var nodes = 0
        val stack = ArrayList<XmlElement>()
        val nsStack = ArrayList<Map<String, String>>()
        var root: XmlElement? = null

        fun malformed(why: String): Nothing =
            throw ImportException("“$label” contains malformed XML ($why).")

        fun run(): XmlElement {
            while (pos < s.length) {
                val lt = s.indexOf('<', pos)
                if (lt < 0) {
                    text(s.substring(pos))
                    pos = s.length
                    break
                }
                if (lt > pos) text(s.substring(pos, lt))
                pos = lt
                when {
                    s.startsWith("<?", pos) -> {
                        val end = s.indexOf("?>", pos + 2)
                        if (end < 0) malformed("unterminated processing instruction")
                        pos = end + 2
                    }
                    s.startsWith("<!--", pos) -> {
                        val end = s.indexOf("-->", pos + 4)
                        if (end < 0) malformed("unterminated comment")
                        pos = end + 3
                    }
                    s.startsWith("<![CDATA[", pos) -> {
                        val end = s.indexOf("]]>", pos + 9)
                        if (end < 0) malformed("unterminated CDATA")
                        rawText(s.substring(pos + 9, end))
                        pos = end + 3
                    }
                    s.startsWith("<!", pos) -> throw ImportException(
                        "“$label” contains a DOCTYPE or entity declaration. These are not allowed in imported documents (protects against XML external entity attacks)."
                    )
                    s.startsWith("</", pos) -> endTag()
                    else -> startTag()
                }
            }
            if (stack.isNotEmpty()) malformed("unclosed element <${stack.last().qName}>")
            return root ?: malformed("no root element")
        }

        private fun text(raw: String) {
            if (stack.isEmpty()) {
                if (raw.isNotBlank()) malformed("text outside the root element")
                return
            }
            rawText(decodeEntities(raw))
        }

        private fun rawText(t: String) {
            if (t.isEmpty()) return
            if (stack.isEmpty()) {
                if (t.isNotBlank()) malformed("text outside the root element")
                return
            }
            countNode()
            stack.last().children.add(t)
        }

        private fun countNode() {
            nodes++
            if (nodes > limits.maxXmlNodes) throw ImportException("“$label” is too complex to import (too many XML nodes).")
        }

        private fun readName(): String {
            val start = pos
            while (pos < s.length) {
                val c = s[pos]
                if (c == ' ' || c == '\t' || c == '\n' || c == '\r' || c == '>' || c == '/' || c == '=') break
                pos++
            }
            if (pos == start) malformed("missing name")
            return s.substring(start, pos)
        }

        private fun skipWs() {
            while (pos < s.length && (s[pos] == ' ' || s[pos] == '\t' || s[pos] == '\n' || s[pos] == '\r')) pos++
        }

        private fun startTag() {
            pos++ // <
            val qName = readName()
            val rawAttrs = ArrayList<Pair<String, String>>()
            var selfClosing = false
            while (true) {
                skipWs()
                if (pos >= s.length) malformed("unterminated tag <$qName>")
                val c = s[pos]
                if (c == '>') { pos++; break }
                if (c == '/') {
                    if (pos + 1 < s.length && s[pos + 1] == '>') { pos += 2; selfClosing = true; break }
                    malformed("bad '/' in tag <$qName>")
                }
                val an = readName()
                skipWs()
                if (pos >= s.length || s[pos] != '=') malformed("attribute without value in <$qName>")
                pos++
                skipWs()
                if (pos >= s.length) malformed("unterminated attribute")
                val quote = s[pos]
                if (quote != '"' && quote != '\'') malformed("unquoted attribute in <$qName>")
                val end = s.indexOf(quote, pos + 1)
                if (end < 0) malformed("unterminated attribute value")
                val rawValue = s.substring(pos + 1, end)
                if (rawValue.indexOf('<') >= 0) malformed("'<' inside attribute value")
                rawAttrs.add(an to decodeEntities(rawValue))
                pos = end + 1
            }

            // Namespace scope for this element.
            val parentNs = nsStack.lastOrNull() ?: DEFAULT_NS
            var scope: MutableMap<String, String>? = null
            for ((an, av) in rawAttrs) {
                if (an == "xmlns" || an.startsWith("xmlns:")) {
                    if (scope == null) scope = HashMap(parentNs)
                    scope[if (an == "xmlns") "" else an.substring(6)] = av
                }
            }
            val ns: Map<String, String> = scope ?: parentNs

            val (prefix, local) = split(qName)
            val elementNs = ns[prefix] ?: ""
            val attrs = ArrayList<XmlAttr>(rawAttrs.size)
            for ((an, av) in rawAttrs) {
                if (an == "xmlns" || an.startsWith("xmlns:")) continue
                val (ap, al) = split(an)
                val attrNs = if (ap.isEmpty()) "" else ns[ap] ?: ""
                attrs.add(XmlAttr(an, attrNs, al, av))
            }
            val el = XmlElement(qName, elementNs, local, attrs, ArrayList())
            countNode()
            if (stack.isEmpty()) {
                if (root != null) malformed("more than one root element")
                root = el
            } else {
                stack.last().children.add(el)
            }
            if (!selfClosing) {
                stack.add(el)
                nsStack.add(ns)
                if (stack.size > limits.maxXmlDepth) throw ImportException("“$label” is nested too deeply to import safely.")
            }
        }

        private fun endTag() {
            pos += 2
            val qName = readName()
            skipWs()
            if (pos >= s.length || s[pos] != '>') malformed("bad closing tag </$qName>")
            pos++
            if (stack.isEmpty()) malformed("unexpected closing tag </$qName>")
            val open = stack.removeAt(stack.size - 1)
            nsStack.removeAt(nsStack.size - 1)
            if (open.qName != qName) malformed("expected </${open.qName}> but found </$qName>")
        }

        private fun split(q: String): Pair<String, String> {
            val i = q.indexOf(':')
            return if (i < 0) "" to q else q.substring(0, i) to q.substring(i + 1)
        }

        private fun decodeEntities(raw: String): String {
            if (raw.indexOf('&') < 0) return raw
            val sb = StringBuilder(raw.length)
            var i = 0
            while (i < raw.length) {
                val c = raw[i]
                if (c != '&') { sb.append(c); i++; continue }
                val semi = raw.indexOf(';', i + 1)
                if (semi < 0 || semi - i > 12) malformed("bad entity reference")
                val name = raw.substring(i + 1, semi)
                when {
                    name == "lt" -> sb.append('<')
                    name == "gt" -> sb.append('>')
                    name == "amp" -> sb.append('&')
                    name == "quot" -> sb.append('"')
                    name == "apos" -> sb.append('\'')
                    name.startsWith("#x") || name.startsWith("#X") -> appendCodePoint(sb, name.substring(2).toIntOrNull(16))
                    name.startsWith("#") -> appendCodePoint(sb, name.substring(1).toIntOrNull())
                    else -> malformed("undeclared entity &$name;")
                }
                i = semi + 1
            }
            return sb.toString()
        }

        private fun appendCodePoint(sb: StringBuilder, cp: Int?) {
            if (cp == null || cp < 0 || cp > 0x10FFFF || (cp in 0xD800..0xDFFF)) malformed("bad character reference")
            if (cp == 0) malformed("NUL character reference")
            sb.appendCodePoint(cp)
        }
    }

    private val DEFAULT_NS: Map<String, String> = mapOf("xml" to "http://www.w3.org/XML/1998/namespace")
}

class XmlAttr(val qName: String, val ns: String, val local: String, val value: String)

class XmlElement(
    val qName: String,
    val ns: String,
    val local: String,
    val attrs: List<XmlAttr>,
    /** Children are either [XmlElement] or [String] text nodes. */
    val children: MutableList<Any>,
) {
    /** First attribute with the given local name (optionally restricted to a namespace URI). */
    fun attr(local: String, ns: String? = null): String? =
        attrs.firstOrNull { it.local == local && (ns == null || it.ns == ns) }?.value

    fun elements(): List<XmlElement> = children.filterIsInstance<XmlElement>()

    fun child(local: String): XmlElement? = children.firstOrNull { it is XmlElement && it.local == local } as XmlElement?

    fun childrenNamed(local: String): List<XmlElement> = children.filterIsInstance<XmlElement>().filter { it.local == local }

    /** Depth-first search for descendants with the given local name. */
    fun descendants(local: String): List<XmlElement> {
        val out = ArrayList<XmlElement>()
        fun walk(e: XmlElement) {
            for (c in e.children) if (c is XmlElement) {
                if (c.local == local) out.add(c)
                walk(c)
            }
        }
        walk(this)
        return out
    }

    fun textContent(): String {
        val sb = StringBuilder()
        fun walk(e: XmlElement) {
            for (c in e.children) if (c is String) sb.append(c) else if (c is XmlElement) walk(c)
        }
        walk(this)
        return sb.toString()
    }

    override fun toString(): String = "<$qName>"
}
