package dev.kuml.lsp

import java.io.File
import java.nio.file.Files

/**
 * Test-only helper: writes a platform-appropriate fake `kuml` CLI stub that
 * ignores its `diagnostics <file>` arguments and prints the given TSV lines
 * (with real tab characters, single-quoted so the shell passes them through
 * literally) to stdout — optionally sleeping first to exercise timeout paths.
 *
 * Keeps `./gradlew check` hermetic on CI, which never has the real kUML CLI on
 * `PATH`; the real-CLI wiring is already proven by `DiagnosticsCommandTest`.
 *
 * Returns `null` if a usable executable could not be produced on this
 * platform — callers should skip (not fail) the affected test case.
 */
internal object FakeCli {
    fun write(
        tsvLines: List<String>,
        sleepMs: Long = 0,
    ): File? {
        val dir = Files.createTempDirectory("kuml-lsp-fakecli").toFile()
        return if (isWindows()) writeBat(dir, tsvLines, sleepMs) else writeSh(dir, tsvLines, sleepMs)
    }

    private fun writeSh(
        dir: File,
        tsvLines: List<String>,
        sleepMs: Long,
    ): File? {
        val script = File(dir, "fake-kuml.sh")
        val body =
            buildString {
                appendLine("#!/bin/sh")
                if (sleepMs > 0) {
                    val seconds = ((sleepMs + 999) / 1000).coerceAtLeast(1)
                    appendLine("sleep $seconds")
                }
                tsvLines.forEach { line -> appendLine("printf '%s\\n' '$line'") }
            }
        script.writeText(body)
        return if (script.setExecutable(true)) script else null
    }

    private fun writeBat(
        dir: File,
        tsvLines: List<String>,
        sleepMs: Long,
    ): File? {
        val script = File(dir, "fake-kuml.bat")
        val body =
            buildString {
                appendLine("@echo off")
                if (sleepMs > 0) {
                    val seconds = ((sleepMs + 999) / 1000).coerceAtLeast(1)
                    appendLine("ping -n ${seconds + 1} 127.0.0.1 >nul")
                }
                tsvLines.forEach { line -> appendLine("echo $line") }
            }
        script.writeText(body)
        return script
    }

    private fun isWindows(): Boolean =
        System
            .getProperty("os.name")
            .orEmpty()
            .lowercase()
            .contains("win")
}
