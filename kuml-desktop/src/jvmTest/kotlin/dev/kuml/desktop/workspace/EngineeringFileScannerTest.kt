package dev.kuml.desktop.workspace

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.nio.file.Files

class EngineeringFileScannerTest : FunSpec({

    test("finds *.kuml.kts files recursively, sorted by relative path") {
        val root = Files.createTempDirectory("kuml-eng-scan-test").toFile()
        try {
            java.io.File(root, "b.kuml.kts").writeText("// b")
            java.io.File(root, "sub").mkdirs()
            java.io.File(root, "sub/a.kuml.kts").writeText("// a")
            java.io.File(root, "not-a-script.md").writeText("# ignored")

            val files = EngineeringFileScanner.scan(root)

            files.map { it.relativeTo(root).path.replace(java.io.File.separatorChar, '/') } shouldBe
                listOf("b.kuml.kts", "sub/a.kuml.kts")
        } finally {
            root.deleteRecursively()
        }
    }

    test("skips hidden directories") {
        val root = Files.createTempDirectory("kuml-eng-scan-test").toFile()
        try {
            java.io.File(root, ".hidden").mkdirs()
            java.io.File(root, ".hidden/secret.kuml.kts").writeText("// hidden")
            java.io.File(root, "visible.kuml.kts").writeText("// visible")

            val files = EngineeringFileScanner.scan(root)

            files.map { it.name } shouldBe listOf("visible.kuml.kts")
        } finally {
            root.deleteRecursively()
        }
    }

    test("empty directory yields an empty list") {
        val root = Files.createTempDirectory("kuml-eng-scan-test").toFile()
        try {
            EngineeringFileScanner.scan(root) shouldBe emptyList()
        } finally {
            root.deleteRecursively()
        }
    }
})
