package dev.kuml.desktop.ui

import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import java.io.File

/** Measured: 62 button calls in the desktop module (2026-11-03), rounded down to the next five. */
private const val MIN_BUTTONS = 60

/**
 * Every dialog `Button(`/`OutlinedButton(`/`TextButton(` in the desktop module must either use
 * [IconLabel] or carry a `// no-icon: <ButtonIconException>` comment directly above it.
 * Part b2a covered the workspace, MCP and plugin dialogs; part b2b finishes b2 with the AI and
 * simulation dialogs and scans the whole desktop module.
 */

class DialogButtonCoverageTest :
    FunSpec({
        val sourceRoot: File =
            listOf(
                File(System.getProperty("user.dir"), "src/jvmMain/kotlin/dev/kuml/desktop"),
                File(System.getProperty("user.dir"), "kuml-desktop/src/jvmMain/kotlin/dev/kuml/desktop"),
            ).firstOrNull { it.isDirectory } ?: error("desktop sources not found relative to user.dir=${System.getProperty("user.dir")}")

        fun sources(): List<File> = sourceRoot.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()

        /** Replaces comments and string/char literal contents by spaces, keeping offsets and newlines. */
        fun mask(src: String): String {
            val out = StringBuilder(src)
            var i = 0

            fun blank(
                from: Int,
                to: Int,
            ) {
                for (k in from until to) if (out[k] != '\n') out[k] = ' '
            }
            while (i < src.length) {
                when {
                    src.startsWith("//", i) -> {
                        val end = src.indexOf('\n', i).let { if (it < 0) src.length else it }
                        blank(i, end)
                        i = end
                    }
                    src.startsWith("/*", i) -> {
                        val end = src.indexOf("*/", i + 2).let { if (it < 0) src.length else it + 2 }
                        blank(i, end)
                        i = end
                    }
                    src.startsWith("\"\"\"", i) -> {
                        val end = src.indexOf("\"\"\"", i + 3).let { if (it < 0) src.length else it + 3 }
                        blank(i, end)
                        i = end
                    }
                    src[i] == '"' -> {
                        var j = i + 1
                        while (j < src.length && src[j] != '"') j += if (src[j] == '\\') 2 else 1
                        blank(i + 1, minOf(j, src.length))
                        i = j + 1
                    }
                    src[i] == '\'' -> {
                        var j = i + 1
                        while (j < src.length && src[j] != '\'') j += if (src[j] == '\\') 2 else 1
                        blank(i + 1, minOf(j, src.length))
                        i = j + 1
                    }
                    else -> i++
                }
            }
            return out.toString()
        }

        fun matching(
            s: String,
            open: Int,
            o: Char,
            c: Char,
        ): Int {
            var depth = 0
            var i = open
            while (i < s.length) {
                if (s[i] == o) depth++
                if (s[i] == c) {
                    depth--
                    if (depth == 0) return i
                }
                i++
            }
            return s.length - 1
        }

        val callRegex = Regex("""(?<![A-Za-z0-9_])(Button|OutlinedButton|TextButton)\(""")

        fun violations(file: File): List<String> {
            val original = file.readText()
            val masked = mask(original)
            val result = mutableListOf<String>()
            callRegex.findAll(masked).forEach { m ->
                val open = m.range.last
                val close = matching(masked, open, '(', ')')
                var end = close
                // Trailing lambda: skip whitespace, then a '{' block belongs to this call.
                var k = close + 1
                while (k < masked.length && masked[k].isWhitespace()) k++
                if (k < masked.length && masked[k] == '{') end = matching(masked, k, '{', '}')
                val call = original.substring(m.range.first, end + 1)
                val line = original.substring(0, m.range.first).count { it == '\n' } + 1
                if ("IconLabel(" !in call) {
                    val prev =
                        original
                            .lines()
                            .take(line - 1)
                            .lastOrNull { it.isNotBlank() }
                            ?.trim()
                            .orEmpty()
                    val tag = Regex("""^// no-icon: ([A-Z_]+)$""").matchEntire(prev)?.groupValues?.get(1)
                    val valid = tag != null && ButtonIconException.entries.any { it.name == tag }
                    if (!valid) result += "${file.name}:$line ${m.value} has neither IconLabel nor a valid // no-icon: comment"
                }
            }
            return result
        }

        test("every dialog button in the desktop module has an icon or a documented exception") {
            val all = sources().flatMap { violations(it) }
            withClue(all.joinToString("\n")) { all.shouldBeEmpty() }
        }

        test("the scan actually sees the buttons (guards against an always-green test)") {
            val total =
                sources().sumOf { callRegex.findAll(mask(it.readText())).count() }
            (total >= MIN_BUTTONS) shouldBe true
        }

        test("every no-icon comment names a valid ButtonIconException") {
            val tagRegex = Regex("""//\s*no-icon:\s*([A-Za-z_]+)""")
            val valid = ButtonIconException.entries.map { it.name }.toSet()
            val bad =
                sources().flatMap { file ->
                    file.readLines().mapIndexedNotNull { i, line ->
                        val tag = tagRegex.find(line)?.groupValues?.get(1)
                        if (tag != null && tag !in valid) "${file.name}:${i + 1} unknown no-icon tag '$tag'" else null
                    }
                }
            withClue(bad.joinToString("\n")) { bad.shouldBeEmpty() }
        }
    })
