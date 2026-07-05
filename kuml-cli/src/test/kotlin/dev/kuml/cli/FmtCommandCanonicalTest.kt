package dev.kuml.cli

import com.github.ajalt.clikt.testing.test
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.io.File

/**
 * Tests for `kuml fmt --canonical` and its interaction with `--check`.
 *
 * Verifies:
 * 1. `--canonical` writes the canonical form to file.
 * 2. `--canonical --check` on a non-canonical file exits with [ExitCodes.FMT_CHECK_FAILED].
 * 3. `--canonical --check` on an already-canonical file exits 0.
 * 4. Plain `kuml fmt` (without `--canonical`) is unaffected by V3.0.1 additions.
 */
class FmtCommandCanonicalTest :
    StringSpec({
        "fmt --canonical: writes canonical form to file" {
            val file = createTempKumlFile("model {\n\n    state(\"A\")\n\n}\n")
            try {
                val result = FmtCommand().test(listOf("--canonical", file.absolutePath))
                result.statusCode shouldBe 0
                result.output shouldContain "formatted"
                // File must now contain no blank lines
                file.readText() shouldBe "model {\n    state(\"A\")\n}\n"
            } finally {
                file.delete()
            }
        }

        "fmt --canonical --check: non-canonical file exits FMT_CHECK_FAILED (5)" {
            // A file with blank lines is valid fmt-output but not canonical
            val file = createTempKumlFile("model {\n\n    state(\"A\")\n\n}\n")
            try {
                val result = FmtCommand().test(listOf("--canonical", "--check", file.absolutePath))
                result.statusCode shouldBe ExitCodes.FMT_CHECK_FAILED
                result.output shouldContain "needs formatting"
            } finally {
                file.delete()
            }
        }

        "fmt --canonical --check: already-canonical file exits 0" {
            val canonical = "model {\n    state(\"A\")\n}\n"
            val file = createTempKumlFile(canonical)
            try {
                val result = FmtCommand().test(listOf("--canonical", "--check", file.absolutePath))
                result.statusCode shouldBe 0
                result.output shouldContain ": ok"
            } finally {
                file.delete()
            }
        }

        "fmt (without --canonical): behaviour unchanged by V3.0.1 additions" {
            // A file with one blank line is already fmt-compliant — must stay as-is
            val alreadyFormatted = "model {\n\n    state(\"A\")\n}\n"
            val file = createTempKumlFile(alreadyFormatted)
            try {
                val result = FmtCommand().test(listOf("--check", file.absolutePath))
                result.statusCode shouldBe 0
                result.output shouldContain ": ok"
                // File must be untouched
                file.readText() shouldBe alreadyFormatted
            } finally {
                file.delete()
            }
        }
    })

// ── helpers ───────────────────────────────────────────────────────────────────

private fun createTempKumlFile(content: String): File {
    val file = File.createTempFile("fmt-canonical-test-", ".kuml.kts")
    file.writeText(content)
    return file
}
