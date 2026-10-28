package dev.kuml.cli.workspace

import dev.kuml.workspace.WorkspaceScanner
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.io.File
import java.nio.file.Files

class WorkspaceRendererThemeTest :
    FunSpec({

        val demoRoot = File("src/test/resources/okf-workspace")

        test("default themeName (null) keeps the previous behaviour: all diagram blocks render") {
            val docs = WorkspaceScanner.scan(root = demoRoot).documents.filter { it.kumlBlocks.isNotEmpty() }
            val out = Files.createTempDirectory("kuml-ws-theme").toFile()
            try {
                val report = WorkspaceRenderer.render(documents = docs, outputDir = out, format = "svg", width = 800, mirror = false)
                report.failures.shouldBeEmpty()
                report.rendered.size shouldBe docs.size
            } finally {
                out.deleteRecursively()
            }
        }

        test("themeName is passed through to the pipeline: an unknown theme fails the block") {
            val docs = WorkspaceScanner.scan(root = demoRoot).documents.filter { it.kumlBlocks.isNotEmpty() }
            val out = Files.createTempDirectory("kuml-ws-theme").toFile()
            try {
                val report =
                    WorkspaceRenderer.render(
                        documents = docs,
                        outputDir = out,
                        format = "svg",
                        width = 800,
                        mirror = false,
                        themeName = "no-such-theme",
                    )
                report.failures.shouldNotBeEmpty()
                report.failures.first().message shouldContain "no-such-theme"
            } finally {
                out.deleteRecursively()
            }
        }
    })
