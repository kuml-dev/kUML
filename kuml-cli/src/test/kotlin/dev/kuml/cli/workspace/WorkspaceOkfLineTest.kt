package dev.kuml.cli.workspace

import com.github.ajalt.clikt.testing.test
import dev.kuml.cli.KumlCli
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.string.shouldContain
import java.io.File
import java.nio.file.Files
import java.util.Locale

/** PAL 2B (P-WS-01): OKF-E-007 names the document line of the broken statement, independent of the JVM locale. */
class WorkspaceOkfLineTest :
    FunSpec({
        // Document lines: 1 '---', 2 'type', 3 '---', 4 '# T', 5 '```kuml' (fence), 6 classDiagram, 7 classOf(OK), 8 typo
        val doc =
            "---\ntype: Concept\n---\n# T\n```kuml\nclassDiagram(name = \"A\") {\n    classOf(name = \"X\")\n    classOfTypo(name = \"Y\")\n}\n```\n"

        for (locale in listOf(Locale.US, Locale.GERMANY)) {
            test("OKF-E-007 reports document line 8 and block line 3 (locale $locale)") {
                val saved = Locale.getDefault()
                Locale.setDefault(locale)
                val root = Files.createTempDirectory("kuml-okf-line").toFile()
                try {
                    File(root, "bad.md").writeText(doc)
                    val result = KumlCli().test(listOf("workspace", "validate", root.absolutePath, "--evaluate"))
                    result.output shouldContain "OKF-E-007"
                    result.output shouldContain "line 8 (block line 3)"
                } finally {
                    Locale.setDefault(saved)
                    root.deleteRecursively()
                }
            }
        }

        // Document lines: 1 '---', 2 'type', 3 '---', 4 '# T', 5 '```kuml' (fence), 6 import, 7 model, 8 partDef, 9 comment (block line 4)
        val noteDoc =
            "---\ntype: Concept\n---\n# T\n```kuml\nimport dev.kuml.sysml2.dsl.sysml2Model\n" +
                "sysml2Model(\"M\") {\n    val a = partDef(\"A\")\n    comment(text = \"x\", \"Nope\", id = \"n1\")\n    bdd(\"B\") { include(a) }\n}\n```\n"

        test("KUML-NOTE-W001 sits on the line of the comment call, not on the fence") {
            val root = Files.createTempDirectory("kuml-note-line").toFile()
            try {
                File(root, "note.md").writeText(noteDoc)
                val result = KumlCli().test(listOf("workspace", "validate", root.absolutePath, "--evaluate"))
                result.output shouldContain "KUML-NOTE-W001"
                result.output shouldContain "(block line 4)"
            } finally {
                root.deleteRecursively()
            }
        }
    })
