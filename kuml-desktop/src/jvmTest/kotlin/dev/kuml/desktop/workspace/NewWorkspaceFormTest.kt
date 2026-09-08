package dev.kuml.desktop.workspace

import dev.kuml.workspace.WorkspaceMode
import dev.kuml.workspace.WorkspaceScanner
import dev.kuml.workspace.scaffold.WorkspaceScaffolder
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import java.io.File
import java.nio.file.Files

/**
 * Pure-logic tests for [NewWorkspaceFormState] (V3.x, FT-Desktop-New-Workspace), following the
 * same reasoning as [WorkspaceTrustTest] in this package — no Compose test harness exists in
 * this module, so the form's decisions are tested directly against [NewWorkspaceFormState]/
 * [WorkspaceScaffolder] rather than through [NewWorkspaceDialog] itself.
 */
class NewWorkspaceFormTest :
    FunSpec({

        fun tempDir(prefix: String): File = Files.createTempDirectory(prefix).toFile()

        test("empty name is NAME_EMPTY and cannot create") {
            val parent = tempDir("kuml-nwf-empty")
            try {
                val form = NewWorkspaceFormState(parentDir = parent)
                form.error shouldBe NewWorkspaceError.NAME_EMPTY
                form.canCreate shouldBe false
            } finally {
                parent.deleteRecursively()
            }
        }

        test("a normal name with an empty parent produces the expected slug/target and can create") {
            val parent = tempDir("kuml-nwf-normal")
            try {
                val form = NewWorkspaceFormState(name = "My Club Bylaws", parentDir = parent)
                form.slug shouldBe "my-club-bylaws"
                form.targetDir shouldBe File(parent, "my-club-bylaws")
                form.canCreate shouldBe true
                form.error shouldBe null
            } finally {
                parent.deleteRecursively()
            }
        }

        test("a name with no safe characters falls back to the 'workspace' slug") {
            val parent = tempDir("kuml-nwf-fallback")
            try {
                val form = NewWorkspaceFormState(name = "!!!", parentDir = parent)
                form.slug shouldBe "workspace"
                form.targetDir.name shouldBe "workspace"
                form.canCreate shouldBe true
            } finally {
                parent.deleteRecursively()
            }
        }

        test("an existing non-empty target directory is TARGET_NOT_EMPTY and cannot create") {
            val parent = tempDir("kuml-nwf-nonempty")
            try {
                val existing = File(parent, "taken")
                existing.mkdirs()
                File(existing, "file.txt").writeText("x")
                val form = NewWorkspaceFormState(name = "Taken", parentDir = parent)
                form.targetDir shouldBe existing
                form.error shouldBe NewWorkspaceError.TARGET_NOT_EMPTY
                form.canCreate shouldBe false
            } finally {
                parent.deleteRecursively()
            }
        }

        test("an existing but empty target directory can create") {
            val parent = tempDir("kuml-nwf-emptydir")
            try {
                val existing = File(parent, "empty-target")
                existing.mkdirs()
                val form = NewWorkspaceFormState(name = "Empty Target", parentDir = parent)
                form.targetDir shouldBe existing
                form.error shouldBe null
                form.canCreate shouldBe true
            } finally {
                parent.deleteRecursively()
            }
        }

        test("switching mode does not change slug or targetDir") {
            val parent = tempDir("kuml-nwf-mode")
            try {
                val knowledge = NewWorkspaceFormState(name = "Same Name", parentDir = parent, mode = NewWorkspaceMode.KNOWLEDGE)
                val engineering = knowledge.copy(mode = NewWorkspaceMode.ENGINEERING)
                engineering.slug shouldBe knowledge.slug
                engineering.targetDir shouldBe knowledge.targetDir
            } finally {
                parent.deleteRecursively()
            }
        }

        test("toSpec maps KNOWLEDGE and ENGINEERING to the matching WorkspaceInitSpec mode string") {
            val parent = tempDir("kuml-nwf-tospec")
            try {
                val knowledgeSpec = NewWorkspaceFormState(name = "T", parentDir = parent, mode = NewWorkspaceMode.KNOWLEDGE).toSpec()
                knowledgeSpec.mode shouldBe "knowledge"
                val engineeringSpec = NewWorkspaceFormState(name = "T", parentDir = parent, mode = NewWorkspaceMode.ENGINEERING).toSpec()
                engineeringSpec.mode shouldBe "engineering"
            } finally {
                parent.deleteRecursively()
            }
        }

        test("invariant: canCreate is exactly the absence of a blocking (non-retryable) error") {
            // NAME_EMPTY and TARGET_NOT_EMPTY are validation problems with the form's current
            // values and block the button. WRITE_FAILED is a transient runtime condition (see
            // the "retry" tests below) and deliberately does NOT block it — canCreate is
            // therefore not simply `error == null`.
            val parent = tempDir("kuml-nwf-invariant")
            try {
                val existingNonEmpty = File(parent, "busy")
                existingNonEmpty.mkdirs()
                File(existingNonEmpty, "f.txt").writeText("x")

                val states =
                    listOf(
                        NewWorkspaceFormState(parentDir = parent),
                        NewWorkspaceFormState(name = "Fresh Name", parentDir = parent),
                        NewWorkspaceFormState(name = "busy", parentDir = parent),
                        NewWorkspaceFormState(name = "Fresh Name", parentDir = parent, runtimeError = "boom"),
                        NewWorkspaceFormState(name = "", parentDir = parent, nameTouched = true),
                    )
                for (state in states) {
                    val isBlocking = state.error == NewWorkspaceError.NAME_EMPTY || state.error == NewWorkspaceError.TARGET_NOT_EMPTY
                    (!state.canCreate) shouldBe isBlocking
                }
            } finally {
                parent.deleteRecursively()
            }
        }

        test("a WRITE_FAILED runtime error does not leave the user stuck — canCreate stays true so 'Erstellen' can be clicked again") {
            val parent = tempDir("kuml-nwf-retry")
            try {
                val failed = NewWorkspaceFormState(name = "Retry Me", parentDir = parent, runtimeError = "disk full")
                failed.error shouldBe NewWorkspaceError.WRITE_FAILED
                failed.canCreate shouldBe true

                // Mirrors what NewWorkspaceDialog.create() does at the start of every attempt
                // (including a retry after a prior failure): clear the stale runtimeError
                // before trying again, rather than relying on the user to edit the name or pick
                // a new target directory just to unstick the button.
                val retried = failed.copy(runtimeError = null)
                retried.error shouldBe null
                retried.canCreate shouldBe true
            } finally {
                parent.deleteRecursively()
            }
        }

        test("switching mode after a runtime failure also clears the stale error, like the name field and Browse do") {
            val parent = tempDir("kuml-nwf-retry-mode")
            try {
                val failed =
                    NewWorkspaceFormState(
                        name = "Retry Me",
                        parentDir = parent,
                        mode = NewWorkspaceMode.KNOWLEDGE,
                        runtimeError = "disk full",
                    )
                // What NewWorkspaceDialog's ModeOption onClick handlers do.
                val afterModeSwitch = failed.copy(mode = NewWorkspaceMode.ENGINEERING, runtimeError = null)
                afterModeSwitch.error shouldBe null
                afterModeSwitch.canCreate shouldBe true
            } finally {
                parent.deleteRecursively()
            }
        }

        // ── Integration: real scaffold via WorkspaceScaffolder ───────────────

        test("happy path knowledge: scaffold creates the 5-file set and scans as KNOWLEDGE") {
            val parent = tempDir("kuml-nwf-e2e-knowledge")
            try {
                val form = NewWorkspaceFormState(name = "Muster Verein", parentDir = parent, mode = NewWorkspaceMode.KNOWLEDGE)
                WorkspaceScaffolder.scaffold(spec = form.toSpec(), targetDir = form.targetDir, force = false, echo = {})

                File(form.targetDir, ".kuml-workspace.toml").exists() shouldBe true
                WorkspaceScanner.scan(root = form.targetDir).mode shouldBe WorkspaceMode.KNOWLEDGE
            } finally {
                parent.deleteRecursively()
            }
        }

        test("happy path engineering: scaffold creates <slug>.kuml.kts and scans as ENGINEERING") {
            val parent = tempDir("kuml-nwf-e2e-engineering")
            try {
                val form = NewWorkspaceFormState(name = "My Diagrams", parentDir = parent, mode = NewWorkspaceMode.ENGINEERING)
                WorkspaceScaffolder.scaffold(spec = form.toSpec(), targetDir = form.targetDir, force = false, echo = {})

                File(form.targetDir, "${form.slug}.kuml.kts").exists() shouldBe true
                WorkspaceScanner.scan(root = form.targetDir).mode shouldBe WorkspaceMode.ENGINEERING
            } finally {
                parent.deleteRecursively()
            }
        }

        test("scaffold on a non-empty target throws and leaves the existing file untouched") {
            val parent = tempDir("kuml-nwf-e2e-noforce")
            try {
                val form = NewWorkspaceFormState(name = "Occupied", parentDir = parent)
                form.targetDir.mkdirs()
                val existingFile = File(form.targetDir, "keep.txt")
                existingFile.writeText("pre-existing")
                val bytesBefore = existingFile.readBytes()

                val result =
                    runCatching {
                        WorkspaceScaffolder.scaffold(spec = form.toSpec(), targetDir = form.targetDir, force = false, echo = {})
                    }
                result.isFailure shouldBe true
                result.exceptionOrNull() shouldNotBe null
                existingFile.readBytes() shouldBe bytesBefore
            } finally {
                parent.deleteRecursively()
            }
        }
    })
