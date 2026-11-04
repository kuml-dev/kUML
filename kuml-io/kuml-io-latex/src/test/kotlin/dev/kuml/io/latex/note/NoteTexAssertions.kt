package dev.kuml.io.latex.note

import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain

/** Hostile note bodies shared by the BPMN and Service Blueprint note tests. */
internal val HOSTILE_BODIES: List<String> =
    listOf(
        "\\input{/etc/passwd}",
        "\\write18{touch /tmp/kuml-note-escape}",
        "\\immediate\\write18{x} % } { # ^^5c ~ \$ & _",
        "line1\nline2\r\n\tlast\u0000end\u2028sep\u2029par",
        "**\\input{x}** and [x](javascript:\\input{y}) `\\write18`",
    )

/** Checks that note text cannot run as TeX: no live macro from user text, balanced braces, no live `%`, no link macro. */
internal fun assertTexSafe(tex: String) {
    tex shouldNotContain "\\input"
    tex shouldNotContain "\\write18"
    tex shouldNotContain "\\immediate"
    tex shouldNotContain "^^"
    tex shouldNotContain "\\href"
    tex shouldNotContain "\\url"
    for (line in tex.lines()) {
        if (line.contains('%')) {
            val withoutEscaped = line.replace("\\%", "")
            if (withoutEscaped.contains('%')) {
                // only a whole-line comment may contain a bare percent sign
                (line.trimStart().startsWith("%")) shouldBe true
            }
        }
        if (line.contains("kuml-note-text")) {
            val flat = line.replace("\\{", "").replace("\\}", "")
            flat.count { it == '{' } shouldBe flat.count { it == '}' }
        }
    }
}
