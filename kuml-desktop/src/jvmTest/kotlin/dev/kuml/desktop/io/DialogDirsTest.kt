package dev.kuml.desktop.io

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.io.File
import java.nio.file.Files

class DialogDirsTest :
    FunSpec({

        fun withTemp(block: (home: File, other: File) -> Unit) {
            val home = Files.createTempDirectory("kuml-home").toFile()
            val other = Files.createTempDirectory("kuml-other").toFile()
            try {
                block(home, other)
            } finally {
                home.deleteRecursively()
                other.deleteRecursively()
            }
        }

        test("null and blank fall back to home") {
            withTemp { home, _ ->
                DialogDirs.resolve(stored = null, userHome = home) shouldBe home
                DialogDirs.resolve(stored = "", userHome = home) shouldBe home
                DialogDirs.resolve(stored = "  ", userHome = home) shouldBe home
            }
        }

        test("existing directory is returned") {
            withTemp { home, other ->
                DialogDirs.resolve(stored = other.absolutePath, userHome = home) shouldBe other
            }
        }

        test("missing directory falls back to home") {
            withTemp { home, other ->
                DialogDirs.resolve(stored = File(other, "gone").absolutePath, userHome = home) shouldBe home
            }
        }

        test("a file path falls back to home") {
            withTemp { home, other ->
                val f = File(other, "f.txt").apply { writeText("x") }
                DialogDirs.resolve(stored = f.absolutePath, userHome = home) shouldBe home
            }
        }

        test("directory helpers") {
            withTemp { _, other ->
                val sub = File(other, "sub")
                DialogDirs.dirOfFile(File(sub, "a.svg")) shouldBe sub.absolutePath
                DialogDirs.dirOfDirectoryChoice(sub) shouldBe sub.absolutePath
                DialogDirs.parentOfDirectoryChoice(sub) shouldBe other.absolutePath
                val root = File("/")
                DialogDirs.parentOfDirectoryChoice(root) shouldBe root.absolutePath
                DialogDirs.dirOfFile(root) shouldBe null
            }
        }
    })
