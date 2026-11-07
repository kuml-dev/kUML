package dev.kuml.desktop.workspace

import dev.kuml.cli.workspace.PreparedLatexRun
import dev.kuml.desktop.render.DesktopEngineInit
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import java.io.File
import java.nio.file.Files
import java.util.concurrent.Semaphore
import kotlin.time.Duration.Companion.seconds

/** Real runs through the function the dialog calls: real scripts, real files, no mock of the export. */
class WorkspaceLatexExportRunTest :
    FunSpec({
        System.setProperty("java.awt.headless", "true")
        DesktopEngineInit.ensure()

        val cliResources: File =
            listOf(
                File(System.getProperty("user.dir"), "../kuml-cli/src/test/resources"),
                File(System.getProperty("user.dir"), "kuml-cli/src/test/resources"),
            ).firstOrNull { File(it, "latex-knowledge").isDirectory } ?: error("kuml-cli test resources not found")

        fun knowledge(): File {
            val root = Files.createTempDirectory("kuml-desk-latex-k").toFile()
            File(cliResources, "latex-knowledge").copyRecursively(root)
            return root
        }

        fun engineering(broken: Boolean = false): File {
            val root = Files.createTempDirectory("kuml-desk-latex-e").toFile()
            File(cliResources, "latex/uml-class.kuml.kts").copyTo(File(root, "a.kuml.kts"))
            if (broken) File(root, "broken.kuml.kts").writeText("this is not kotlin (")
            return root
        }

        fun form(
            root: File,
            strict: Boolean = false,
            out: String = "out",
        ) = WorkspaceLatexFormState(targetPath = File(root, out).path, strict = strict, themeName = "kuml")

        fun run(
            root: File,
            form: WorkspaceLatexFormState,
            gate: Semaphore = Semaphore(1),
            isCancelled: () -> Boolean = { false },
            onWriting: () -> Boolean = { true },
        ) = runWorkspaceLatexExport(root = root, form = form, evalGate = gate, isCancelled = isCancelled, onWriting = onWriting)

        test(
            "Knowledge workspace: writes main.tex and reports the CLI's lines; a second run asks, overwrite works",
        ).config(timeout = 400.seconds) {
            val root = knowledge()
            try {
                val first = run(root, form(root))
                val done = first.state.shouldBeInstanceOf<LatexUiState.Done>()
                File(root, "out/main.tex").isFile shouldBe true
                done.summary[0] shouldContain "Wrote main.tex, kuml-preamble.tex, 4 figure(s), 1 image(s) for 5 document(s)"
                done.summary[1] shouldBe "0 failed, 0 skipped, 0 excluded from main.tex, 1 warnings"
                done.lines.any { it.text.startsWith("WARN ueberblick.md") } shouldBe true
                latexReportText(done) shouldContain "NOTE ueberblick.md"
                latexStatusView(done).tone() shouldBe StatusTone.WARNING

                val second = run(root, form(root))
                val ask = second.state.shouldBeInstanceOf<LatexUiState.ConfirmOverwrite>()
                (ask.existing.isNotEmpty()) shouldBe true
                val pending = second.pending ?: error("the prepared result must be kept for the overwrite question")
                pending.prepared.shouldBeInstanceOf<PreparedLatexRun.Knowledge>()

                overwriteWorkspaceLatexExport(pending).state.shouldBeInstanceOf<LatexUiState.Done>()
            } finally {
                root.deleteRecursively()
            }
        }

        test("strict stops before anything is written").config(timeout = 400.seconds) {
            val root = knowledge()
            try {
                var wrote = false
                val outcome =
                    run(root, form(root, strict = true), onWriting = {
                        wrote = true
                        true
                    })
                val blocked = outcome.state.shouldBeInstanceOf<LatexUiState.StrictBlocked>()
                blocked.lines.isNotEmpty() shouldBe true
                wrote shouldBe false
                File(root, "out").exists() shouldBe false
            } finally {
                root.deleteRecursively()
            }
        }

        test("Engineering folder: a clean run is green, files are written, the second run asks").config(timeout = 400.seconds) {
            val root = engineering()
            try {
                val done = run(root, form(root)).state.shouldBeInstanceOf<LatexUiState.Done>()
                done.summary[0] shouldContain "Wrote 1 figure(s) plus kuml-preamble.tex and main.tex"
                File(root, "out/a.tex").isFile shouldBe true
                latexStatusView(done).tone() shouldBe StatusTone.SUCCESS
                val ask = run(root, form(root)).state.shouldBeInstanceOf<LatexUiState.ConfirmOverwrite>()
                ask.existing.toSet() shouldBe setOf("a.tex", "kuml-preamble.tex", "main.tex")
            } finally {
                root.deleteRecursively()
            }
        }

        test("Engineering folder with a broken script: written, shown red; with strict nothing is written").config(timeout = 400.seconds) {
            val root = engineering(broken = true)
            try {
                val strict = run(root, form(root, strict = true, out = "strict-out")).state
                strict.shouldBeInstanceOf<LatexUiState.StrictBlocked>()
                File(root, "strict-out").exists() shouldBe false
                val done = run(root, form(root)).state.shouldBeInstanceOf<LatexUiState.Done>()
                done.counts.failed shouldBe 1
                latexStatusView(done).tone() shouldBe StatusTone.ERROR
                done.lines.last().text shouldContain "ERROR broken.kuml.kts:"
            } finally {
                root.deleteRecursively()
            }
        }

        test("a folder whose only script is broken has nothing to export").config(timeout = 400.seconds) {
            val root = Files.createTempDirectory("kuml-desk-latex-n").toFile()
            try {
                File(root, "broken.kuml.kts").writeText("this is not kotlin (")
                val state = run(root, form(root)).state.shouldBeInstanceOf<LatexUiState.NothingToExport>()
                state.counts.failed shouldBe 1
                File(root, "out").exists() shouldBe false
            } finally {
                root.deleteRecursively()
            }
        }

        test("a cancelled run is discarded and writes nothing").config(timeout = 400.seconds) {
            val root = engineering()
            try {
                run(root, form(root), isCancelled = { true }).state shouldBe LatexUiState.Cancelled
                run(root, form(root), onWriting = { false }).state shouldBe LatexUiState.Cancelled
                File(root, "out").exists() shouldBe false
            } finally {
                root.deleteRecursively()
            }
        }

        test("a busy evaluation gate is reported as transient and the gate is not taken").config(timeout = 60.seconds) {
            val root = engineering()
            try {
                val gate = Semaphore(1)
                gate.acquire()
                run(root, form(root), gate = gate).state shouldBe LatexUiState.Busy
                gate.availablePermits() shouldBe 0
            } finally {
                root.deleteRecursively()
            }
        }

        test("the gate is released after a run, also after a failure").config(timeout = 400.seconds) {
            val root = engineering()
            try {
                val gate = Semaphore(1)
                run(root, form(root), gate = gate)
                gate.availablePermits() shouldBe 1
                val failing =
                    runWorkspaceLatexExport(
                        root = root,
                        form = form(root),
                        evalGate = gate,
                        isCancelled = { false },
                        preparer = { _, _, _ -> throw IllegalArgumentException("boom\u001B[31m") },
                    )
                val failed = failing.state.shouldBeInstanceOf<LatexUiState.Failed>()
                withClue("control characters must be neutralised") { failed.message.none { Character.isISOControl(it) } shouldBe true }
                gate.availablePermits() shouldBe 1
            } finally {
                root.deleteRecursively()
            }
        }

        test("an I/O problem with the target (a file in the way) is reported, not thrown").config(timeout = 400.seconds) {
            val root = engineering()
            try {
                File(root, "out").writeText("i am a file")
                val state = run(root, form(root)).state.shouldBeInstanceOf<LatexUiState.Failed>()
                state.message shouldContain "not a plain directory"
            } finally {
                root.deleteRecursively()
            }
        }

        test("the export refuses to write outside the chosen folder through the one guarded path").config(timeout = 400.seconds) {
            val root = engineering()
            try {
                val outside = Files.createTempDirectory("kuml-desk-latex-outside").toFile()
                try {
                    val link = File(root, "link")
                    val linked = runCatching { Files.createSymbolicLink(link.toPath(), outside.toPath()) }.isSuccess
                    if (linked) {
                        val state = run(root, form(root, out = "link")).state
                        state.shouldBeInstanceOf<LatexUiState.Failed>()
                        outside.list()!!.toList() shouldBe emptyList()
                    }
                } finally {
                    outside.deleteRecursively()
                }
            } finally {
                root.deleteRecursively()
            }
        }
    })
