package dev.kuml.cli.workspace

import dev.kuml.cli.workspace.WorkspaceBlockEvaluator.Declared
import dev.kuml.markdown.KumlCodeBlock
import dev.kuml.workspace.Frontmatter
import dev.kuml.workspace.OkfDocument
import dev.kuml.workspace.OkfSeverity
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import java.io.File

/** Unit tests of the cross-document reqId aggregation (OKF-W-009). */
class ReqIdConflictFindingsTest :
    FunSpec({
        fun doc(path: String) =
            OkfDocument(
                file = File(path),
                relativePath = path,
                frontmatter = Frontmatter(fields = emptyMap(), tags = emptyList(), present = true, bodyStartLine = 1),
                type = null,
                rawType = null,
                kumlBlocks = emptyList(),
                links = emptyList(),
            )

        fun declared(
            path: String,
            reqId: String,
            text: String,
        ) = Declared(
            doc = doc(path),
            block = KumlCodeBlock(source = "", startLine = 5, endLine = 9),
            reqId = reqId,
            text = text,
        )

        test("different text in two documents yields one warning on the later document without the text") {
            val f =
                WorkspaceBlockEvaluator.reqIdConflictFindings(
                    listOf(declared("b.md", "R-1", "secret stop"), declared("a.md", "R-1", "secret start")),
                )
            f.size shouldBe 1
            f[0].code shouldBe "OKF-W-009"
            f[0].severity shouldBe OkfSeverity.WARNING
            f[0].file shouldBe "b.md"
            f[0].line shouldBe 5
            f[0].message shouldContain "R-1"
            f[0].message shouldNotContain "secret"
        }

        test("identical (whitespace-normalised) text, empty text, empty reqId and a single document stay silent") {
            WorkspaceBlockEvaluator
                .reqIdConflictFindings(
                    listOf(
                        declared("a.md", "R-1", "shall  start"),
                        declared("b.md", "R-1", " shall start "),
                        declared("c.md", "R-2", ""),
                        declared("d.md", "R-2", "x"),
                        declared("e.md", "", "x"),
                        declared("f.md", "", "y"),
                        declared("a.md", "R-3", "one"),
                        declared("a.md", "R-3", "two"),
                    ),
                ).size shouldBe 0
        }

        test("text comparison is case-sensitive") {
            WorkspaceBlockEvaluator
                .reqIdConflictFindings(listOf(declared("a.md", "R-1", "Start"), declared("b.md", "R-1", "start")))
                .size shouldBe 1
        }

        test("findings are capped and followed by a summary") {
            val list = mutableListOf(declared("a000.md", "R-1", "base"))
            for (i in 1..150) list += declared("b%03d.md".format(i), "R-1", "other $i")
            val f = WorkspaceBlockEvaluator.reqIdConflictFindings(list)
            f.size shouldBe 101
            f.last().message shouldContain "50 further"
        }
    })
