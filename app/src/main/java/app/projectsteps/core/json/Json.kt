package app.projectsteps.core.json

/**
 * Small, dependency-free JSON reader/writer used for backups.
 *
 * Values map to: Map<String, Any?>, List<Any?>, String, Long, Double, Boolean, null.
 * The parser enforces a nesting-depth limit and rejects trailing garbage, so a hostile
 * backup file cannot blow the stack.
 */
object Json {

    class JsonException(message: String) : Exception(message)

    fun parse(text: String, maxDepth: Int = 64): Any? {
        val parser = Parser(text, maxDepth)
        parser.skipWhitespace()
        val value = parser.readValue(0)
        parser.skipWhitespace()
        if (!parser.atEnd()) throw JsonException("Unexpected data after JSON value at position ${parser.pos}")
        return value
    }

    fun write(value: Any?, pretty: Boolean = true): String {
        val sb = StringBuilder()
        writeValue(sb, value, pretty, 0)
        if (pretty) sb.append('\n')
        return sb.toString()
    }

    private fun writeValue(sb: StringBuilder, value: Any?, pretty: Boolean, indent: Int) {
        when (value) {
            null -> sb.append("null")
            is String -> writeString(sb, value)
            is Boolean -> sb.append(if (value) "true" else "false")
            is Int, is Long, is Short, is Byte -> sb.append(value.toString())
            is Double -> {
                require(!value.isNaN() && !value.isInfinite()) { "Cannot encode $value in JSON" }
                if (value == Math.floor(value) && Math.abs(value) < 1e15) sb.append(value.toLong().toString()).append(".0")
                else sb.append(value.toString())
            }
            is Float -> writeValue(sb, value.toDouble(), pretty, indent)
            is Map<*, *> -> {
                if (value.isEmpty()) { sb.append("{}"); return }
                sb.append('{')
                var first = true
                for ((k, v) in value) {
                    if (!first) sb.append(',')
                    first = false
                    newline(sb, pretty, indent + 1)
                    writeString(sb, k.toString())
                    sb.append(if (pretty) ": " else ":")
                    writeValue(sb, v, pretty, indent + 1)
                }
                newline(sb, pretty, indent)
                sb.append('}')
            }
            is Iterable<*> -> {
                val list = value.toList()
                if (list.isEmpty()) { sb.append("[]"); return }
                sb.append('[')
                list.forEachIndexed { i, v ->
                    if (i > 0) sb.append(',')
                    newline(sb, pretty, indent + 1)
                    writeValue(sb, v, pretty, indent + 1)
                }
                newline(sb, pretty, indent)
                sb.append(']')
            }
            else -> writeString(sb, value.toString())
        }
    }

    private fun newline(sb: StringBuilder, pretty: Boolean, indent: Int) {
        if (!pretty) return
        sb.append('\n')
        repeat(indent) { sb.append("  ") }
    }

    private fun writeString(sb: StringBuilder, s: String) {
        sb.append('"')
        for (c in s) {
            when (c) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                '\b' -> sb.append("\\b")
                '\u000C' -> sb.append("\\f")
                else -> if (c < ' ' || c == ' ' || c == ' ') {
                    sb.append("\\u").append(String.format("%04x", c.code))
                } else sb.append(c)
            }
        }
        sb.append('"')
    }

    private class Parser(val s: String, val maxDepth: Int) {
        var pos = 0

        fun atEnd() = pos >= s.length

        fun skipWhitespace() {
            while (pos < s.length && (s[pos] == ' ' || s[pos] == '\n' || s[pos] == '\r' || s[pos] == '\t')) pos++
        }

        fun readValue(depth: Int): Any? {
            if (depth > maxDepth) throw JsonException("JSON nested too deeply")
            skipWhitespace()
            if (atEnd()) throw JsonException("Unexpected end of JSON")
            return when (val c = s[pos]) {
                '{' -> readObject(depth)
                '[' -> readArray(depth)
                '"' -> readString()
                't' -> literal("true", true)
                'f' -> literal("false", false)
                'n' -> literal("null", null)
                else -> if (c == '-' || c in '0'..'9') readNumber() else throw JsonException("Unexpected character '$c' at $pos")
            }
        }

        private fun literal(word: String, value: Any?): Any? {
            if (!s.startsWith(word, pos)) throw JsonException("Invalid literal at $pos")
            pos += word.length
            return value
        }

        private fun readObject(depth: Int): Map<String, Any?> {
            pos++ // {
            val map = LinkedHashMap<String, Any?>()
            skipWhitespace()
            if (pos < s.length && s[pos] == '}') { pos++; return map }
            while (true) {
                skipWhitespace()
                if (atEnd() || s[pos] != '"') throw JsonException("Expected object key at $pos")
                val key = readString()
                skipWhitespace()
                if (atEnd() || s[pos] != ':') throw JsonException("Expected ':' at $pos")
                pos++
                map[key] = readValue(depth + 1)
                skipWhitespace()
                if (atEnd()) throw JsonException("Unterminated object")
                when (s[pos]) {
                    ',' -> pos++
                    '}' -> { pos++; return map }
                    else -> throw JsonException("Expected ',' or '}' at $pos")
                }
            }
        }

        private fun readArray(depth: Int): List<Any?> {
            pos++ // [
            val list = ArrayList<Any?>()
            skipWhitespace()
            if (pos < s.length && s[pos] == ']') { pos++; return list }
            while (true) {
                list.add(readValue(depth + 1))
                skipWhitespace()
                if (atEnd()) throw JsonException("Unterminated array")
                when (s[pos]) {
                    ',' -> pos++
                    ']' -> { pos++; return list }
                    else -> throw JsonException("Expected ',' or ']' at $pos")
                }
            }
        }

        private fun readString(): String {
            pos++ // opening quote
            val sb = StringBuilder()
            while (true) {
                if (atEnd()) throw JsonException("Unterminated string")
                val c = s[pos++]
                when {
                    c == '"' -> return sb.toString()
                    c == '\\' -> {
                        if (atEnd()) throw JsonException("Bad escape")
                        when (val e = s[pos++]) {
                            '"' -> sb.append('"')
                            '\\' -> sb.append('\\')
                            '/' -> sb.append('/')
                            'b' -> sb.append('\b')
                            'f' -> sb.append('\u000C')
                            'n' -> sb.append('\n')
                            'r' -> sb.append('\r')
                            't' -> sb.append('\t')
                            'u' -> {
                                if (pos + 4 > s.length) throw JsonException("Bad unicode escape")
                                val hex = s.substring(pos, pos + 4)
                                val code = hex.toIntOrNull(16) ?: throw JsonException("Bad unicode escape")
                                sb.append(code.toChar())
                                pos += 4
                            }
                            else -> throw JsonException("Bad escape '\\$e'")
                        }
                    }
                    c < ' ' -> throw JsonException("Control character in string")
                    else -> sb.append(c)
                }
            }
        }

        private fun readNumber(): Any {
            val start = pos
            if (s[pos] == '-') pos++
            while (pos < s.length && s[pos] in '0'..'9') pos++
            var isDouble = false
            if (pos < s.length && s[pos] == '.') {
                isDouble = true; pos++
                while (pos < s.length && s[pos] in '0'..'9') pos++
            }
            if (pos < s.length && (s[pos] == 'e' || s[pos] == 'E')) {
                isDouble = true; pos++
                if (pos < s.length && (s[pos] == '+' || s[pos] == '-')) pos++
                while (pos < s.length && s[pos] in '0'..'9') pos++
            }
            val text = s.substring(start, pos)
            if (text == "-" || text.isEmpty()) throw JsonException("Bad number at $start")
            return if (isDouble) text.toDoubleOrNull() ?: throw JsonException("Bad number '$text'")
            else text.toLongOrNull() ?: text.toDoubleOrNull() ?: throw JsonException("Bad number '$text'")
        }
    }
}

/** Typed accessors for parsed JSON objects. */
@Suppress("UNCHECKED_CAST")
class JsonObj(val map: Map<String, Any?>) {
    fun has(key: String) = map.containsKey(key)
    fun str(key: String): String = map[key] as? String ?: throw Json.JsonException("Missing text field '$key'")
    fun strOrNull(key: String): String? = map[key] as? String
    fun long(key: String): Long = (map[key] as? Number)?.toLong() ?: throw Json.JsonException("Missing number field '$key'")
    fun longOrNull(key: String): Long? = (map[key] as? Number)?.toLong()
    fun int(key: String, default: Int = 0): Int = (map[key] as? Number)?.toInt() ?: default
    fun intOrNull(key: String): Int? = (map[key] as? Number)?.toInt()
    fun double(key: String, default: Double = 0.0): Double = (map[key] as? Number)?.toDouble() ?: default
    fun bool(key: String, default: Boolean = false): Boolean = map[key] as? Boolean ?: default
    fun objList(key: String): List<JsonObj> =
        (map[key] as? List<Any?>)?.mapNotNull { (it as? Map<String, Any?>)?.let(::JsonObj) } ?: emptyList()
    fun strMap(key: String): Map<String, String> =
        (map[key] as? Map<String, Any?>)?.mapNotNull { (k, v) -> (v as? String)?.let { k to it } }?.toMap() ?: emptyMap()
}
