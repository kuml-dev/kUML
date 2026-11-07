package dev.kuml.cli.export

import dev.kuml.io.latex.LatexFamily
import dev.kuml.layout.LayoutEngineRegistry
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import java.io.File
import java.nio.file.Files
import kotlin.time.Duration.Companion.seconds

class LatexFolderExportTest :
    FunSpec({
        beforeEach { LayoutEngineRegistry.clear() }
        afterEach { LayoutEngineRegistry.clear() }

        val res = File("src/test/resources")

        fun tmp(): File = Files.createTempDirectory("kuml-folder-export").toFile()

        fun put(
            root: File,
            rel: String,
            from: String,
        ) {
            val f = File(root, rel)
            f.parentFile.mkdirs()
            File(res, from).copyTo(f, overwrite = true)
        }

        fun putText(
            root: File,
            rel: String,
            text: String,
        ) {
            val f = File(root, rel)
            f.parentFile.mkdirs()
            f.writeText(text)
        }

        fun tree(dir: File): Map<String, Long> =
            dir
                .walkTopDown()
                .filter { it.isFile }
                .associate { it.relativeTo(dir).invariantSeparatorsPath to it.length() }

        fun mixed(): File {
            val root = tmp()
            put(root, "uml-class.kuml.kts", "latex/uml-class.kuml.kts")
            put(root, "c4-context.kuml.kts", "latex/c4-context.kuml.kts")
            put(root, "sub/bpmn-process.kuml.kts", "latex/bpmn-process.kuml.kts")
            put(root, "erm.kuml.kts", "latex-folder/erm.kuml.kts")
            put(root, "two.kuml.kts", "latex-folder/two-diagrams.kuml.kts")
            putText(root, "broken.kuml.kts", "this is not kotlin (")
            return root
        }

        test("a mixed folder exports figures, a preamble and a main document").config(timeout = 120.seconds) {
            val root = mixed()
            val out = File(root, "out")
            try {
                val prepared = LatexFolderExport.prepare(root = root, excludeDirs = setOf(out), themeName = null)
                prepared.failures.map { it.source } shouldBe listOf("broken.kuml.kts")
                prepared.skipped.map { it.source } shouldBe listOf("erm.kuml.kts")
                prepared.notes.any { it.startsWith("two.kuml.kts") && "exported the first" in it } shouldBe true
                prepared.preamble shouldContain "shapes.geometric"
                prepared.preamble shouldNotContain "plotmarks"
                val result = LatexFolderExport.write(prepared = prepared, outDir = out, overwrite = false)
                val names = result.written.map { it.relativeTo(out).invariantSeparatorsPath }.toSet()
                names shouldBe
                    setOf(
                        "uml-class.tex",
                        "c4-context.tex",
                        "sub/bpmn-process.tex",
                        "two.tex",
                        "kuml-preamble.tex",
                        "main.tex",
                    )
                val main = File(out, "main.tex").readText()
                val order = listOf("c4-context", "sub/bpmn-process", "two", "uml-class")
                order.forEach { Regex("""\\kumlfigure\{${Regex.escape(it)}\}""").findAll(main).count() shouldBe 1 }
                val idx = order.map { main.indexOf("\\kumlfigure{$it}") }
                idx shouldBe idx.sorted()
                prepared.figures.first { it.target == "uml-class.tex" }.family shouldBe LatexFamily.UML
                tree(out).keys.none { it.endsWith(".tmp") } shouldBe true
            } finally {
                root.deleteRecursively()
            }
        }

        test("colliding names get numbered, case-insensitively, and main and the preamble are reserved").config(timeout = 120.seconds) {
            val root = tmp()
            try {
                put(root, "a b.kuml.kts", "latex/uml-class.kuml.kts")
                put(root, "a-b.kuml.kts", "latex/uml-class.kuml.kts")
                put(root, "A+B.kuml.kts", "latex/uml-class.kuml.kts")
                put(root, "z.kuml.kts", "latex/uml-class.kuml.kts")
                put(root, "main.kuml.kts", "latex/uml-class.kuml.kts")
                val p = LatexFolderExport.prepare(root = root, excludeDirs = emptySet(), themeName = null)
                val targets = p.figures.map { it.source to it.target }.toMap()
                targets["A+B.kuml.kts"] shouldBe "A-B.tex"
                targets["a b.kuml.kts"] shouldBe "a-b-2.tex"
                targets["a-b.kuml.kts"] shouldBe "a-b-3.tex"
                targets["main.kuml.kts"] shouldBe "main-2.tex"
                p.figures
                    .map { it.target.lowercase() }
                    .toSet()
                    .size shouldBe 5
                p.notes.any { "name collision" in it } shouldBe true
            } finally {
                root.deleteRecursively()
            }
        }

        test("names SAFE_INPUT would reject are sanitized, and file and directory targets never collide").config(timeout = 120.seconds) {
            val root = tmp()
            val out = File(tmp(), "out")
            try {
                put(root, "_draft.kuml.kts", "latex/uml-class.kuml.kts")
                put(root, "v1..2.kuml.kts", "latex/uml-class.kuml.kts")
                put(root, "sub/_x.kuml.kts", "latex/uml-class.kuml.kts")
                put(root, "foo.kuml.kts", "latex/uml-class.kuml.kts")
                put(root, "foo.tex/bar.kuml.kts", "latex/uml-class.kuml.kts")
                val p = LatexFolderExport.prepare(root = root, excludeDirs = emptySet(), themeName = null)
                val targets = p.figures.map { it.source to it.target }.toMap()
                targets["_draft.kuml.kts"] shouldBe "draft.tex"
                targets["v1..2.kuml.kts"] shouldBe "v1.2.tex"
                targets["sub/_x.kuml.kts"] shouldBe "sub/x.tex"
                targets["foo.kuml.kts"] shouldBe "foo.tex"
                targets["foo.tex/bar.kuml.kts"] shouldBe "foo.tex-2/bar.tex"
                LatexFolderExport.write(prepared = p, outDir = out, overwrite = false)
                File(out, "foo.tex").isFile shouldBe true
                File(out, "foo.tex-2/bar.tex").isFile shouldBe true
            } finally {
                root.deleteRecursively()
                out.parentFile.deleteRecursively()
            }
        }

        test("a collaboration with a dangling message flow is written but kept out of main.tex").config(timeout = 120.seconds) {
            val root = tmp()
            val out = File(tmp(), "out")
            try {
                put(root, "collab.kuml.kts", "latex/bpmn-collaboration.kuml.kts")
                put(root, "ok.kuml.kts", "latex/uml-class.kuml.kts")
                val p = LatexFolderExport.prepare(root = root, excludeDirs = emptySet(), themeName = null)
                val collab = p.figures.first { it.source == "collab.kuml.kts" }
                (collab.excludedReason ?: "") shouldContain "undefined TikZ node"
                p.figures.first { it.source == "ok.kuml.kts" }.excludedReason shouldBe null
                LatexFolderExport.write(prepared = p, outDir = out, overwrite = false)
                File(out, "collab.tex").isFile shouldBe true
                val main = File(out, "main.tex").readText()
                main shouldContain "% excluded: collab.tex"
                main shouldNotContain "\\kumlfigure{collab}"
            } finally {
                root.deleteRecursively()
                out.parentFile.deleteRecursively()
            }
        }

        test("existing output needs overwrite and a refusal leaves everything untouched").config(timeout = 120.seconds) {
            val root = tmp()
            val out = File(root, "out")
            try {
                put(root, "a.kuml.kts", "latex/uml-class.kuml.kts")
                val p = LatexFolderExport.prepare(root = root, excludeDirs = setOf(out), themeName = null)
                out.mkdirs()
                File(out, "main.tex").writeText("PRECIOUS")
                val before = tree(out)
                val e = shouldThrow<LatexFolderExportException> { LatexFolderExport.write(prepared = p, outDir = out, overwrite = false) }
                e.code shouldBe LatexFolderExportException.Code.OUTPUT_EXISTS
                tree(out) shouldBe before
                File(out, "main.tex").readText() shouldBe "PRECIOUS"
                LatexFolderExport.write(prepared = p, outDir = out, overwrite = true)
                File(out, "main.tex").readText() shouldContain "\\documentclass"
            } finally {
                root.deleteRecursively()
            }
        }

        test("an output directory that is a symbolic link is refused").config(timeout = 120.seconds) {
            val root = tmp()
            val real = tmp()
            try {
                put(root, "a.kuml.kts", "latex/uml-class.kuml.kts")
                val link = File(root, "outlink")
                runCatching { Files.createSymbolicLink(link.toPath(), real.toPath()) }.getOrNull() ?: return@config
                val p = LatexFolderExport.prepare(root = root, excludeDirs = setOf(link), themeName = null)
                shouldThrow<LatexFolderExportException> { LatexFolderExport.write(prepared = p, outDir = link, overwrite = true) }
                    .code shouldBe LatexFolderExportException.Code.OUTPUT_INVALID
                real.list()!!.size shouldBe 0
            } finally {
                root.deleteRecursively()
                real.deleteRecursively()
            }
        }

        test("a subdirectory of the output that is a symlink to the outside is refused").config(timeout = 120.seconds) {
            val root = tmp()
            val outside = tmp()
            try {
                put(root, "sub/a.kuml.kts", "latex/uml-class.kuml.kts")
                val out = File(root, "out").also { it.mkdirs() }
                runCatching { Files.createSymbolicLink(File(out, "sub").toPath(), outside.toPath()) }.getOrNull() ?: return@config
                val p = LatexFolderExport.prepare(root = root, excludeDirs = setOf(out), themeName = null)
                shouldThrow<LatexFolderExportException> { LatexFolderExport.write(prepared = p, outDir = out, overwrite = true) }
                    .code shouldBe LatexFolderExportException.Code.PATH_OUTSIDE_OUTPUT
                outside.list()!!.size shouldBe 0
            } finally {
                root.deleteRecursively()
                outside.deleteRecursively()
            }
        }

        test("hostile file names are sanitised").config(timeout = 120.seconds) {
            val root = tmp()
            try {
                put(root, "evil}\\input{x}.kuml.kts", "latex/uml-class.kuml.kts")
                val p = LatexFolderExport.prepare(root = root, excludeDirs = emptySet(), themeName = null)
                p.figures.size shouldBe 1
                p.main shouldNotContain "\\input{x}"
                p.figures.single().target shouldBe "evil-input-x-.tex"
                val nl = File(root, "line\nbreak.kuml.kts")
                if (runCatching { put(root, nl.name, "latex/uml-class.kuml.kts") }.isSuccess && nl.exists()) {
                    val p2 = LatexFolderExport.prepare(root = root, excludeDirs = emptySet(), themeName = null)
                    p2.main
                        .lines()
                        .filter { "break" in it && !it.trimStart().startsWith("%") }
                        .none { "\\input" in it } shouldBe true
                }
            } finally {
                root.deleteRecursively()
            }
        }

        test("a symbolic link script is skipped").config(timeout = 120.seconds) {
            val root = tmp()
            try {
                put(root, "real.kuml.kts", "latex/uml-class.kuml.kts")
                runCatching { Files.createSymbolicLink(File(root, "link.kuml.kts").toPath(), File(root, "real.kuml.kts").toPath()) }
                    .getOrNull() ?: return@config
                val p = LatexFolderExport.prepare(root = root, excludeDirs = emptySet(), themeName = null)
                p.skipped shouldContain LatexFolderIssue(source = "link.kuml.kts", reason = "symbolic link")
                p.figures.map { it.source } shouldBe listOf("real.kuml.kts")
            } finally {
                root.deleteRecursively()
            }
        }

        test("an empty folder reports NO_SCRIPTS") {
            val root = tmp()
            try {
                shouldThrow<LatexFolderExportException> {
                    LatexFolderExport.prepare(
                        root = root,
                        excludeDirs = emptySet(),
                        themeName = null,
                    )
                }.code shouldBe LatexFolderExportException.Code.NO_SCRIPTS
            } finally {
                root.deleteRecursively()
            }
        }
    })
