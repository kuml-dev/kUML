package dev.kuml.workspace.latex

import dev.kuml.workspace.latex.LatexTestSupport.main
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import java.io.File

/**
 * The security gate of the export: whatever a workspace says, `main.tex` may only contain control sequences that
 * the writer itself produces. The allowed set is not written by hand: it is the set of the same document built
 * from harmless text, plus the commands of [LatexEscaper].
 */
class LatexInjectionTest :
    FunSpec({
        val payloads =
            listOf(
                "\\write18{touch /tmp/kuml-pwn-test}",
                "\\input{/etc/passwd}",
                "\\include{x}",
                "\\openin1=/etc/passwd",
                "\\catcode`\\~=0 ~",
                "\\def\\x{y}\\x",
                "\\immediate\\write16{x}",
                "\\csname x\\endcsname",
                "\\usepackage{shellesc}",
                "\\begin{document}",
                "\\end{document}",
                "\\\\[1cm] \\\\[",
                "}\\end{document}\\iffalse{",
                "% comment \\fi",
                "\\verb|x|",
                "^^5cwrite18{x} ^^M",
                "x\u202E\u2028\u0000\u001B y \uD83D\uDE00 日本",
                "\$\$ \\href{run:calc}{x} \$\$",
            )

        fun fixture(p: String): Map<String, String> {
            val fm = "---\ntype: Article\ntitle: $p\n---\n"
            return mapOf(
                "index.md" to
                    "---\ntype: KumlWorkspace\ntitle: $p\nsubtitle: $p\nauthor: $p\n" +
                    "organization: $p\nversion: $p\nstatus: $p\ndate: $p\nlang: de\n---\n# $p\n\n" +
                    "- [a](a.md)\n- [b](b.md)\n",
                "a.md" to
                    fm + "# $p\n\nText $p mehr\n\n## H2 $p\n\n### H3 $p\n\n#### H4 $p\n\n##### H5 $p\n\n###### H6 $p\n\n" +
                    "| $p | B |\n|---|---|\n| $p | $p |\n\n" +
                    "- item $p\n    - inner $p\n\n1. num $p\n\n" +
                    "![$p](bilder/$p.png)\n\n" +
                    "[link $p](https://example.org/$p) [intern $p](b.md) [x]($p)\n\n" +
                    "`code $p` **bold $p** *em $p* ~~st $p~~\n\n" +
                    "```$p\ncode $p\n```\n\n" +
                    "> [!$p] $p\n> quote $p\n\n" +
                    "<div>$p</div>\n\n" +
                    "```kuml {name=\"$p\"}\nx\n```\n",
                "b.md" to fm + "# B $p\n\ntext\n",
            )
        }

        val commandRegex = Regex("\\\\[A-Za-z@]+")

        fun commandsOf(tex: String) = commandRegex.findAll(tex).map { it.value }.toSet()

        fun build(
            p: String,
            extra: Map<String, String> = emptyMap(),
        ): String {
            val ws = LatexTestSupport.workspace(files = fixture(p) + extra)
            val prepared = LatexTestSupport.prepare(ws = ws)
            return prepared.main()
        }

        val baseline = build("harmlos")
        // \mbox is the writer's own filler for empty list items and blank code lines; the harmless text never produces one.
        val allowed = commandsOf(baseline) + LatexEscaper.EMITTED_COMMANDS.map { "\\$it" } + "\\mbox"

        fun bodyOf(tex: String) = tex.substringAfter("\\begin{document}")

        fun assertSafe(tex: String) {
            (commandsOf(tex) - allowed).shouldBeEmpty()
            // the number of \usepackage, \input, \include and \documentclass lines is the baseline's
            Regex("\\\\usepackage").findAll(tex).count() shouldBe Regex("\\\\usepackage").findAll(baseline).count()
            Regex("\\\\input\\{").findAll(tex).count() shouldBe Regex("\\\\input\\{").findAll(baseline).count()
            Regex("\\\\documentclass").findAll(tex).count() shouldBe 1
            Regex("\\\\end\\{document}").findAll(tex).count() shouldBe 1
            Regex("\\\\begin\\{document}").findAll(tex).count() shouldBe 1
            for (bad in listOf("\\write18", "\\openin", "\\catcode", "\\csname", "\\immediate", "\\def\\", "\\include{")) {
                tex shouldNotContain
                    bad
            }
            // every % in the body is escaped, except in our own "% excluded" comment lines
            bodyOf(tex).lines().filterNot { it.startsWith("% excluded") }.forEach { line ->
                Regex("(?<!\\\\)%").containsMatchIn(line) shouldBe false
            }
            // braces balance when escaped ones and the control symbol \\ are removed
            val plain = tex.replace("\\\\", "").replace("\\{", "").replace("\\}", "")
            plain.count { it == '{' } shouldBe plain.count { it == '}' }
            // no raw line break inside a control word from user text: a lone backslash before a newline never occurs
            Regex("\\\\\\s*\\n\\s*[A-Za-z]").containsMatchIn(bodyOf(tex)) shouldBe false
        }

        test("the baseline itself passes the structural checks") {
            assertSafe(baseline)
        }

        payloads.forEachIndexed { i, p ->
            test("hostile payload #$i appears everywhere in the workspace and yields only known control sequences") {
                assertSafe(build(p))
            }
        }

        test("all payloads at once") {
            assertSafe(build(payloads.joinToString(" ")))
        }

        test("a document whose file name is hostile cannot inject through its title") {
            val name = "evil\\write18{touch x}\$y%z.md"
            val ok =
                runCatching {
                    File(LatexTestSupport.tempDir(), name).let {
                        it.writeText("x")
                        it.delete()
                    }
                }.isSuccess
            if (ok) {
                val tex = build("harmlos", extra = mapOf(name to "---\ntype: Article\n---\nText\n"))
                (commandsOf(tex) - allowed).shouldBeEmpty()
                tex shouldNotContain "\\write18"
                tex shouldNotContain "\\write18{touch"
            }
        }

        test("the generated labels never contain document text") {
            val tex = build(payloads.joinToString(" "))
            Regex("\\\\(label|hyperref)\\[?\\{?([^}\\]]*)").findAll(tex).forEach {
                (it.groupValues[2].isEmpty() || it.groupValues[2].matches(Regex("kuml-l\\d+"))) shouldBe true
            }
        }
    })
