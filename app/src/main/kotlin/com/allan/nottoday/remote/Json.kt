package com.allan.nottoday.remote

/**
 * JSON mínimo, em Kotlin puro (o org.json do Android não existe nos testes de JVM e não queremos dependências).
 * Objetos viram LinkedHashMap, listas viram ArrayList, inteiros viram Long, fracionários viram Double.
 */
object Json {
    class JsonException(message: String) : Exception(message)

    private const val MAX_DEPTH = 24

    fun parse(text: String): Any? {
        val p = Parser(text)
        val v = p.value(0)
        p.ws()
        if (p.pos != text.length) throw JsonException("lixo depois do JSON")
        return v
    }

    @Suppress("UNCHECKED_CAST")
    fun parseObject(text: String): Map<String, Any?> =
        parse(text) as? Map<String, Any?> ?: throw JsonException("esperado um objeto JSON")

    fun encode(v: Any?): String = StringBuilder().also { write(it, v) }.toString()

    private fun write(sb: StringBuilder, v: Any?) {
        when (v) {
            null -> sb.append("null")
            is Boolean -> sb.append(v.toString())
            is Int, is Long, is Short, is Byte -> sb.append(v.toString())
            is Number -> sb.append(if (v.toDouble().isFinite()) v.toString() else "null")
            is String -> str(sb, v)
            is Map<*, *> -> {
                sb.append('{')
                var first = true
                for ((k, x) in v) {
                    if (!first) sb.append(',')
                    first = false
                    str(sb, k.toString())
                    sb.append(':')
                    write(sb, x)
                }
                sb.append('}')
            }
            is Iterable<*> -> {
                sb.append('[')
                var first = true
                for (x in v) {
                    if (!first) sb.append(',')
                    first = false
                    write(sb, x)
                }
                sb.append(']')
            }
            is Array<*> -> write(sb, v.asList())
            else -> str(sb, v.toString())
        }
    }

    private fun str(sb: StringBuilder, s: String) {
        sb.append('"')
        for (c in s) {
            when {
                c == '"' -> sb.append("\\\"")
                c == '\\' -> sb.append("\\\\")
                c == '\n' -> sb.append("\\n")
                c == '\r' -> sb.append("\\r")
                c == '\t' -> sb.append("\\t")
                c < ' ' -> sb.append("\\u%04x".format(c.code))
                else -> sb.append(c)
            }
        }
        sb.append('"')
    }

    private class Parser(val s: String) {
        var pos = 0

        fun ws() {
            while (pos < s.length && (s[pos] == ' ' || s[pos] == '\n' || s[pos] == '\r' || s[pos] == '\t')) pos++
        }

        private fun fail(msg: String): Nothing = throw JsonException("$msg (posição $pos)")

        fun value(depth: Int): Any? {
            if (depth > MAX_DEPTH) fail("aninhamento demais")
            ws()
            if (pos >= s.length) fail("fim inesperado")
            return when (val c = s[pos]) {
                '{' -> obj(depth)
                '[' -> arr(depth)
                '"' -> string()
                't' -> lit("true", true)
                'f' -> lit("false", false)
                'n' -> lit("null", null)
                else -> if (c == '-' || c in '0'..'9') number() else fail("caractere inesperado '$c'")
            }
        }

        private fun lit(word: String, v: Any?): Any? {
            if (!s.startsWith(word, pos)) fail("literal inválido")
            pos += word.length
            return v
        }

        private fun obj(depth: Int): Map<String, Any?> {
            val m = LinkedHashMap<String, Any?>()
            pos++
            ws()
            if (pos < s.length && s[pos] == '}') { pos++; return m }
            while (true) {
                ws()
                if (pos >= s.length || s[pos] != '"') fail("esperada chave")
                val k = string()
                ws()
                if (pos >= s.length || s[pos] != ':') fail("esperado ':'")
                pos++
                m[k] = value(depth + 1)
                ws()
                if (pos >= s.length) fail("fim inesperado")
                when (s[pos++]) {
                    ',' -> continue
                    '}' -> return m
                    else -> fail("esperado ',' ou '}'")
                }
            }
        }

        private fun arr(depth: Int): List<Any?> {
            val l = ArrayList<Any?>()
            pos++
            ws()
            if (pos < s.length && s[pos] == ']') { pos++; return l }
            while (true) {
                l.add(value(depth + 1))
                ws()
                if (pos >= s.length) fail("fim inesperado")
                when (s[pos++]) {
                    ',' -> continue
                    ']' -> return l
                    else -> fail("esperado ',' ou ']'")
                }
            }
        }

        private fun string(): String {
            val sb = StringBuilder()
            pos++
            while (true) {
                if (pos >= s.length) fail("string sem fim")
                val c = s[pos++]
                when {
                    c == '"' -> return sb.toString()
                    c == '\\' -> {
                        if (pos >= s.length) fail("escape sem fim")
                        when (val e = s[pos++]) {
                            '"', '\\', '/' -> sb.append(e)
                            'b' -> sb.append('\b')
                            'f' -> sb.append('\u000c')
                            'n' -> sb.append('\n')
                            'r' -> sb.append('\r')
                            't' -> sb.append('\t')
                            'u' -> {
                                if (pos + 4 > s.length) fail("\\u incompleto")
                                sb.append(s.substring(pos, pos + 4).toIntOrNull(16)?.toChar() ?: fail("\\u inválido"))
                                pos += 4
                            }
                            else -> fail("escape inválido")
                        }
                    }
                    c < ' ' -> fail("caractere de controle em string")
                    else -> sb.append(c)
                }
            }
        }

        private fun number(): Any {
            val start = pos
            if (s[pos] == '-') pos++
            while (pos < s.length && s[pos] in '0'..'9') pos++
            var frac = false
            if (pos < s.length && s[pos] == '.') { frac = true; pos++; while (pos < s.length && s[pos] in '0'..'9') pos++ }
            if (pos < s.length && (s[pos] == 'e' || s[pos] == 'E')) {
                frac = true
                pos++
                if (pos < s.length && (s[pos] == '+' || s[pos] == '-')) pos++
                while (pos < s.length && s[pos] in '0'..'9') pos++
            }
            val t = s.substring(start, pos)
            return if (frac) t.toDoubleOrNull() ?: fail("número inválido") else t.toLongOrNull() ?: fail("número fora do intervalo")
        }
    }
}
