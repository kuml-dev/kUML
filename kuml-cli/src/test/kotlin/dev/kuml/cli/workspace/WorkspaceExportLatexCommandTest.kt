package dev.kuml.cli.workspace

import com.github.ajalt.clikt.testing.test
import dev.kuml.cli.KumlCli
import dev.kuml.layout.LayoutEngineRegistry
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.io.File
import java.nio.file.Files
import kotlin.time.Duration.Companion.seconds

class WorkspaceExportLatexCommandTest :
    FunSpec({
        beforeEach { LayoutEngineRegistry.clear() }
        afterEach { LayoutEngineRegistry.clear() }

        val res = File("src/test/resources")

        fun folder(withBroken: Boolean): File {
            val root = Files.createTempDirectory("kuml-latex-cli").toFile()
            File(res, "latex/uml-class.kuml.kts").copyTo(File(root, "a.kuml.kts"))
            File(res, "latex-folder/erm.kuml.kts").copyTo(File(root, "erm.kuml.kts"))
            if (withBroken) File(root, "broken.kuml.kts").writeText("this is not kotlin (")
            return root
        }

        fun run(
            root: File,
            vararg args: String,
        ) = KumlCli().test(listOf("workspace", "export", root.absolutePath, "--format", "latex") + args.toList())

        test("a clean folder exits 0 with a summary and SKIP lines").config(timeout = 120.seconds) {
            val root = folder(withBroken = false)
            try {
                val out = File(root, "out")
                val r = run(root, "-o", out.absolutePath)
                r.statusCode shouldBe 0
                r.stdout shouldContain "Wrote 1 figure(s) plus kuml-preamble.tex and main.tex"
                r.stdout shouldContain "0 failed, 1 skipped, 0 excluded"
                r.stderr shouldContain "SKIP erm.kuml.kts:"
                File(out, "main.tex").isFile shouldBe true
            } finally {
                root.deleteRecursively()
            }
        }

        test("a broken script exits 3 but the good figures are written").config(timeout = 120.seconds) {
            val root = folder(withBroken = true)
            try {
                val out = File(root, "out")
                val r = run(root, "-o", out.absolutePath)
                r.statusCode shouldBe 3
                r.stderr shouldContain "ERROR broken.kuml.kts:"
                File(out, "a.tex").isFile shouldBe true
            } finally {
                root.deleteRecursively()
            }
        }

        test("--strict exits 3 and writes nothing").config(timeout = 120.seconds) {
            val root = folder(withBroken = false)
            try {
                val out = File(root, "out")
                val r = run(root, "-o", out.absolutePath, "--strict")
                r.statusCode shouldBe 3
                r.stderr shouldContain "nothing written"
                out.exists() shouldBe false
            } finally {
                root.deleteRecursively()
            }
        }

        test("PDF-only options are rejected with usage exit 2") {
            val root = folder(withBroken = false)
            try {
                val r = run(root, "--kind", "diagrams")
                r.statusCode shouldBe 2
                r.stderr shouldContain "--kind"
            } finally {
                root.deleteRecursively()
            }
        }

        test("existing output without --force exits 2 with a hint, with --force it succeeds").config(timeout = 120.seconds) {
            val root = folder(withBroken = false)
            try {
                val out = File(root, "out")
                run(root, "-o", out.absolutePath).statusCode shouldBe 0
                val again = run(root, "-o", out.absolutePath)
                again.statusCode shouldBe 2
                again.stderr shouldContain "--force"
                run(root, "-o", out.absolutePath, "--force").statusCode shouldBe 0
            } finally {
                root.deleteRecursively()
            }
        }

        test("the default output lands in .kuml-out/export/latex and is not scanned again").config(timeout = 120.seconds) {
            val root = folder(withBroken = false)
            try {
                run(root).statusCode shouldBe 0
                File(root, ".kuml-out/export/latex/main.tex").isFile shouldBe true
                run(root, "--force").statusCode shouldBe 0
            } finally {
                root.deleteRecursively()
            }
        }
    })
