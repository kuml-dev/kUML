package dev.kuml.desktop.io

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import java.io.File
import java.nio.file.Files

class RecentFilesTest : FunSpec({

    test("add-new inserts path at the front") {
        val result = RecentFiles.add(listOf("/a.kts", "/b.kts"), "/c.kts")
        result[0] shouldBe "/c.kts"
        result shouldContain "/a.kts"
        result shouldContain "/b.kts"
    }

    test("promote-existing moves path to front without duplicates") {
        val result = RecentFiles.add(listOf("/a.kts", "/b.kts", "/c.kts"), "/b.kts")
        result[0] shouldBe "/b.kts"
        result shouldHaveSize 3
        result.count { it == "/b.kts" } shouldBe 1
    }

    test("add caps list at max=10") {
        val existing = (1..10).map { "/file$it.kts" }
        val result = RecentFiles.add(existing, "/new.kts", max = 10)
        result shouldHaveSize 10
        result[0] shouldBe "/new.kts"
        result shouldNotContain "/file10.kts"
    }

    test("pruneMissing removes non-existent paths, keeps existing") {
        val tempDir = Files.createTempDirectory("kuml-recent-test").toFile()
        val existingFile = File(tempDir, "exists.kts").also { it.writeText("") }
        val missingPath = File(tempDir, "missing.kts").absolutePath

        try {
            val result = RecentFiles.pruneMissing(listOf(existingFile.absolutePath, missingPath))
            result shouldContain existingFile.absolutePath
            result shouldNotContain missingPath
        } finally {
            existingFile.delete()
            tempDir.delete()
        }
    }

    test("remove eliminates specified path") {
        val result = RecentFiles.remove(listOf("/a.kts", "/b.kts", "/c.kts"), "/b.kts")
        result shouldNotContain "/b.kts"
        result shouldContain "/a.kts"
        result shouldContain "/c.kts"
    }
})
