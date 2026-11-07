package dev.kuml.desktop.workspace

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.io.File
import java.nio.file.Files

class WorkspaceLatexExportFormTest :
    FunSpec({
        fun tempRoot(): File = Files.createTempDirectory("kuml-latex-form").toFile().canonicalFile

        test("the initial target is the CLI default below the workspace") {
            val root = tempRoot()
            try {
                val form = WorkspaceLatexFormState.initial(root = root, themeName = "kuml")
                form.targetPath shouldBe File(root, ".kuml-out/export/latex").path
                form.strict shouldBe false
                form.canSubmit(root) shouldBe true
            } finally {
                root.deleteRecursively()
            }
        }

        test("a relative path is taken relative to the workspace, not to the working directory") {
            val root = tempRoot()
            try {
                resolveLatexTarget(path = "out/tex", root = root) shouldBe File(root, "out/tex")
                resolveLatexTarget(path = "a/../b", root = root) shouldBe File(root, "b")
            } finally {
                root.deleteRecursively()
            }
        }

        test("the workspace folder itself, a blank path and control characters are rejected") {
            val root = tempRoot()
            try {
                val base = WorkspaceLatexFormState(targetPath = root.path, themeName = "kuml")
                base.problem(root) shouldBe LatexTargetProblem.IS_WORKSPACE_ROOT
                base.copy(targetPath = "${root.path}/.").problem(root) shouldBe LatexTargetProblem.IS_WORKSPACE_ROOT
                base.copy(targetPath = "  ").problem(root) shouldBe LatexTargetProblem.EMPTY_OR_INVALID
                base.copy(targetPath = "out\u001B[31m").problem(root) shouldBe LatexTargetProblem.EMPTY_OR_INVALID
                base.copy(targetPath = "out\nx").canSubmit(root) shouldBe false
                base.copy(targetPath = "sub").canSubmit(root) shouldBe true
            } finally {
                root.deleteRecursively()
            }
        }

        test("a target typed through a symbolic link resolves to the canonical location") {
            val root = tempRoot()
            try {
                val real = File(root, "real").also { it.mkdirs() }
                val link = File(root, "link")
                val linked = runCatching { Files.createSymbolicLink(link.toPath(), real.toPath()) }.isSuccess
                if (linked) {
                    resolveLatexTarget(path = File(link, "out").path, root = root) shouldBe File(real, "out")
                }
            } finally {
                root.deleteRecursively()
            }
        }
    })
