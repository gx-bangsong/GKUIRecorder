/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.recorder.asr.model

/**
 * Tiny JSON reader/writer for the small files this package keeps (download resume metadata and
 * the installed model manifest). Supports objects, arrays, strings, numbers, booleans and null.
 * Written here instead of org.json so the same code runs in plain JVM unit tests.
 */
object MiniJson {
    fun write(value: Any?): String = StringBuilder().also { append(it, value) }.toString()

    fun parse(text: String): Any? {
        val parser = Parser(text)
        val value = parser.readValue()
        parser.skipWhitespace()
        require(parser.atEnd()) { "Trailing characters in JSON" }
        return value
    }

    @Suppress("UNCHECKED_CAST")
    fun parseObject(text: String): Map<String, Any?> = parse(text) as? Map<String, Any?>
        ?: throw IllegalArgumentException("JSON root is not an object")

    private fun append(out: StringBuilder, value: Any?) {
        when (value) {
            null -> out.append("null")
            is String -> appendString(out, value)
            is Boolean -> out.append(value.toString())
            is Int, is Long -> out.append(value.toString())
            is Float, is Double -> {
                val number = (value as Number).toDouble()
                require(number.isFinite()) { "JSON cannot represent non-finite numbers" }
                out.append(value.toString())
            }
            is Map<*, *> -> {
                out.append('{')
                value.entries.forEachIndexed { index, (key, item) ->
                    if (index > 0) {
                        out.append(',')
                    }
                    appendString(out, key as String)
                    out.append(':')
                    append(out, item)
                }
                out.append('}')
            }
            is List<*> -> {
                out.append('[')
                value.forEachIndexed { index, item ->
                    if (index > 0) {
                        out.append(',')
                    }
                    append(out, item)
                }
                out.append(']')
            }
            else -> throw IllegalArgumentException("Unsupported JSON value: ${value::class.simpleName}")
        }
    }

    private fun appendString(out: StringBuilder, text: String) {
        out.append('"')
        for (c in text) {
            when (c) {
                '"' -> out.append("\\\"")
                '\\' -> out.append("\\\\")
                '\n' -> out.append("\\n")
                '\r' -> out.append("\\r")
                '\t' -> out.append("\\t")
                else -> if (c < ' ') {
                    out.append("\\u%04x".format(c.code))
                } else {
                    out.append(c)
                }
            }
        }
        out.append('"')
    }

    private class Parser(private val text: String) {
        private var pos = 0

        fun atEnd() = pos >= text.length

        fun skipWhitespace() {
            while (pos < text.length && text[pos].isWhitespace()) {
                pos++
            }
        }

        fun readValue(): Any? {
            skipWhitespace()
            require(pos < text.length) { "Unexpected end of JSON" }
            return when (text[pos]) {
                '{' -> readObject()
                '[' -> readArray()
                '"' -> readString()
                't' -> readLiteral("true", true)
                'f' -> readLiteral("false", false)
                'n' -> readLiteral("null", null)
                else -> readNumber()
            }
        }

        private fun readObject(): Map<String, Any?> {
            expect('{')
            val result = LinkedHashMap<String, Any?>()
            skipWhitespace()
            if (peek() == '}') {
                pos++
                return result
            }
            while (true) {
                skipWhitespace()
                val key = readString()
                skipWhitespace()
                expect(':')
                result[key] = readValue()
                skipWhitespace()
                when (val c = next()) {
                    ',' -> continue
                    '}' -> return result
                    else -> throw IllegalArgumentException("Bad object separator '$c'")
                }
            }
        }

        private fun readArray(): List<Any?> {
            expect('[')
            val result = ArrayList<Any?>()
            skipWhitespace()
            if (peek() == ']') {
                pos++
                return result
            }
            while (true) {
                result.add(readValue())
                skipWhitespace()
                when (val c = next()) {
                    ',' -> continue
                    ']' -> return result
                    else -> throw IllegalArgumentException("Bad array separator '$c'")
                }
            }
        }

        private fun readString(): String {
            expect('"')
            val out = StringBuilder()
            while (true) {
                val c = next()
                when (c) {
                    '"' -> return out.toString()
                    '\\' -> when (val e = next()) {
                        '"', '\\', '/' -> out.append(e)
                        'n' -> out.append('\n')
                        'r' -> out.append('\r')
                        't' -> out.append('\t')
                        'b' -> out.append('\b')
                        'f' -> out.append('\u000c')
                        'u' -> {
                            require(pos + 4 <= text.length) { "Bad unicode escape" }
                            out.append(text.substring(pos, pos + 4).toInt(16).toChar())
                            pos += 4
                        }
                        else -> throw IllegalArgumentException("Bad escape \\$e")
                    }
                    else -> out.append(c)
                }
            }
        }

        private fun readNumber(): Any {
            val start = pos
            while (pos < text.length && (text[pos].isDigit() || text[pos] in "+-.eE")) {
                pos++
            }
            val raw = text.substring(start, pos)
            require(raw.isNotEmpty()) { "Bad JSON value at $pos" }
            return raw.toLongOrNull() ?: raw.toDouble()
        }

        private fun readLiteral(word: String, value: Any?): Any? {
            require(text.startsWith(word, pos)) { "Bad literal at $pos" }
            pos += word.length
            return value
        }

        private fun peek(): Char? = if (pos < text.length) text[pos] else null

        private fun next(): Char {
            require(pos < text.length) { "Unexpected end of JSON" }
            return text[pos++]
        }

        private fun expect(c: Char) {
            val actual = next()
            require(actual == c) { "Expected '$c' but found '$actual'" }
        }
    }
}
